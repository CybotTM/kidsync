package dev.kidsync.server.services

import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebSocket connections grouped by bucket.
 */
class WebSocketManager {

    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)

    data class WsConnection(
        val session: WebSocketSession,
        val deviceId: String,
        val bucketId: String,
    )

    // SEC-S-06: Connection limits to prevent resource exhaustion
    companion object {
        const val MAX_CONNECTIONS_PER_DEVICE = 2
        const val MAX_CONNECTIONS_PER_BUCKET = 50
    }

    private val connections = ConcurrentHashMap<String, MutableSet<WsConnection>>()
    private val json = Json { encodeDefaults = true }

    /**
     * Add a connection. Returns false if connection limits are exceeded.
     */
    fun addConnection(bucketId: String, connection: WsConnection): Boolean {
        val bucketConnections = connections.computeIfAbsent(bucketId) { ConcurrentHashMap.newKeySet() }

        // SEC-S-06: Enforce per-device connection limit
        val deviceCount = bucketConnections.count { it.deviceId == connection.deviceId }
        if (deviceCount >= MAX_CONNECTIONS_PER_DEVICE) {
            logger.warn("WebSocket connection limit per device reached: device={} bucket={}", connection.deviceId, bucketId)
            return false
        }

        // SEC-S-06: Enforce per-bucket connection limit
        if (bucketConnections.size >= MAX_CONNECTIONS_PER_BUCKET) {
            logger.warn("WebSocket connection limit per bucket reached: bucket={}", bucketId)
            return false
        }

        bucketConnections.add(connection)
        logger.info("WebSocket connected: device={} bucket={}", connection.deviceId, bucketId)
        return true
    }

    fun removeConnection(bucketId: String, connection: WsConnection) {
        connections[bucketId]?.remove(connection)
        logger.info("WebSocket disconnected: device={} bucket={}", connection.deviceId, bucketId)
    }

    /**
     * Notify all bucket devices (except the source) that new ops are available.
     */
    suspend fun notifyOpsAvailable(bucketId: String, latestSequence: Long, sourceDeviceId: String) {
        val bucketConnections = connections[bucketId] ?: return
        val message = json.encodeToString(
            WsOpsAvailable.serializer(),
            WsOpsAvailable(latestSequence = latestSequence, sourceDeviceId = sourceDeviceId),
        )

        val deadConnections = mutableListOf<WsConnection>()
        for (conn in bucketConnections) {
            if (conn.deviceId != sourceDeviceId) {
                try {
                    conn.session.send(Frame.Text(message))
                } catch (e: Exception) {
                    logger.warn("Failed to send WS message to device={}: {}", conn.deviceId, e.message)
                    deadConnections.add(conn)
                }
            }
        }
        bucketConnections.removeAll(deadConnections.toSet())
    }

    /**
     * Notify bucket about checkpoint availability.
     */
    suspend fun notifyCheckpointAvailable(bucketId: String, startSequence: Long, endSequence: Long) {
        val bucketConnections = connections[bucketId] ?: return
        val message = json.encodeToString(
            WsCheckpointAvailable.serializer(),
            WsCheckpointAvailable(startSequence = startSequence, endSequence = endSequence),
        )

        val deadConnections = mutableListOf<WsConnection>()
        for (conn in bucketConnections) {
            try {
                conn.session.send(Frame.Text(message))
            } catch (e: Exception) {
                logger.warn("Failed to send WS checkpoint to device={}: {}", conn.deviceId, e.message)
                deadConnections.add(conn)
            }
        }
        bucketConnections.removeAll(deadConnections.toSet())
    }

    /**
     * Notify bucket about snapshot availability.
     */
    suspend fun notifySnapshotAvailable(bucketId: String, atSequence: Long, snapshotId: String) {
        val bucketConnections = connections[bucketId] ?: return
        val message = json.encodeToString(
            WsSnapshotAvailable.serializer(),
            WsSnapshotAvailable(atSequence = atSequence, snapshotId = snapshotId),
        )

        val deadConnections = mutableListOf<WsConnection>()
        for (conn in bucketConnections) {
            try {
                conn.session.send(Frame.Text(message))
            } catch (e: Exception) {
                logger.warn("Failed to send WS snapshot to device={}: {}", conn.deviceId, e.message)
                deadConnections.add(conn)
            }
        }
        bucketConnections.removeAll(deadConnections.toSet())
    }

    /**
     * Notify bucket about new device joining.
     */
    suspend fun notifyDeviceJoined(bucketId: String, newDeviceId: String, encryptionKey: String) {
        val bucketConnections = connections[bucketId] ?: return
        val message = json.encodeToString(
            WsDeviceJoined.serializer(),
            WsDeviceJoined(deviceId = newDeviceId, encryptionKey = encryptionKey),
        )

        val deadConnections = mutableListOf<WsConnection>()
        for (conn in bucketConnections) {
            if (conn.deviceId != newDeviceId) {
                try {
                    conn.session.send(Frame.Text(message))
                } catch (e: Exception) {
                    logger.warn("Failed to send WS device-joined to device={}: {}", conn.deviceId, e.message)
                    deadConnections.add(conn)
                }
            }
        }
        bucketConnections.removeAll(deadConnections.toSet())
    }
}

// WebSocket message types

@Serializable
data class WsOpsAvailable(
    val type: String = "ops_available",
    val latestSequence: Long,
    val sourceDeviceId: String,
)

@Serializable
data class WsCheckpointAvailable(
    val type: String = "checkpoint_available",
    val startSequence: Long,
    val endSequence: Long,
)

@Serializable
data class WsSnapshotAvailable(
    val type: String = "snapshot_available",
    val atSequence: Long,
    val snapshotId: String,
)

@Serializable
data class WsDeviceJoined(
    val type: String = "device_joined",
    val deviceId: String,
    val encryptionKey: String,
)

@Serializable
data class WsAuthOk(
    val type: String = "auth_ok",
    val deviceId: String,
    val bucketId: String,
    val latestSequence: Long,
)

@Serializable
data class WsAuthFailed(
    val type: String = "auth_failed",
    val error: String,
    val message: String,
)

@Serializable
data class WsPong(
    val type: String = "pong",
    val ts: String,
)
