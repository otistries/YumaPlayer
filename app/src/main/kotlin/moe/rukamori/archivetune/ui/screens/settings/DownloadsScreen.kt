/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.formatFileSize
import moe.rukamori.archivetune.utils.PlaylistOfflineStatus
import moe.rukamori.archivetune.viewmodels.DownloadQueueFilter
import moe.rukamori.archivetune.viewmodels.DownloadQueueItem
import moe.rukamori.archivetune.viewmodels.DownloadsViewModel

@Composable
fun DownloadsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val playlistStatuses by viewModel.playlistStatuses.collectAsStateWithLifecycle(initialValue = emptyList())

    val failedCount = items.count { it.isFailed }
    val hasActive = items.any { it.isActive }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        TopAppBar(
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = {
                Text(
                    text = stringResource(R.string.download_queue),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = { navController.backToMain() },
                ) {
                    Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            actions = {
                if (failedCount > 0) {
                    IconButton(
                        onClick = viewModel::retryAllFailed,
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.replay),
                            contentDescription = stringResource(R.string.download_retry_all_failed),
                        )
                    }
                }
                if (hasActive) {
                    IconButton(
                        onClick = viewModel::pauseAll,
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.pause),
                            contentDescription = stringResource(R.string.download_pause_all),
                        )
                    }
                } else if (items.any { !it.isCompleted }) {
                    IconButton(
                        onClick = viewModel::resumeAll,
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = stringResource(R.string.download_resume_all),
                        )
                    }
                }
            },
        )

        if (playlistStatuses.isNotEmpty()) {
            Text(
                text = stringResource(R.string.offline_playlists_section),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            playlistStatuses.forEach { status ->
                PlaylistOfflineRow(
                    status = status,
                    onClick = { navController.navigate("local_playlist/${status.playlistId}") },
                    onLongClick = { status.spotifyId?.let { viewModel.syncPlaylist(it) } },
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == DownloadQueueFilter.ALL,
                onClick = { viewModel.filter.value = DownloadQueueFilter.ALL },
                label = { Text(stringResource(R.string.filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            FilterChip(
                selected = filter == DownloadQueueFilter.ACTIVE,
                onClick = { viewModel.filter.value = DownloadQueueFilter.ACTIVE },
                label = { Text(stringResource(R.string.downloading), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            FilterChip(
                selected = filter == DownloadQueueFilter.COMPLETED,
                onClick = { viewModel.filter.value = DownloadQueueFilter.COMPLETED },
                label = {
                    Text(stringResource(R.string.download_state_completed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
            FilterChip(
                selected = filter == DownloadQueueFilter.FAILED,
                onClick = { viewModel.filter.value = DownloadQueueFilter.FAILED },
                label = { Text(stringResource(R.string.download_state_failed), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }

        if (items.isEmpty()) {
            EmptyPlaceholder(
                icon = R.drawable.download,
                text = stringResource(R.string.download_queue_empty),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.songId }) { item ->
                    DownloadQueueRow(
                        item = item,
                        onRetry = { viewModel.retry(item) },
                        onRemove = { viewModel.remove(item) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueRow(
    item: DownloadQueueItem,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item.thumbnailUrl != null) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = downloadStateLabel(item),
                style = MaterialTheme.typography.bodySmall,
                color =
                    when {
                        item.isFailed -> MaterialTheme.colorScheme.error
                        item.isCompleted -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.state == Download.STATE_DOWNLOADING && item.percentDownloaded >= 0f) {
                LinearProgressIndicator(
                    progress = { item.percentDownloaded / 100f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
        }

        if (item.isFailed) {
            IconButton(
                onClick = onRetry,
                onLongClick = {},
            ) {
                Icon(
                    painter = painterResource(R.drawable.replay),
                    contentDescription = stringResource(R.string.retry),
                )
            }
        }
        IconButton(
            onClick = onRemove,
            onLongClick = {},
        ) {
            Icon(
                painter = painterResource(R.drawable.delete),
                contentDescription = stringResource(R.string.remove_download),
            )
        }
    }
}

@Composable
private fun PlaylistOfflineRow(
    status: PlaylistOfflineStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = status.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val baseText =
                when {
                    status.isFullyOffline -> stringResource(R.string.offline_playlist_fully_offline)
                    status.failed > 0 ->
                        stringResource(R.string.offline_playlist_failures, status.failed)
                    else ->
                        stringResource(
                            R.string.offline_playlist_progress,
                            status.downloaded,
                            status.total,
                        )
                }
            Text(
                text =
                    if (status.bytesDownloaded > 0) {
                        "$baseText · ${formatFileSize(status.bytesDownloaded)}"
                    } else {
                        baseText
                    },
                style = MaterialTheme.typography.bodySmall,
                color =
                    when {
                        status.isFullyOffline -> MaterialTheme.colorScheme.primary
                        status.failed > 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!status.isFullyOffline && status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.completeness },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
        }
        Icon(
            painter =
                painterResource(
                    when {
                        status.isFullyOffline -> R.drawable.check
                        status.failed > 0 -> R.drawable.error
                        else -> R.drawable.download
                    },
                ),
            contentDescription = null,
            tint =
                when {
                    status.isFullyOffline -> MaterialTheme.colorScheme.primary
                    status.failed > 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun downloadStateLabel(item: DownloadQueueItem): String =
    when {
        item.isCompleted -> stringResource(R.string.download_state_completed)
        item.isFailed -> stringResource(R.string.download_state_failed)
        item.state == Download.STATE_DOWNLOADING ->
            stringResource(
                R.string.download_progress_percent,
                item.percentDownloaded.toInt().coerceIn(0, 100),
            )
        item.state == Download.STATE_STOPPED && item.stopReason != 0 -> stringResource(R.string.download_state_paused)
        else -> stringResource(R.string.download_state_queued)
    }
