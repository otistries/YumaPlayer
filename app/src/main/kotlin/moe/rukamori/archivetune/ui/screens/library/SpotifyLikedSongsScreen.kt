/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.SpotifyLikedKeepOfflineKey
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.spotify.SpotifyAccountViewModel
import moe.rukamori.archivetune.spotify.SpotifyLikedSongsViewModel
import moe.rukamori.archivetune.spotify.SpotifyPlaybackResolver
import moe.rukamori.archivetune.spotify.SpotifyLikedSongsQueue
import moe.rukamori.archivetune.ui.component.DraggableScrollbar
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.screens.playlist.LocalPlaylistRemoveDownloadDialog
import moe.rukamori.archivetune.ui.screens.settings.SpotifyLoginFallback
import moe.rukamori.archivetune.ui.screens.settings.SpotifyLoginSheet
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.HeaderDownloadState
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.headerDownloadState
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLikedSongsScreen(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyLikedSongsViewModel = hiltViewModel(),
    spotifyAccountViewModel: SpotifyAccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val spotifyState by spotifyAccountViewModel.uiState.collectAsStateWithLifecycle()
    var showSpotifyLogin by remember { mutableStateOf(false) }

    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()
    val isPlaying by playerConnection?.isPlaying?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    val mediaMetadata by playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<MediaMetadata?>(null) }
    val lazyListState = rememberLazyListState()
    val systemBarsTopPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()

    val showTopBarTitle by remember {
        derivedStateOf { lazyListState.firstVisibleItemIndex > 0 }
    }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var resolvingTrackId by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    val filteredTracks =
        remember(tracks, query.text) {
            if (query.text.isBlank()) {
                tracks
            } else {
                tracks.filter { track ->
                    track.name.contains(query.text, ignoreCase = true) ||
                        track.artists.any { artist -> artist.name.contains(query.text, ignoreCase = true) } ||
                        track.album?.name?.contains(query.text, ignoreCase = true) == true
                }
            }
        }

    val loadedDurationMs =
        remember(tracks) {
            tracks.sumOf { track -> track.durationMs.toLong() }
        }

    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    val (spotifyLikedKeepOffline, setSpotifyLikedKeepOffline) = rememberPreference(SpotifyLikedKeepOfflineKey, false)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val errorContainerColor = MaterialTheme.colorScheme.errorContainer

    val gradientColors = remember(errorContainerColor) {
        listOf(errorContainerColor)
    }

    val gradientAlpha by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex == 0) {
                val offset = lazyListState.firstVisibleItemScrollOffset
                (1f - (offset / 600f)).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }.collect { lastVisibleIndex ->
            if (
                tracks.size >= 5 &&
                lastVisibleIndex != null &&
                lastVisibleIndex >= tracks.size - 5
            ) {
                viewModel.loadMoreSongs()
            }
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    }

    fun playPlaylist(
        startIndex: Int = 0,
        shuffled: Boolean = false,
    ) {
        val queueTracks = if (shuffled) tracks.shuffled() else tracks
        if (queueTracks.isEmpty()) return
        val boundedStartIndex = startIndex.coerceIn(queueTracks.indices)
        val preloadTrack = queueTracks[boundedStartIndex]
        if (resolvingTrackId != null) return

        coroutineScope.launch {
            resolvingTrackId = preloadTrack.id
            try {
                val preloadItem = SpotifyPlaybackResolver.resolveToMetadata(preloadTrack)
                playerConnection?.playQueue(
                    SpotifyLikedSongsQueue(
                        title = context.getString(R.string.spotify_liked_songs),
                        allTracks = queueTracks,
                        startIndex = boundedStartIndex,
                        preloadItem = preloadItem,
                        totalCount = total.takeIf { it > 0 },
                        hasCustomOrder = isSearching,
                    ),
                )
            } finally {
                resolvingTrackId = null
            }
        }
    }

    if (showSpotifyLogin) {
        SpotifyLoginSheet(
            onDismiss = { showSpotifyLogin = false },
            onCookiesCaptured = { spDc, spKey ->
                spotifyAccountViewModel.connectWithCookies(spDc = spDc, spKey = spKey)
                showSpotifyLogin = false
                viewModel.refresh()
            },
        )
    }

    ExpressivePullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Transparent),
    ) {
        if (!disableBlur && gradientColors.isNotEmpty() && gradientAlpha > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxSize(0.55f)
                        .align(Alignment.TopCenter)
                        .zIndex(-1f)
                        .drawBehind {
                            val width = size.width
                            val height = size.height

                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        colors =
                                            listOf(
                                                gradientColors[0].copy(alpha = gradientAlpha * 0.7f),
                                                gradientColors[0].copy(alpha = gradientAlpha * 0.35f),
                                                Color.Transparent,
                                            ),
                                        center = Offset(width * 0.5f, height * 0.25f),
                                        radius = width * 0.85f,
                                    ),
                            )
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                Color.Transparent,
                                                Color.Transparent,
                                                surfaceColor.copy(alpha = gradientAlpha * 0.22f),
                                                surfaceColor.copy(alpha = gradientAlpha * 0.55f),
                                                surfaceColor,
                                            ),
                                        startY = height * 0.4f,
                                        endY = height,
                                    ),
                            )
                        },
            )
        }

        if (!spotifyState.isAuthenticated) {
            SpotifyLoginFallback(
                onLoginClick = { showSpotifyLogin = true },
                modifier = Modifier.padding(top = systemBarsTopPadding + AppBarHeight)
            )
        } else {
            LazyColumn(
                state = lazyListState,
                contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!isSearching) {
                    item(key = "header") {
                        val downloads by downloadUtil.downloads.collectAsStateWithLifecycle()
                        var resolvedSongs by remember { mutableStateOf<List<HeaderDownloadItem>?>(null) }
                        var resolvingForDownload by remember { mutableStateOf(false) }
                        var resolvedCount by remember { mutableStateOf(0) }
                        var totalCount by remember { mutableStateOf(0) }
                        var resolveError by remember { mutableStateOf(false) }
                        var showRemoveDownloadDialog by remember { mutableStateOf(false) }

                        val downloadState =
                            resolvedSongs
                                ?.let { items -> headerDownloadState(items.map { it.id }, downloads) }
                                ?: HeaderDownloadState.None

                        LaunchedEffect(resolveError) {
                            if (resolveError) {
                                Toast.makeText(context, R.string.download_resolve_failed, Toast.LENGTH_SHORT).show()
                                resolveError = false
                            }
                        }

                        suspend fun resolveForDownload(
                            onBatchResolved: (List<HeaderDownloadItem>) -> Unit = {},
                        ): List<HeaderDownloadItem> {
                            val semaphore = Semaphore(4)
                            val channel = Channel<HeaderDownloadItem?>(Channel.UNLIMITED)
                            val results = mutableListOf<HeaderDownloadItem>()
                            val sentIds = mutableSetOf<String>()
                            var batch = mutableListOf<HeaderDownloadItem>()
                            totalCount = tracks.size
                            resolvedCount = 0
                            kotlinx.coroutines.coroutineScope {
                                launch {
                                    tracks
                                        .map { track ->
                                            async {
                                                semaphore.withPermit {
                                                    channel.send(
                                                        SpotifyPlaybackResolver
                                                            .resolveToMetadata(track, database)
                                                            ?.let { HeaderDownloadItem(id = it.id, title = it.title) },
                                                    )
                                                }
                                            }
                                        }.awaitAll()
                                    channel.close()
                                }
                                for (item in channel) {
                                    resolvedCount += 1
                                    if (item != null) {
                                        results += item
                                        batch += item
                                    }
                                    if (batch.size >= 8) {
                                        val pending = batch.filter { it.id !in sentIds }
                                        sentIds += pending.map { it.id }
                                        onBatchResolved(pending)
                                        batch = mutableListOf()
                                    }
                                }
                                if (batch.isNotEmpty()) {
                                    val pending = batch.filter { it.id !in sentIds }
                                    sentIds += pending.map { it.id }
                                    onBatchResolved(pending)
                                }
                            }
                            return results.distinctBy { it.id }
                        }

                        LaunchedEffect(spotifyLikedKeepOffline, tracks) {
                            if (spotifyLikedKeepOffline && resolvedSongs == null) {
                                resolvingForDownload = true
                                try {
                                    resolvedSongs = resolveForDownload()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    resolveError = true
                                } finally {
                                    resolvingForDownload = false
                                }
                            }
                        }

                        SpotifyLikedHeaderHero(
                            total = total,
                            tracksCount = tracks.size,
                            loadedDurationMs = loadedDurationMs,
                            hasTracks = tracks.isNotEmpty(),
                            gradientColors = gradientColors,
                            onRefresh = viewModel::refresh,
                            onPlay = { playPlaylist() },
                            onShuffle = { playPlaylist(shuffled = true) },
                            keepOffline = spotifyLikedKeepOffline,
                            resolvingForDownload = resolvingForDownload,
                            downloadState = downloadState,
                            onKeepOfflineChange = { enabled ->
                                if (enabled) {
                                    setSpotifyLikedKeepOffline(true)
                                    coroutineScope.launch(Dispatchers.IO) {
                                        resolvingForDownload = true
                                        try {
                                            val items =
                                                resolveForDownload { batch ->
                                                    sendAddMissingDownloads(
                                                        context = context,
                                                        songs = batch,
                                                        downloads = downloadUtil.downloads.value,
                                                    )
                                                }
                                            resolvedSongs = items
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            resolveError = true
                                        } finally {
                                            resolvingForDownload = false
                                        }
                                    }
                                } else {
                                    showRemoveDownloadDialog = true
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = systemBarsTopPadding + AppBarHeight),
                        )

                        if (resolvingForDownload) {
                            Text(
                                text = stringResource(R.string.download_resolving, resolvedCount, totalCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                            )
                        }

                        LocalPlaylistRemoveDownloadDialog(
                            show = showRemoveDownloadDialog,
                            playlistName = context.getString(R.string.spotify_liked_songs),
                            onDismiss = { showRemoveDownloadDialog = false },
                            onConfirm = {
                                setSpotifyLikedKeepOffline(false)
                                resolvedSongs?.let { items ->
                                    sendRemoveDownloads(context, items.map { it.id })
                                }
                            },
                        )
                    }
                }

                spotifyLikedTrackList(
                    isLoading = isLoading,
                    tracksIsEmpty = tracks.isEmpty(),
                    error = error,
                    filteredTracks = filteredTracks,
                    isQueryBlank = query.text.isBlank(),
                    mediaMetadata = mediaMetadata,
                    resolvingTrackId = resolvingTrackId,
                    isPlaying = isPlaying,
                    onTrackClick = { track, index, trackIsActive ->
                        if (trackIsActive) {
                            playerConnection?.player?.togglePlayPause()
                        } else {
                            val startIndex =
                                tracks
                                    .indexOfFirst { item -> item.id == track.id }
                                    .takeIf { itemIndex -> itemIndex >= 0 }
                                    ?: index
                            playPlaylist(startIndex = startIndex)
                        }
                    },
                )
            }

            DraggableScrollbar(
                modifier =
                    Modifier
                        .padding(
                            LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
                        ).align(Alignment.CenterEnd),
                scrollState = lazyListState,
                headerItems = if (!isSearching) 1 else 0,
            )
        }

        SpotifyLikedTopBar(
            isSearching = isSearching,
            query = query,
            showTopBarTitle = showTopBarTitle,
            focusRequester = focusRequester,
            scrollBehavior = scrollBehavior,
            onQueryChange = { query = it },
            onNavigationClick = {
                if (isSearching) {
                    isSearching = false
                    query = TextFieldValue()
                } else {
                    navController.navigateUp()
                }
            },
            onNavigationLongClick = {
                if (!isSearching) navController.backToMain()
            },
            onSearchClick = { isSearching = true },
        )
    }
}
