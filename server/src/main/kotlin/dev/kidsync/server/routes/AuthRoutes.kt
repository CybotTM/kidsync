package dev.kidsync.server.routes

import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.db.Users
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.AuthService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll

fun Route.authRoutes(authService: AuthService) {
    route("/auth") {
        rateLimit(RateLimitName("auth")) {
            post("/register") {
                val request = call.receive<RegisterRequest>()
                val result = authService.register(request)
                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { throw it },
                )
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
                val result = authService.login(request)
                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { throw it },
                )
            }

            post("/refresh") {
                val request = call.receive<RefreshRequest>()
                val result = authService.refresh(request)
                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { throw it },
                )
            }
        }

        authenticate("auth-jwt") {
            rateLimit(RateLimitName("general")) {
                post("/totp/setup") {
                    val principal = call.userPrincipal()
                    // Look up the user's email
                    val email = dbQuery {
                        Users.selectAll()
                            .where { Users.id eq principal.userId }
                            .firstOrNull()
                            ?.get(Users.email) ?: ""
                    }
                    val result = authService.totpSetup(principal.userId, email)
                    result.fold(
                        onSuccess = { call.respond(HttpStatusCode.OK, it) },
                        onFailure = { throw it },
                    )
                }

                post("/totp/verify") {
                    val principal = call.userPrincipal()
                    val request = call.receive<TotpVerifyRequest>()
                    val result = authService.totpVerify(principal.userId, request.code)
                    result.fold(
                        onSuccess = { call.respond(HttpStatusCode.OK, it) },
                        onFailure = { throw it },
                    )
                }
            }
        }
    }
}
