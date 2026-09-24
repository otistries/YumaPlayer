/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.SpotifyLikedKeepOfflineKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineResyncer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val downloadUtil: DownloadUtil,
        private val syncState: SyncState,
    ) {
        fun enqueueMissingForKeepOfflinePlaylists() {
            syncState.syncScope.launch {
                try {
                    val keepOfflineLikedSongs =
                        runCatching {
                            context.dataStore.data.first()[SpotifyLikedKeepOfflineKey] ?: false
                        }.getOrElse { e ->
                            Timber.w(e, "Failed to read SpotifyLikedKeepOfflineKey")
                            false
                        }
                    if (keepOfflineLikedSongs) {
                        val missingLikedSongs =
                            database.likedSongsByRowIdAscSpotify().first().map { likedSong ->
                                HeaderDownloadItem(
                                    id = likedSong.song.id,
                                    title = likedSong.song.title,
                                )
                            }
                        if (missingLikedSongs.isNotEmpty()) {
                            runCatching {
                                sendAddMissingDownloads(
                                    context = context,
                                    songs = missingLikedSongs,
                                    downloads = downloadUtil.downloads.value,
                                )
                            }.onFailure {
                                Timber.w(it, "Failed to enqueue missing Spotify liked songs downloads on startup")
                            }
                        }
                    }

                    val keepOfflinePlaylists = database.keepOfflinePlaylists().first()
                    if (keepOfflinePlaylists.isEmpty()) return@launch

                    val downloads = downloadUtil.downloads.value
                    val missing =
                        keepOfflinePlaylists.flatMap { playlist ->
                            database.playlistSongs(playlist.id).first().map { playlistSong ->
                                HeaderDownloadItem(
                                    id = playlistSong.song.id,
                                    title = playlistSong.song.song.title,
                                )
                            }
                        }
                    if (missing.isEmpty()) return@launch

                    runCatching {
                        sendAddMissingDownloads(
                            context = context,
                            songs = missing,
                            downloads = downloads,
                        )
                    }.onFailure {
                        Timber.w(it, "Failed to enqueue missing keep-offline downloads on startup")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to resync keep-offline playlists on startup")
                }
            }
        }
    }
