package dev.kidsync.server

import dev.kidsync.server.util.SlidingWindowRateLimiter
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the shared SlidingWindowRateLimiter utility.
 */
class SlidingWindowRateLimiterTest {

    @Test
    fun `allows up to maxRequests then denies`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 3, windowMs = 60_000L)

        assertTrue(limiter.checkAndIncrement("key1"), "Request 1 should be allowed")
        assertTrue(limiter.checkAndIncrement("key1"), "Request 2 should be allowed")
        assertTrue(limiter.checkAndIncrement("key1"), "Request 3 should be allowed")
        assertFalse(limiter.checkAndIncrement("key1"), "Request 4 should be denied")
        assertFalse(limiter.checkAndIncrement("key1"), "Request 5 should also be denied")
    }

    @Test
    fun `maxRequests of 1 allows exactly one request`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 1, windowMs = 60_000L)

        assertTrue(limiter.checkAndIncrement("key1"), "First request should be allowed")
        assertFalse(limiter.checkAndIncrement("key1"), "Second request should be denied")
    }

    @Test
    fun `independent keys are tracked separately`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 60_000L)

        // Fill key A
        assertTrue(limiter.checkAndIncrement("keyA"))
        assertTrue(limiter.checkAndIncrement("keyA"))
        assertFalse(limiter.checkAndIncrement("keyA"), "keyA should be rate-limited")

        // Key B should be unaffected
        assertTrue(limiter.checkAndIncrement("keyB"), "keyB should be independent")
        assertTrue(limiter.checkAndIncrement("keyB"), "keyB second request should be allowed")
        assertFalse(limiter.checkAndIncrement("keyB"), "keyB third request should be denied")
    }

    @Test
    fun `reset clears all state`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 60_000L)

        assertTrue(limiter.checkAndIncrement("key1"))
        assertTrue(limiter.checkAndIncrement("key1"))
        assertFalse(limiter.checkAndIncrement("key1"), "Should be rate-limited")

        limiter.reset()

        assertTrue(limiter.checkAndIncrement("key1"), "Should be allowed after reset")
        assertTrue(limiter.checkAndIncrement("key1"), "Second request after reset should be allowed")
    }

    @Test
    fun `different limiter instances are independent`() {
        val limiterA = SlidingWindowRateLimiter(maxRequests = 1, windowMs = 60_000L)
        val limiterB = SlidingWindowRateLimiter(maxRequests = 1, windowMs = 60_000L)

        assertTrue(limiterA.checkAndIncrement("same-key"))
        assertFalse(limiterA.checkAndIncrement("same-key"), "limiterA should be limited")

        // limiterB should be completely independent
        assertTrue(limiterB.checkAndIncrement("same-key"), "limiterB should not be affected by limiterA")
    }

    @Test
    fun `large maxRequests works correctly at boundary`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 100, windowMs = 60_000L)

        for (i in 1..100) {
            assertTrue(limiter.checkAndIncrement("key"), "Request $i should be allowed")
        }
        assertFalse(limiter.checkAndIncrement("key"), "Request 101 should be denied")
    }

    @Test
    fun `cleanup removes stale entries without affecting active ones`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 60_000L)

        // Add some entries
        limiter.checkAndIncrement("active-key")
        limiter.checkAndIncrement("active-key")

        // Explicit cleanup should not affect entries within the window
        limiter.cleanup()

        // The key should still be at its current count (no reset from cleanup)
        assertFalse(limiter.checkAndIncrement("active-key"), "active-key should still be limited after cleanup")
    }

    @Test
    fun `concurrent access does not corrupt state`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 100, windowMs = 60_000L)
        val threads = (1..10).map {
            Thread {
                for (i in 1..10) {
                    limiter.checkAndIncrement("shared-key")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // After 100 requests (10 threads x 10 each), the next should be denied
        assertFalse(limiter.checkAndIncrement("shared-key"), "Should be rate-limited after 100 concurrent requests")
    }
}
