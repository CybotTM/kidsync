package com.kidsync.app.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.kidsync.app.domain.usecase.sync.SyncOpsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic background sync.
 *
 * Runs the full sync pipeline:
 * 1. Pull new ops from server
 * 2. Verify hash chains
 * 3. Decrypt and apply with conflict resolution
 * 4. Push pending local ops
 *
 * Scheduled to run every 15 minutes with network connectivity constraints.
 * Also supports one-shot execution for immediate sync triggers.
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
        const val KEY_FAMILY_ID = "family_id"

        private const val PREF_FAMILY_ID = "family_id"

        /**
         * Enqueue a periodic sync job that runs every 15 minutes.
         */
        fun enqueuePeriodicSync(workManager: WorkManager, familyId: UUID) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_FAMILY_ID, familyId.toString())
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Trigger an immediate one-shot sync.
         */
        fun triggerImmediateSync(workManager: WorkManager, familyId: UUID) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_FAMILY_ID, familyId.toString())
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            workManager.enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /**
         * Cancel all sync work.
         */
        fun cancelAll(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
            workManager.cancelUniqueWork(WORK_NAME_ONESHOT)
        }
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
                        .putInt("pulled", (result as? com.kidsync.app.domain.usecase.sync.SyncResult)?.pulled ?: 0)
                        .putInt("pushed", (result as? com.kidsync.app.domain.usecase.sync.SyncResult)?.pushed ?: 0)
                        .putInt("conflicts", (result as? com.kidsync.app.domain.usecase.sync.SyncResult)?.conflictsResolved ?: 0)
                        .build()
                    Result.success(outputData)
                } else {
                    if (runAttemptCount < 3) {
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
