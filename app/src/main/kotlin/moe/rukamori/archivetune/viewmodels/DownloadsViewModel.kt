/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.utils.PlaylistOfflineStatus
import moe.rukamori.archivetune.utils.SyncUtils
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
    val startTimeMs: Long,
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
        private val syncUtils: SyncUtils,
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
                            startTimeMs = download.startTimeMs,
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
                            .thenBy { it.startTimeMs }
                            .thenBy { it.title.lowercase() },
                    )
            }.flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val playlistStatuses: Flow<List<PlaylistOfflineStatus>> =
            combine(
                database.keepOfflinePlaylists(),
                downloadUtil.downloads,
            ) { playlists, downloads ->
                val bytesBySongId =
                    downloads.mapValues { (_, download) ->
                        if (download.state == Download.STATE_COMPLETED) download.contentLength.coerceAtLeast(0L) else 0L
                    }
                playlists
                    .map { playlist ->
                        PlaylistOfflineStatus.compute(
                            songIds = database.playlistSongs(playlist.id).first().map { it.song.id },
                            downloads = downloads.mapValues { it.value.state },
                            name = playlist.playlist.name,
                            playlistId = playlist.id,
                            bytesBySongId = bytesBySongId,
                            spotifyId = playlist.playlist.spotifyId,
                        )
                    }.sortedWith(
                        compareBy<PlaylistOfflineStatus> { it.isFullyOffline }
                            .thenByDescending { it.completeness },
                    )
            }.flowOn(Dispatchers.IO)

        fun syncPlaylist(spotifyId: String) {
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(context, R.string.playlist_sync_started, Toast.LENGTH_SHORT).show()
                val outcome =
                    runCatching {
                        withContext(Dispatchers.IO) { syncUtils.syncSingleSpotifyPlaylist(spotifyId) }
                    }
                if (outcome.getOrDefault(false)) return@launch
                val detail =
                    outcome.exceptionOrNull()?.let { e ->
                        e.localizedMessage?.takeIf(String::isNotBlank) ?: e.javaClass.simpleName
                    } ?: context.getString(R.string.error_unknown)
                Toast.makeText(
                    context,
                    context.getString(R.string.playlist_sync_failed, detail),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

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
