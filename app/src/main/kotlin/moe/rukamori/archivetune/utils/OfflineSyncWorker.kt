/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

class OfflineSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        runCatching {
            val syncUtils =
                EntryPointAccessors.fromApplication(applicationContext, OfflineSyncEntryPoint::class.java).syncUtils()
            syncUtils.trySpotifyAutoSync(authoritative = false)
            Result.success()
        }.getOrElse { e ->
            Timber.e(e, "OfflineSyncWorker failed to trigger Spotify auto-sync")
            Result.retry()
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OfflineSyncEntryPoint {
    fun syncUtils(): SyncUtils
}
