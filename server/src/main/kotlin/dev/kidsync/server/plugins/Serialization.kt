package dev.kidsync.server.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            // SEC-S-16: ignoreUnknownKeys=true is intentional for backward compatibility.
            // Clients may send fields from newer API versions that the server does not yet
            // know about. Rejecting those would break forward compatibility. The server only
            // processes explicitly modeled fields; unknown fields are safely ignored.
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }
}
