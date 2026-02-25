package dev.kidsync.server.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reusable sliding-window rate limiter.
 *
 * Tracks request counts per key (e.g., IP address, signing key) over a
 * configurable time window. Thread-safe: uses ConcurrentHashMap + synchronized
 * per-key window to prevent TOCTOU races on window reset.
 *
 * @param maxRequests Maximum number of requests allowed per key within the window.
 * @param windowMs Window duration in milliseconds.
 */
class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long,
) {
    private data class Window(
        val count: AtomicInteger = AtomicInteger(0),
        val windowStart: AtomicLong = AtomicLong(0),
    )

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Check whether [key] is within the rate limit and increment its counter.
     *
     * @return `true` if the request is allowed, `false` if rate-limited.
     */
    fun checkAndIncrement(key: String): Boolean {
        cleanup()
        val now = System.currentTimeMillis()
        val window = windows.computeIfAbsent(key) { Window() }

        synchronized(window) {
            val start = window.windowStart.get()
            if (now - start > windowMs) {
                window.windowStart.set(now)
                window.count.set(1)
                return true
            }
            val current = window.count.incrementAndGet()
            return current <= maxRequests
        }
    }

    /**
     * Remove entries where windowStart is older than 2x the window period
     * to prevent unbounded memory growth from accumulated keys.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val threshold = now - (2 * windowMs)
        windows.entries.removeIf { (_, window) ->
            window.windowStart.get() < threshold
        }
    }

    /** Clear all rate limit state. Useful for test isolation. */
    fun reset() {
        windows.clear()
    }
}
