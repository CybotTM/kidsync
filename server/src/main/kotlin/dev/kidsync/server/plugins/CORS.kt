package dev.kidsync.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCORS() {
    val allowedOrigins = System.getenv("KIDSYNC_CORS_ORIGINS")
        ?.split(",")
        ?.map { it.trim() }
        ?: emptyList()

    install(CORS) {
        if (allowedOrigins.isEmpty()) {
            // Development mode: allow all origins
            anyHost()
        } else {
            allowedOrigins.forEach { origin -> allowHost(origin, schemes = listOf("https")) }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Protocol-Version")
        exposeHeader("X-RateLimit-Limit")
        exposeHeader("X-RateLimit-Remaining")
        exposeHeader("X-RateLimit-Reset")
        exposeHeader("X-Blob-SHA256")
    }
}
