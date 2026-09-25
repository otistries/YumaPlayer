/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import androidx.media3.exoplayer.offline.Download

data class PlaylistOfflineStatus(
    val playlistId: String,
    val name: String,
    val total: Int,
    val downloaded: Int,
    val failed: Int,
    val active: Int,
    val bytesDownloaded: Long = 0,
    val spotifyId: String? = null,
) {
    val isFullyOffline: Boolean
        get() = total > 0 && downloaded == total

    val completeness: Float
        get() = if (total == 0) 0f else downloaded.toFloat() / total

    companion object {
        fun compute(
            songIds: List<String>,
            downloads: Map<String, Int>,
            name: String,
            playlistId: String,
            bytesBySongId: Map<String, Long> = emptyMap(),
            spotifyId: String? = null,
        ): PlaylistOfflineStatus {
            val distinctIds = songIds.distinct()
            var downloaded = 0
            var failed = 0
            var active = 0
            var bytes = 0L
            distinctIds.forEach { songId ->
                when (downloads[songId]) {
                    Download.STATE_COMPLETED -> {
                        downloaded++
                        bytes += (bytesBySongId[songId] ?: 0L).coerceAtLeast(0L)
                    }

                    Download.STATE_FAILED -> failed++
                    Download.STATE_QUEUED,
                    Download.STATE_DOWNLOADING,
                    Download.STATE_RESTARTING,
                    -> active++
                }
            }
            return PlaylistOfflineStatus(
                playlistId = playlistId,
                name = name,
                total = distinctIds.size,
                downloaded = downloaded,
                failed = failed,
                active = active,
                bytesDownloaded = bytes,
                spotifyId = spotifyId,
            )
        }
    }
}

object OfflineSyncLogic {
    fun orphanedDownloadIds(
        beforeIdsByPlaylist: Map<String, Set<String>>,
        afterIdsByPlaylist: Map<String, Set<String>>,
        songIdsInAnyPlaylist: Set<String>,
        likedSongIds: Set<String>,
    ): Set<String> {
        val afterIds = afterIdsByPlaylist.values.flatten().toSet()
        return beforeIdsByPlaylist
            .flatMap { (playlistId, beforeIds) ->
                val afterIdsForPlaylist = afterIdsByPlaylist[playlistId] ?: emptySet()
                beforeIds.filter { it !in afterIdsForPlaylist }
            }.toSet()
            .filterTo(mutableSetOf()) { songId ->
                songId !in afterIds && songId !in songIdsInAnyPlaylist && songId !in likedSongIds
            }
    }
}
