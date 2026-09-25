/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HeaderDownloadProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val boundedProgress =
        remember(progress) {
            progress.coerceIn(0f, 1f)
        }
    val animatedProgress by animateFloatAsState(
        targetValue = boundedProgress,
        animationSpec = tween(durationMillis = 300),
        label = "headerDownloadProgress",
    )
    val color = MaterialTheme.colorScheme.onSurface
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (boundedProgress <= 0f) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = color,
                trackColor = trackColor,
            )
        } else {
            CircularWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(32.dp),
                color = color,
                trackColor = trackColor,
            )
        }
    }
}
