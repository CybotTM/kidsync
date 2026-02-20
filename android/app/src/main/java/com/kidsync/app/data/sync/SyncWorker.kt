package com.kidsync.app.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.kidsync.app.domain.usecase.sync.SyncOpsUseCase
import com.kidsync.app.domain.usecase.sync.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * WorkManager worker for background sync.
 *
 * Runs the full sync pipeline:
 * 1. Pull new ops from server
 * 2. Verify hash chains
 * 3. Decrypt and apply with conflict resolution
 * 4. Push pending local ops
 *
 * Scheduling and constraints are managed by [SyncScheduler].
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncOpsUseCase: SyncOpsUseCase,
    private val prefs: SharedPreferences
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_PERIODIC = "kidsync_periodic_sync"
        const val WORK_NAME_ONESHOT = "kidsync_oneshot_sync"
        const val TAG_SYNC = "kidsync_sync"
        const val TAG_PERIODIC = "kidsync_periodic"
        const val TAG_IMMEDIATE = "kidsync_immediate"
        const val KEY_FAMILY_ID = "family_id"

        internal const val PREF_FAMILY_ID = "family_id"
        internal const val MAX_RETRY_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val familyIdStr = inputData.getString(KEY_FAMILY_ID)
            ?: prefs.getString(PREF_FAMILY_ID, null)
            ?: return Result.failure(
                Data.Builder()
                    .putString("error", "No family ID available")
                    .build()
            )

        val familyId = try {
            UUID.fromString(familyIdStr)
        } catch (_: IllegalArgumentException) {
            return Result.failure(
                Data.Builder()
                    .putString("error", "Invalid family ID: $familyIdStr")
                    .build()
            )
        }

        return when (val syncResult = syncOpsUseCase(familyId)) {
            is kotlin.Result<*> -> {
                if (syncResult.isSuccess) {
                    val result = syncResult.getOrNull()
                    val outputData = Data.Builder()
                        .putInt("pulled", (result as? SyncResult)?.pulled ?: 0)
                        .putInt("pushed", (result as? SyncResult)?.pushed ?: 0)
                        .putInt("conflicts", (result as? SyncResult)?.conflictsResolved ?: 0)
                        .build()
                    Result.success(outputData)
                } else {
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure(
                            Data.Builder()
                                .putString("error", syncResult.exceptionOrNull()?.message ?: "Unknown sync error")
                                .build()
                        )
                    }
                }
            }
        }
    }
}
