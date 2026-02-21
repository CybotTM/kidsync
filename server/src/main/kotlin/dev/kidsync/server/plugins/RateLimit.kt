package dev.kidsync.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimit() {
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            // SEC-S-13: With XForwardedHeaders installed in Application.kt,
            // request.local.remoteAddress reflects the real client IP behind proxy
            requestKey { call ->
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
            rateLimiter(limit = 1, refillPeriod = 60.minutes)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
    }
}
