package com.kidsync.app.sync.webdav

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Named

/**
 * WorkManager worker for periodic WebDAV sync.
 *
 * Performs the sync cycle:
 * 1. Pull new ops from the WebDAV server
 * 2. Push local pending ops to the WebDAV server
 * 3. Update sync state and checkpoint
 *
 * Scheduled via [WorkManager] with configurable intervals (15min to 4hr).
 * Requires network connectivity and respects battery optimization.
 */
@HiltWorker
class WebDavSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val webDavSyncManager: WebDavSyncManager,
    private val json: Json,
    @Named("encrypted_prefs") private val encryptedPrefs: SharedPreferences
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WebDavSyncWorker"
        const val UNIQUE_WORK_NAME = "webdav_sync_periodic"
        const val ONE_TIME_WORK_NAME = "webdav_sync_now"

        // SharedPreferences keys for WebDAV config
        const val PREF_WEBDAV_ENABLED = "webdav_enabled"
        const val PREF_WEBDAV_SERVER_URL = "webdav_server_url"
        const val PREF_WEBDAV_USERNAME = "webdav_username"
        const val PREF_WEBDAV_PASSWORD = "webdav_password"
        const val PREF_WEBDAV_BASE_PATH = "webdav_base_path"
        const val PREF_WEBDAV_SYNC_INTERVAL = "webdav_sync_interval"
        const val PREF_WEBDAV_LAST_SYNC = "webdav_last_sync"
        const val PREF_WEBDAV_BUCKET_ID = "webdav_bucket_id"

        /**
         * Schedule periodic WebDAV sync with the given interval in minutes.
         */
        fun schedule(context: Context, intervalMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WebDavSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }

        /**
         * Cancel the periodic sync.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        /**
         * Trigger an immediate one-time sync.
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<WebDavSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "WebDAV sync starting")

        // Load config from encrypted prefs
        val serverUrl = encryptedPrefs.getString(PREF_WEBDAV_SERVER_URL, null)
        val username = encryptedPrefs.getString(PREF_WEBDAV_USERNAME, null)
        val password = encryptedPrefs.getString(PREF_WEBDAV_PASSWORD, null)
        val basePath = encryptedPrefs.getString(PREF_WEBDAV_BASE_PATH, "kidsync") ?: "kidsync"
        val bucketId = encryptedPrefs.getString(PREF_WEBDAV_BUCKET_ID, null)

        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.w(TAG, "WebDAV config incomplete, skipping sync")
            return Result.failure()
        }

        if (bucketId.isNullOrBlank()) {
            Log.w(TAG, "No bucket ID configured for WebDAV sync, skipping")
            return Result.failure()
        }

        val config = WebDavConfig(
            serverUrl = serverUrl,
            username = username,
            password = password,
            basePath = basePath
        )

        webDavSyncManager.configure(config)

        return try {
            // Ensure directory structure exists
            webDavSyncManager.ensureDirectoryStructure(bucketId)
                .getOrElse { e ->
                    Log.e(TAG, "Failed to ensure directory structure", e)
                    return Result.retry()
                }

            // Pull new ops from server
            val lastCheckpoint = webDavSyncManager.downloadCheckpoint(bucketId)
            val afterSequence = lastCheckpoint?.lastSequence ?: 0L

            val pullResult = webDavSyncManager.pullOps(bucketId, afterSequence)
            pullResult.getOrElse { e ->
                Log.e(TAG, "Failed to pull ops", e)
                return Result.retry()
            }

            val pulledOps = pullResult.getOrThrow()
            Log.d(TAG, "Pulled ${pulledOps.size} new ops from WebDAV")

            // Push local pending ops
            val pendingEntities = webDavSyncManager.getPendingOpsForPush(bucketId)
            if (pendingEntities.isNotEmpty()) {
                val pushResult = webDavSyncManager.pushOps(bucketId, pendingEntities)
                pushResult.getOrElse { e ->
                    Log.e(TAG, "Failed to push ops", e)
                    return Result.retry()
                }
                Log.d(TAG, "Pushed ${pendingEntities.size} ops to WebDAV")
            }

            // Update checkpoint
            val newLamport = webDavSyncManager.readLamportTimestamp(bucketId)
            val checkpoint = WebDavCheckpoint(
                lastSequence = newLamport,
                lamportTimestamp = newLamport,
                updatedAt = Instant.now().toString()
            )
            webDavSyncManager.uploadCheckpoint(bucketId, checkpoint)

            // Record last sync time
            encryptedPrefs.edit()
                .putString(PREF_WEBDAV_LAST_SYNC, Instant.now().toString())
                .apply()

            Log.d(TAG, "WebDAV sync completed successfully")
            Result.success()
        } catch (e: WebDavAuthException) {
            Log.e(TAG, "WebDAV auth failed", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV sync failed", e)
            Result.retry()
        }
    }
}
