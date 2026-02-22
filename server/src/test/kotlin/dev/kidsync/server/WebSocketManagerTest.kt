package dev.kidsync.server

import dev.kidsync.server.services.WebSocketManager
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for WebSocketManager connection management, limits, and cleanup.
 * Uses a minimal WebSocketSession stub to avoid full server setup.
 */
class WebSocketManagerTest {

    /**
     * Minimal WebSocketSession stub for testing connection tracking.
     * The actual send/receive are not exercised in these unit tests --
     * only addConnection/removeConnection/disconnectDevice are tested.
     */
    private class StubWebSocketSession : WebSocketSession {
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override val incoming: ReceiveChannel<Frame> = Channel(Channel.UNLIMITED)
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override suspend fun flush() {}
        @Deprecated("Use cancel instead", ReplaceWith("cancel()"))
        override fun terminate() {}
    }

    private fun newManager() = WebSocketManager()

    private fun conn(deviceId: String, bucketId: String) =
        WebSocketManager.WsConnection(StubWebSocketSession(), deviceId, bucketId)

    // ================================================================
    // Basic add/remove
    // ================================================================

    @Test
    fun `addConnection returns true for first connection`() {
        val mgr = newManager()
        val c = conn("d1", "b1")
        assertTrue(mgr.addConnection("b1", c))
    }

    @Test
    fun `removeConnection after addConnection decrements global count`() {
        val mgr = newManager()
        val c = conn("d1", "b1")
        mgr.addConnection("b1", c)
        mgr.removeConnection("b1", c)
        // We can add another to confirm the slot freed up; no exception
        val c2 = conn("d1", "b1")
        assertTrue(mgr.addConnection("b1", c2))
    }

    // ================================================================
    // Per-device connection limit
    // ================================================================

    @Test
    fun `per-device limit allows exactly MAX_CONNECTIONS_PER_DEVICE connections`() {
        val mgr = newManager()
        // MAX_CONNECTIONS_PER_DEVICE = 2
        val c1 = conn("d1", "b1")
        val c2 = conn("d1", "b1")
        assertTrue(mgr.addConnection("b1", c1))
        assertTrue(mgr.addConnection("b1", c2))
    }

    @Test
    fun `per-device limit rejects third connection from same device`() {
        val mgr = newManager()
        val c1 = conn("d1", "b1")
        val c2 = conn("d1", "b1")
        val c3 = conn("d1", "b1")
        mgr.addConnection("b1", c1)
        mgr.addConnection("b1", c2)
        assertFalse(mgr.addConnection("b1", c3))
    }

    @Test
    fun `different devices can each have MAX_CONNECTIONS_PER_DEVICE connections`() {
        val mgr = newManager()
        assertTrue(mgr.addConnection("b1", conn("d1", "b1")))
        assertTrue(mgr.addConnection("b1", conn("d1", "b1")))
        assertTrue(mgr.addConnection("b1", conn("d2", "b1")))
        assertTrue(mgr.addConnection("b1", conn("d2", "b1")))
    }

    // ================================================================
    // Per-bucket connection limit
    // ================================================================

    @Test
    fun `per-bucket limit rejects connection when bucket has MAX_CONNECTIONS_PER_BUCKET`() {
        val mgr = newManager()
        // MAX_CONNECTIONS_PER_BUCKET = 50, MAX_CONNECTIONS_PER_DEVICE = 2
        // Add 25 devices x 2 connections = 50 total for bucket b1
        for (i in 1..25) {
            mgr.addConnection("b1", conn("device-$i", "b1"))
            mgr.addConnection("b1", conn("device-$i", "b1"))
        }
        // 51st connection should be rejected
        assertFalse(mgr.addConnection("b1", conn("overflow-device", "b1")))
    }

    // ================================================================
    // Global connection limit
    // ================================================================

    @Test
    fun `global limit does not restrict connections below the limit`() {
        val mgr = newManager()
        // MAX_GLOBAL_CONNECTIONS = 10_000
        // Add 50 connections to bucket b1 (max per bucket)
        for (i in 1..25) {
            mgr.addConnection("b1", conn("device-b1-$i", "b1"))
            mgr.addConnection("b1", conn("device-b1-$i", "b1"))
        }
        // Bucket b1 is full (50 connections) but global limit (10000) is not reached
        // Connections to other buckets should still succeed
        assertTrue(mgr.addConnection("b2", conn("device-b2-1", "b2")))
    }

    // ================================================================
    // Remove non-existent connection is safe
    // ================================================================

    @Test
    fun `removing non-existent connection does not crash`() {
        val mgr = newManager()
        val c = conn("d1", "b1")
        // Remove without adding -- should not throw
        mgr.removeConnection("b1", c)
    }

    @Test
    fun `removing from non-existent bucket does not crash`() {
        val mgr = newManager()
        val c = conn("d1", "nonexistent")
        mgr.removeConnection("nonexistent", c)
    }

    // ================================================================
    // After removal, device can reconnect
    // ================================================================

    @Test
    fun `device can reconnect after all connections removed`() {
        val mgr = newManager()
        val c1 = conn("d1", "b1")
        val c2 = conn("d1", "b1")
        mgr.addConnection("b1", c1)
        mgr.addConnection("b1", c2)
        // At limit now
        assertFalse(mgr.addConnection("b1", conn("d1", "b1")))
        // Remove one
        mgr.removeConnection("b1", c1)
        // Should be able to add again
        assertTrue(mgr.addConnection("b1", conn("d1", "b1")))
    }

    // ================================================================
    // Cross-bucket isolation
    // ================================================================

    @Test
    fun `device in bucket A does not count toward device limit in bucket B`() {
        val mgr = newManager()
        mgr.addConnection("b1", conn("d1", "b1"))
        mgr.addConnection("b1", conn("d1", "b1"))
        // d1 is at per-device limit in b1, but should be allowed in b2
        assertTrue(mgr.addConnection("b2", conn("d1", "b2")))
    }

    // ================================================================
    // Constants exposed correctly
    // ================================================================

    @Test
    fun `MAX_CONNECTIONS_PER_DEVICE is 2`() {
        kotlin.test.assertEquals(2, WebSocketManager.MAX_CONNECTIONS_PER_DEVICE)
    }

    @Test
    fun `MAX_CONNECTIONS_PER_BUCKET is 50`() {
        kotlin.test.assertEquals(50, WebSocketManager.MAX_CONNECTIONS_PER_BUCKET)
    }

    @Test
    fun `MAX_GLOBAL_CONNECTIONS is 10000`() {
        kotlin.test.assertEquals(10_000, WebSocketManager.MAX_GLOBAL_CONNECTIONS)
    }

    // ================================================================
    // disconnectDevice
    // ================================================================

    @Test
    fun `disconnectDevice removes all connections for device in bucket`() = runBlocking {
        val mgr = newManager()
        val c1 = conn("d1", "b1")
        val c2 = conn("d1", "b1")
        val c3 = conn("d2", "b1")
        mgr.addConnection("b1", c1)
        mgr.addConnection("b1", c2)
        mgr.addConnection("b1", c3)

        mgr.disconnectDevice("b1", "d1")

        // d1 should be able to add connections again (slots freed)
        assertTrue(mgr.addConnection("b1", conn("d1", "b1")))
    }

    @Test
    fun `disconnectDevice on non-existent bucket is safe`() = runBlocking {
        val mgr = newManager()
        // Should not throw
        mgr.disconnectDevice("nonexistent-bucket", "d1")
    }
}
