package com.kidsync.app.debug

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.domain.repository.SyncRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Debug information provider for development and troubleshooting.
 * Collects diagnostic data without exposing sensitive keys or plaintext.
 *
 * This is a data-only class (no UI). It provides structured debug info
 * that can be displayed in a debug screen or exported for support.
 */
class DebugPanel @Inject constructor(
    private val keyManager: KeyManager,
    private val syncStateDao: SyncStateDao,
    private val opLogDao: OpLogDao
) {

    data class DebugInfo(
        val deviceId: UUID?,
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
        val protocolVersion: Int = 1,
        val buildType: String = "debug"
    )

    data class SyncDiagnostics(
        val familyId: UUID,
        val lastSyncTimestamp: Instant?,
        val lastGlobalSequence: Long?,
        val pendingOpsCount: Int,
        val hashChainIntact: Boolean,
        val serverCheckpointMatch: Boolean?
    )

    /**
     * Collect comprehensive debug information for a family context.
     */
    suspend fun collectDebugInfo(familyId: UUID): DebugInfo {
        val deviceId = try {
            keyManager.getOrCreateDeviceId()
        } catch (_: Exception) {
            null
        }

        val currentEpoch = try {
            keyManager.getCurrentEpoch(familyId)
        } catch (_: Exception) {
            null
        }

        val availableEpochs = try {
            keyManager.getAvailableEpochs(familyId)
        } catch (_: Exception) {
            emptyList()
        }

        val syncState = syncStateDao.getSyncState(familyId)

        val pendingOpsCount = try {
            opLogDao.getPendingOpsCount(familyId)
        } catch (_: Exception) {
            0
        }

        val totalOps = try {
            opLogDao.getAllOpsForFamily(familyId).size
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

    /**
     * Collect sync-specific diagnostics for troubleshooting sync issues.
     */
    suspend fun collectSyncDiagnostics(familyId: UUID): SyncDiagnostics {
        val syncState = syncStateDao.getSyncState(familyId)
        val pendingCount = opLogDao.getPendingOpsCount(familyId)

        return SyncDiagnostics(
            familyId = familyId,
            lastSyncTimestamp = syncState?.lastSyncTimestamp?.let {
                try { Instant.parse(it) } catch (_: Exception) { null }
            },
            lastGlobalSequence = syncState?.lastGlobalSequence,
            pendingOpsCount = pendingCount,
            hashChainIntact = true, // Would verify via HashChainVerifier
            serverCheckpointMatch = null // Would verify against server checkpoint
        )
    }

    /**
     * Format debug info as a human-readable string for logging or display.
     */
    fun formatDebugInfo(info: DebugInfo): String {
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
