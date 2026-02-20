package dev.kidsync.server.services

import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebSocket connections grouped by family.
 */
class WebSocketManager {

    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)

    data class WsConnection(
        val session: WebSocketSession,
        val userId: String,
        val deviceId: String,
        val familyId: String,
    )

    private val connections = ConcurrentHashMap<String, MutableSet<WsConnection>>()
    private val json = Json { encodeDefaults = true }

    fun addConnection(familyId: String, connection: WsConnection) {
        connections.getOrPut(familyId) { ConcurrentHashMap.newKeySet() }.add(connection)
        logger.info("WebSocket connected: device={} family={}", connection.deviceId, familyId)
    }

    fun removeConnection(familyId: String, connection: WsConnection) {
        connections[familyId]?.remove(connection)
        logger.info("WebSocket disconnected: device={} family={}", connection.deviceId, familyId)
    }

    /**
     * Notify all family devices (except the source) that new ops are available.
     */
    suspend fun notifyOpsAvailable(familyId: String, latestSequence: Long, sourceDeviceId: String) {
        val familyConnections = connections[familyId] ?: return
        val message = json.encodeToString(
            WsOpsAvailable.serializer(),
            WsOpsAvailable(latestSequence = latestSequence, sourceDeviceId = sourceDeviceId),
        )

        for (conn in familyConnections) {
            if (conn.deviceId != sourceDeviceId) {
                try {
                    conn.session.send(Frame.Text(message))
                } catch (e: Exception) {
                    logger.warn("Failed to send WS message to device={}: {}", conn.deviceId, e.message)
                }
            }
        }
    }

    /**
     * Notify family about checkpoint availability.
     */
    suspend fun notifyCheckpointAvailable(familyId: String, startSequence: Long, endSequence: Long) {
        val familyConnections = connections[familyId] ?: return
        val message = json.encodeToString(
            WsCheckpointAvailable.serializer(),
            WsCheckpointAvailable(startSequence = startSequence, endSequence = endSequence),
        )

        for (conn in familyConnections) {
            try {
                conn.session.send(Frame.Text(message))
            } catch (e: Exception) {
                logger.warn("Failed to send WS checkpoint to device={}: {}", conn.deviceId, e.message)
            }
        }
    }

    /**
     * Notify family about snapshot availability.
     */
    suspend fun notifySnapshotAvailable(familyId: String, atSequence: Long, snapshotId: String) {
        val familyConnections = connections[familyId] ?: return
        val message = json.encodeToString(
            WsSnapshotAvailable.serializer(),
            WsSnapshotAvailable(atSequence = atSequence, snapshotId = snapshotId),
        )

        for (conn in familyConnections) {
            try {
                conn.session.send(Frame.Text(message))
            } catch (e: Exception) {
                logger.warn("Failed to send WS snapshot to device={}: {}", conn.deviceId, e.message)
            }
        }
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
data class WsAuthOk(
    val type: String = "auth_ok",
    val deviceId: String,
    val familyId: String,
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
