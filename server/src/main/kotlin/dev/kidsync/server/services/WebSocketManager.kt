package dev.kidsync.server.services

import dev.kidsync.server.db.BucketAccess
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        // SEC3-S-14: Global connection limit to prevent server-wide resource exhaustion
        const val MAX_GLOBAL_CONNECTIONS = 10_000
    }

    private val connections = ConcurrentHashMap<String, MutableSet<WsConnection>>()
    // SEC3-S-14: Atomic counter for total connections across all buckets
    private val globalConnectionCount = AtomicInteger(0)
    private val json = Json { encodeDefaults = true }

    /**
     * Add a connection. Returns false if connection limits are exceeded.
     *
     * SEC2-S-09: Uses synchronized block to make the count-check-and-add atomic,
     * preventing race conditions where concurrent connections could both pass the
     * limit check before either is added.
     *
     * SEC4-S-02: Uses increment-first pattern for global counter to prevent race
     * condition where concurrent connections could both pass the limit check.
     */
    fun addConnection(bucketId: String, connection: WsConnection): Boolean {
        // SEC4-S-02: Atomically increment first, then check. If over limit, decrement.
        // This prevents a TOCTOU race where two threads both read below-limit and both add.
        val newCount = globalConnectionCount.incrementAndGet()
        if (newCount > MAX_GLOBAL_CONNECTIONS) {
            globalConnectionCount.decrementAndGet()
            logger.warn("WebSocket global connection limit reached: {}", MAX_GLOBAL_CONNECTIONS)
            return false
        }

        val bucketConnections = connections.computeIfAbsent(bucketId) { ConcurrentHashMap.newKeySet() }

        synchronized(bucketConnections) {
            // SEC-S-06: Enforce per-device connection limit
            val deviceCount = bucketConnections.count { it.deviceId == connection.deviceId }
            if (deviceCount >= MAX_CONNECTIONS_PER_DEVICE) {
                globalConnectionCount.decrementAndGet()
                logger.warn("WebSocket connection limit per device reached: device={} bucket={}", connection.deviceId, bucketId)
                return false
            }

            // SEC-S-06: Enforce per-bucket connection limit
            if (bucketConnections.size >= MAX_CONNECTIONS_PER_BUCKET) {
                globalConnectionCount.decrementAndGet()
                logger.warn("WebSocket connection limit per bucket reached: bucket={}", bucketId)
                return false
            }

            bucketConnections.add(connection)
        }
        logger.info("WebSocket connected: device={} bucket={}", connection.deviceId, bucketId)
        return true
    }

    fun removeConnection(bucketId: String, connection: WsConnection) {
        val bucketConnections = connections[bucketId]
        val removed = bucketConnections?.remove(connection) ?: false
        if (removed) {
            globalConnectionCount.decrementAndGet()
        }
        // SEC3-S-14: Clean up empty bucket entries to prevent unbounded map growth
        if (bucketConnections != null && bucketConnections.isEmpty()) {
            connections.remove(bucketId, bucketConnections)
        }
        logger.info("WebSocket disconnected: device={} bucket={}", connection.deviceId, bucketId)
    }

    /**
     * SEC3-S-05: Disconnect all WebSocket connections for a specific device in a specific bucket.
     * Used when a device's access is revoked (self-revoke or bucket deletion).
     */
    suspend fun disconnectDevice(bucketId: String, deviceId: String) {
        val bucketConnections = connections[bucketId] ?: return
        val toDisconnect = bucketConnections.filter { it.deviceId == deviceId }
        for (conn in toDisconnect) {
            try {
                conn.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "Access revoked"))
            } catch (e: Exception) {
                logger.warn("Failed to close WS for revoked device={}: {}", deviceId, e.message)
            }
            if (bucketConnections.remove(conn)) {
                globalConnectionCount.decrementAndGet()
            }
        }
        // SEC3-S-14: Clean up empty bucket entries
        if (bucketConnections.isEmpty()) {
            connections.remove(bucketId, bucketConnections)
        }
    }

    /**
     * SEC4-S-15: Helper to remove dead connections and properly decrement the global counter.
     * Must be called when connections are found to be dead during notification sends.
     */
    private fun removeDeadConnections(bucketConnections: MutableSet<WsConnection>, deadConnections: List<WsConnection>) {
        for (conn in deadConnections) {
            if (bucketConnections.remove(conn)) {
                globalConnectionCount.decrementAndGet()
            }
        }
    }

    /**
     * SEC4-S-09: Check if a connection's device still has active bucket access.
     * Returns false if the device's access has been revoked, and closes the connection.
     */
    private suspend fun validateConnectionAccess(conn: WsConnection): Boolean {
        val hasAccess = try {
            dbQuery {
                BucketAccess.selectAll().where {
                    (BucketAccess.bucketId eq conn.bucketId) and
                        (BucketAccess.deviceId eq conn.deviceId) and
                        BucketAccess.revokedAt.isNull()
                }.firstOrNull() != null
            }
        } catch (_: Exception) {
            // Fail-closed: on DB errors, deny access. A transient DB failure will
            // disconnect the user, but they'll reconnect automatically when the DB recovers.
            // This is safer than fail-open, which would let revoked devices keep connections.
            false
        }

        if (!hasAccess) {
            try {
                conn.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "Access revoked"))
            } catch (_: Exception) {
                // Already closed
            }
        }

        return hasAccess
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
        // SEC4-S-15: Properly decrement global counter when removing dead connections
        removeDeadConnections(bucketConnections, deadConnections)
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
        // SEC4-S-15: Properly decrement global counter when removing dead connections
        removeDeadConnections(bucketConnections, deadConnections)
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
        // SEC4-S-15: Properly decrement global counter when removing dead connections
        removeDeadConnections(bucketConnections, deadConnections)
    }

    /**
     * Notify bucket about new device joining.
     * SEC4-S-09: Re-validates bucket access before sending sensitive notifications
     * (encryptionKey). Connections with revoked access are closed and skipped.
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
                // SEC4-S-09: Re-validate bucket access before sending encryptionKey
                if (!validateConnectionAccess(conn)) {
                    logger.warn("Skipping WS device-joined for revoked device={}", conn.deviceId)
                    deadConnections.add(conn)
                    continue
                }

                try {
                    conn.session.send(Frame.Text(message))
                } catch (e: Exception) {
                    logger.warn("Failed to send WS device-joined to device={}: {}", conn.deviceId, e.message)
                    deadConnections.add(conn)
                }
            }
        }
        // SEC4-S-15: Properly decrement global counter when removing dead connections
        removeDeadConnections(bucketConnections, deadConnections)
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
