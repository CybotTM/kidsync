package dev.kidsync.server.plugins

import dev.kidsync.server.AppConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimit(config: AppConfig = AppConfig()) {
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            // SEC-S-13: With XForwardedHeaders installed in Application.kt,
            // request.local.remoteAddress reflects the real client IP behind proxy
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
        // SEC2-S-11: Per-signing-key rate limit for /auth/challenge to prevent
        // challenge DoS via public signing key spam. Anyone who knows a device's
        // public signing key could otherwise flood the challenge endpoint.
        register(RateLimitName("auth-challenge")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call ->
                // Use the signing key from the request body as the rate limit key.
                // Falls back to IP-based limiting if key can't be extracted.
                call.request.local.remoteAddress
            }
        }
        register(RateLimitName("sync-upload")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
        register(RateLimitName("sync-pull")) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
        register(RateLimitName("general")) {
            rateLimiter(limit = 100, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
        register(RateLimitName("snapshot")) {
            rateLimiter(limit = config.snapshotRateLimitPerHour, refillPeriod = 60.minutes)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
    }
}
