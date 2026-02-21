package com.kidsync.app.data.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WorkManager scheduling for background sync operations.
 *
 * Provides three operations:
 * - [schedulePeriodicSync]: Enqueues a periodic 15-minute sync with battery and network constraints.
 * - [requestImmediateSync]: Enqueues a one-time expedited sync for user-triggered actions.
 * - [cancelSync]: Cancels all pending sync work (both periodic and one-shot).
 *
 * Battery-efficient by design:
 * - Periodic sync requires network connectivity and non-low battery.
 * - Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid duplicate periodic workers.
 * - Exponential backoff starting at 30 seconds on transient failures.
 * - Immediate sync only requires network (no battery constraint) to honor user intent.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager
) {

    /**
     * Schedule a periodic background sync that runs approximately every 15 minutes.
     *
     * Constraints:
     * - Requires network connectivity (will not run offline).
     * - Requires battery not low (skips sync when battery is critically low).
     *
     * Uses [ExistingPeriodicWorkPolicy.KEEP] so calling this multiple times
     * does not reset the existing schedule or create duplicate workers.
     *
     * @param bucketId The bucket to sync data for.
     */
    fun schedulePeriodicSync(bucketId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val inputData = Data.Builder()
            .putString(SyncWorker.KEY_BUCKET_ID, bucketId)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .addTag(SyncWorker.TAG_SYNC)
            .addTag(SyncWorker.TAG_PERIODIC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Request an immediate one-shot sync, typically triggered by user action.
     *
     * This uses [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] so the sync
     * will still run even if the expedited quota is exhausted, just not as expedited work.
     *
     * Only requires network connectivity (no battery constraint) since the user
     * explicitly requested this sync.
     *
     * @param bucketId The bucket to sync data for.
     */
    fun requestImmediateSync(bucketId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(SyncWorker.KEY_BUCKET_ID, bucketId)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(SyncWorker.TAG_SYNC)
            .addTag(SyncWorker.TAG_IMMEDIATE)
            .build()

        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Cancel all sync work -- both periodic and one-shot.
     *
     * Call this when the user clears the session or the bucket context changes.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_ONESHOT)
    }
}
