package dev.kidsync.server.plugins

import dev.kidsync.server.services.ApiException
import dev.kidsync.server.util.SessionUtil
import io.ktor.server.application.*
import io.ktor.server.auth.*

/**
 * Principal representing an authenticated device via session token.
 */
data class DevicePrincipal(
    val deviceId: String,
    val signingKey: String,
) : Principal

fun Application.configureAuth(sessionUtil: SessionUtil) {
    install(Authentication) {
        bearer("auth-session") {
            authenticate { tokenCredential ->
                val session = sessionUtil.validateSession(tokenCredential.token)
                    ?: return@authenticate null
                DevicePrincipal(
                    deviceId = session.deviceId,
                    signingKey = session.signingKey,
                )
            }
        }
    }
}

/**
 * Extension to get the authenticated device principal from a call.
 */
// SEC6-S-12: Return proper 401 ApiException instead of 500 IllegalStateException
fun ApplicationCall.devicePrincipal(): DevicePrincipal =
    principal<DevicePrincipal>() ?: throw ApiException(401, "UNAUTHORIZED", "Authentication required")
