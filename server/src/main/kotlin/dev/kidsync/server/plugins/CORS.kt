package dev.kidsync.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCORS() {
    install(CORS) {
        anyHost()
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
