/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.*
import org.junit.Test

class OfflineSyncLogicTest {

    @Test
    fun orphanedDownloadIds_removedAndGoneEverywhere_isOrphan() {
        val orphans =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = mapOf("p1" to setOf("a", "b")),
                afterIdsByPlaylist = mapOf("p1" to setOf("b")),
                songIdsInAnyPlaylist = setOf("b"),
                likedSongIds = emptySet(),
            )

        assertEquals(setOf("a"), orphans)
    }

    @Test
    fun orphanedDownloadIds_stillInAnotherPlaylist_isNotOrphan() {
        val orphans =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = mapOf("p1" to setOf("a")),
                afterIdsByPlaylist = mapOf("p2" to setOf("a")),
                songIdsInAnyPlaylist = emptySet(),
                likedSongIds = emptySet(),
            )

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun orphanedDownloadIds_likedSong_isNotOrphan() {
        val orphans =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = mapOf("p1" to setOf("a")),
                afterIdsByPlaylist = emptyMap(),
                songIdsInAnyPlaylist = emptySet(),
                likedSongIds = setOf("a"),
            )

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun orphanedDownloadIds_stillInSamePlaylist_isNotOrphan() {
        val orphans =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = mapOf("p1" to setOf("a", "b")),
                afterIdsByPlaylist = mapOf("p1" to setOf("a", "b", "c")),
                songIdsInAnyPlaylist = setOf("a", "b", "c"),
                likedSongIds = emptySet(),
            )

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun orphanedDownloadIds_songNotInAnyPlaylistBefore_isIgnored() {
        val orphans =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = mapOf("p1" to setOf("a")),
                afterIdsByPlaylist = emptyMap(),
                songIdsInAnyPlaylist = emptySet(),
                likedSongIds = emptySet(),
            )

        assertEquals(setOf("a"), orphans)

        // A song that never appeared in any keepOffline playlist before is not considered
        val orphans2 =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = emptyMap(),
                afterIdsByPlaylist = emptyMap(),
                songIdsInAnyPlaylist = emptySet(),
                likedSongIds = emptySet(),
            )

        assertTrue(orphans2.isEmpty())
    }

    @Test
    fun orphanedDownloadIds_songStillInAnyPlaylistByDbQuery_isNotOrphan() {
        val orphans =
            OfflineSyncLogic.orphanedDownloadIds(
                beforeIdsByPlaylist = mapOf("p1" to setOf("a")),
                afterIdsByPlaylist = emptyMap(),
                songIdsInAnyPlaylist = setOf("a"),
                likedSongIds = emptySet(),
            )

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun compute_fullyOfflineBoundary() {
        val downloads =
            mapOf(
                "a" to Download.STATE_COMPLETED,
                "b" to Download.STATE_COMPLETED,
            )

        val status =
            PlaylistOfflineStatus.compute(
                songIds = listOf("a", "b"),
                downloads = downloads,
                name = "My playlist",
                playlistId = "p1",
            )

        assertEquals(2, status.total)
        assertEquals(2, status.downloaded)
        assertEquals(0, status.failed)
        assertEquals(0, status.active)
        assertTrue(status.isFullyOffline)

        val missingOne =
            PlaylistOfflineStatus.compute(
                songIds = listOf("a", "b"),
                downloads = mapOf("a" to Download.STATE_COMPLETED),
                name = "My playlist",
                playlistId = "p1",
            )
        assertFalse(missingOne.isFullyOffline)
        assertEquals(1, missingOne.downloaded)
    }

    @Test
    fun compute_emptyPlaylistIsNotFullyOffline() {
        val status =
            PlaylistOfflineStatus.compute(
                songIds = emptyList(),
                downloads = emptyMap(),
                name = "Empty",
                playlistId = "p1",
            )

        assertEquals(0, status.total)
        assertFalse(status.isFullyOffline)
        assertEquals(0f, status.completeness)
    }

    @Test
    fun compute_countsFailedAndActive() {
        val downloads =
            mapOf(
                "a" to Download.STATE_COMPLETED,
                "b" to Download.STATE_FAILED,
                "c" to Download.STATE_DOWNLOADING,
                "d" to Download.STATE_QUEUED,
                "e" to Download.STATE_RESTARTING,
                "f" to Download.STATE_STOPPED,
            )

        val status =
            PlaylistOfflineStatus.compute(
                songIds = listOf("a", "b", "c", "d", "e", "f"),
                downloads = downloads,
                name = "My playlist",
                playlistId = "p1",
            )

        assertEquals(6, status.total)
        assertEquals(1, status.downloaded)
        assertEquals(1, status.failed)
        assertEquals(3, status.active)
        assertFalse(status.isFullyOffline)
    }

    @Test
    fun compute_deduplicatesSongIds() {
        val status =
            PlaylistOfflineStatus.compute(
                songIds = listOf("a", "a", "b"),
                downloads = mapOf("a" to Download.STATE_COMPLETED, "b" to Download.STATE_COMPLETED),
                name = "My playlist",
                playlistId = "p1",
            )

        assertEquals(2, status.total)
        assertEquals(2, status.downloaded)
        assertTrue(status.isFullyOffline)
    }
}
