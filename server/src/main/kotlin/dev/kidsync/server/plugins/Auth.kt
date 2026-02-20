package dev.kidsync.server.plugins

import dev.kidsync.server.AppConfig
import dev.kidsync.server.models.ErrorResponse
import dev.kidsync.server.util.JwtUtil
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureAuth(config: AppConfig, jwtUtil: JwtUtil) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "kidsync"
            verifier(
                com.auth0.jwt.JWT
                    .require(jwtUtil.getAlgorithm())
                    .withIssuer(config.jwtIssuer)
                    .withAudience(config.jwtAudience)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.subject
                val deviceId = credential.payload.getClaim("did").asString()
                val familyIds = credential.payload.getClaim("fam").asList(String::class.java) ?: emptyList()

                if (userId != null && deviceId != null) {
                    UserPrincipal(userId, deviceId, familyIds)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(
                        error = "UNAUTHORIZED",
                        message = "Invalid or expired token",
                    )
                )
            }
        }
    }
}

/**
 * Principal representing an authenticated user.
 */
data class UserPrincipal(
    val userId: String,
    val deviceId: String,
    val familyIds: List<String>,
)

/**
 * Extension to get the authenticated principal from a call.
 */
fun ApplicationCall.userPrincipal(): UserPrincipal =
    principal<UserPrincipal>() ?: throw IllegalStateException("No authenticated user")
