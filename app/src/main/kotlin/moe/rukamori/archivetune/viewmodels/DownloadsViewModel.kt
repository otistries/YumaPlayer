/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.playback.ExoDownloadService
import javax.inject.Inject

enum class DownloadQueueFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    FAILED,
}

data class DownloadQueueItem(
    val songId: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val state: Int,
    val stopReason: Int,
    val percentDownloaded: Float,
    val bytesDownloaded: Long,
    val contentLength: Long,
) {
    val isActive: Boolean
        get() =
            when (state) {
                Download.STATE_QUEUED,
                Download.STATE_DOWNLOADING,
                Download.STATE_RESTARTING,
                -> true

                Download.STATE_STOPPED -> stopReason != 0
                else -> false
            }

    val isFailed: Boolean
        get() = state == Download.STATE_FAILED

    val isCompleted: Boolean
        get() = state == Download.STATE_COMPLETED
}

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadUtil: DownloadUtil,
        database: MusicDatabase,
    ) : ViewModel() {
        val filter = MutableStateFlow(DownloadQueueFilter.ALL)

        val items: StateFlow<List<DownloadQueueItem>> =
            combine(
                downloadUtil.downloads,
                filter,
            ) { downloads, activeFilter ->
                downloads.entries
                    .map { (songId, download) ->
                        val song = database.getSongByIdBlocking(songId)
                        val requestTitle =
                            download.request.data
                                ?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotEmpty() }
                        DownloadQueueItem(
                            songId = songId,
                            title = song?.song?.title ?: requestTitle ?: songId,
                            artist = song?.artists?.joinToString(", ") { it.name },
                            thumbnailUrl = song?.song?.thumbnailUrl,
                            state = download.state,
                            stopReason = download.stopReason,
                            percentDownloaded = download.percentDownloaded,
                            bytesDownloaded = download.bytesDownloaded,
                            contentLength = download.contentLength,
                        )
                    }
                    .filter { item ->
                        when (activeFilter) {
                            DownloadQueueFilter.ALL -> true
                            DownloadQueueFilter.ACTIVE -> item.isActive
                            DownloadQueueFilter.COMPLETED -> item.isCompleted
                            DownloadQueueFilter.FAILED -> item.isFailed
                        }
                    }
                    .sortedWith(
                        compareByDescending<DownloadQueueItem> { it.isActive }
                            .thenByDescending { it.isFailed }
                            .thenBy { it.title.lowercase() },
                    )
            }.flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun retry(item: DownloadQueueItem) {
            val download = downloadUtil.downloads.value[item.songId] ?: return
            runCatching {
                DownloadService.sendAddDownload(
                    context,
                    ExoDownloadService::class.java,
                    download.request,
                    false,
                )
            }
        }

        fun retryAllFailed() {
            downloadUtil.downloads.value.values
                .filter { it.state == Download.STATE_FAILED }
                .forEach { download ->
                    runCatching {
                        DownloadService.sendAddDownload(
                            context,
                            ExoDownloadService::class.java,
                            download.request,
                            false,
                        )
                    }
                }
        }

        fun pauseAll() = sendStopReason(1)

        fun resumeAll() = sendStopReason(0)

        fun remove(item: DownloadQueueItem) {
            DownloadService.sendRemoveDownload(
                context,
                ExoDownloadService::class.java,
                item.songId,
                false,
            )
        }

        private fun sendStopReason(stopReason: Int) {
            viewModelScope.launch(Dispatchers.IO) {
                downloadUtil.downloads.value.values
                    .filter { it.state != Download.STATE_COMPLETED }
                    .forEach { download ->
                        runCatching {
                            DownloadService.sendSetStopReason(
                                context,
                                ExoDownloadService::class.java,
                                download.request.id,
                                stopReason,
                                false,
                            )
                        }
                    }
            }
        }
    }
