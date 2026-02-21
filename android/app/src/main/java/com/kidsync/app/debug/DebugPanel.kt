package com.kidsync.app.debug

import com.kidsync.app.BuildConfig
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import java.time.Instant
import javax.inject.Inject

/**
 * Debug information provider for development and troubleshooting.
 * Collects diagnostic data without exposing sensitive keys or plaintext.
 *
 * SEC3-A-23: All public methods are gated behind BuildConfig.DEBUG to ensure
 * debug functionality is not available in release builds. The methods return
 * empty/stub data when called in release mode as a defense-in-depth measure.
 */
class DebugPanel @Inject constructor(
    private val keyManager: KeyManager,
    private val syncStateDao: SyncStateDao,
    private val opLogDao: OpLogDao
) {

    data class DebugInfo(
        val deviceId: String?,
        val currentEpoch: Int?,
        val availableEpochs: List<Int>,
        val lastSyncTimestamp: Instant?,
        val lastGlobalSequence: Long?,
        val pendingOpsCount: Int,
        val totalOpsCount: Int,
        val serverCheckpointHash: String?,
        val buildInfo: BuildInfo
    )

    data class BuildInfo(
        val versionName: String = "1.0.0",
        val versionCode: Int = 1,
        val protocolVersion: Int = 2,
        val buildType: String = "debug"
    )

    data class SyncDiagnostics(
        val bucketId: String,
        val lastSyncTimestamp: Instant?,
        val lastGlobalSequence: Long?,
        val pendingOpsCount: Int,
        val hashChainIntact: Boolean,
        val serverCheckpointMatch: Boolean?
    )

    suspend fun collectDebugInfo(bucketId: String): DebugInfo {
        // SEC3-A-23: Gate debug functionality behind BuildConfig.DEBUG
        if (!BuildConfig.DEBUG) {
            return DebugInfo(
                deviceId = null,
                currentEpoch = null,
                availableEpochs = emptyList(),
                lastSyncTimestamp = null,
                lastGlobalSequence = null,
                pendingOpsCount = 0,
                totalOpsCount = 0,
                serverCheckpointHash = null,
                buildInfo = BuildInfo(buildType = "release")
            )
        }

        val deviceId = try {
            keyManager.getDeviceId()
        } catch (_: Exception) {
            null
        }

        val currentEpoch = try {
            keyManager.getCurrentEpoch(bucketId)
        } catch (_: Exception) {
            null
        }

        val availableEpochs = try {
            keyManager.getAvailableEpochs(bucketId)
        } catch (_: Exception) {
            emptyList()
        }

        val syncState = syncStateDao.getSyncState(bucketId)

        val pendingOpsCount = try {
            opLogDao.getPendingOpsCount(bucketId)
        } catch (_: Exception) {
            0
        }

        val totalOps = try {
            opLogDao.getAllOpsForBucket(bucketId).size
        } catch (_: Exception) {
            0
        }

        return DebugInfo(
            deviceId = deviceId,
            currentEpoch = currentEpoch,
            availableEpochs = availableEpochs,
            lastSyncTimestamp = syncState?.lastSyncTimestamp?.let {
                try { Instant.parse(it) } catch (_: Exception) { null }
            },
            lastGlobalSequence = syncState?.lastGlobalSequence,
            pendingOpsCount = pendingOpsCount,
            totalOpsCount = totalOps,
            serverCheckpointHash = syncState?.serverCheckpointHash,
            buildInfo = BuildInfo()
        )
    }

    suspend fun collectSyncDiagnostics(bucketId: String): SyncDiagnostics {
        // SEC3-A-23: Gate debug functionality behind BuildConfig.DEBUG
        if (!BuildConfig.DEBUG) {
            return SyncDiagnostics(
                bucketId = bucketId,
                lastSyncTimestamp = null,
                lastGlobalSequence = null,
                pendingOpsCount = 0,
                hashChainIntact = true,
                serverCheckpointMatch = null
            )
        }

        val syncState = syncStateDao.getSyncState(bucketId)
        val pendingCount = opLogDao.getPendingOpsCount(bucketId)

        return SyncDiagnostics(
            bucketId = bucketId,
            lastSyncTimestamp = syncState?.lastSyncTimestamp?.let {
                try { Instant.parse(it) } catch (_: Exception) { null }
            },
            lastGlobalSequence = syncState?.lastGlobalSequence,
            pendingOpsCount = pendingCount,
            hashChainIntact = true,
            serverCheckpointMatch = null
        )
    }

    fun formatDebugInfo(info: DebugInfo): String {
        // SEC3-A-23: Gate debug formatting behind BuildConfig.DEBUG
        if (!BuildConfig.DEBUG) return "Debug info not available in release builds"

        return buildString {
            appendLine("=== KidSync Debug Info ===")
            appendLine("Device ID: ${info.deviceId ?: "NOT SET"}")
            appendLine("Current Epoch: ${info.currentEpoch ?: "N/A"}")
            appendLine("Available Epochs: ${info.availableEpochs.joinToString(", ").ifEmpty { "none" }}")
            appendLine("Last Sync: ${info.lastSyncTimestamp ?: "never"}")
            appendLine("Last Global Seq: ${info.lastGlobalSequence ?: 0}")
            appendLine("Pending Ops: ${info.pendingOpsCount}")
            appendLine("Total Ops: ${info.totalOpsCount}")
            appendLine("Checkpoint Hash: ${info.serverCheckpointHash?.take(16) ?: "none"}...")
            appendLine("Version: ${info.buildInfo.versionName} (${info.buildInfo.versionCode})")
            appendLine("Protocol: v${info.buildInfo.protocolVersion}")
            appendLine("Build: ${info.buildInfo.buildType}")
            appendLine("=========================")
        }
    }
}
