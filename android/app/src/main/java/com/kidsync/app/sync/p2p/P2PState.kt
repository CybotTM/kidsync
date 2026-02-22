package com.kidsync.app.sync.p2p

/**
 * States for the P2P sync connection lifecycle.
 */
sealed class P2PState {
    data object Idle : P2PState()
    data object Advertising : P2PState()
    data object Discovering : P2PState()
    data class Connecting(val endpointName: String) : P2PState()
    data class Connected(val endpointName: String) : P2PState()
    data class Syncing(val progress: Float, val endpointName: String) : P2PState()
    data class Completed(val opsReceived: Long, val opsSent: Long) : P2PState()
    data class Error(val message: String) : P2PState()
}
