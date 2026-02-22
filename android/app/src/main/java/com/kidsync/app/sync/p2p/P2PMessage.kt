package com.kidsync.app.sync.p2p

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol messages for P2P sync via Nearby Connections.
 *
 * Flow:
 * 1. Handshake: both devices exchange bucketId + HMAC proof of DEK ownership
 * 2. OpsPayload: devices exchange encrypted OpLog entries the peer is missing
 * 3. SyncComplete: summary of what was transferred
 * 4. Error: abort with reason
 */
@Serializable
sealed class P2PMessage {

    @Serializable
    @SerialName("handshake")
    data class Handshake(
        val deviceId: String,
        val bucketId: String,
        val hmac: String,
        val lastSequence: Long,
        val timestamp: Long = 0L
    ) : P2PMessage()

    @Serializable
    @SerialName("ops_payload")
    data class OpsPayload(
        val ops: List<P2POp>
    ) : P2PMessage()

    @Serializable
    @SerialName("sync_complete")
    data class SyncComplete(
        val opsReceived: Long,
        val opsSent: Long,
        val bucketId: String = "",
        val hmac: String = ""
    ) : P2PMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String
    ) : P2PMessage()
}

@Serializable
data class P2POp(
    val globalSequence: Long,
    val bucketId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val serverTimestamp: String? = null
)
