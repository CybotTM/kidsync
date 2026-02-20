package dev.kidsync.server.routes

import dev.kidsync.server.models.RegisterPushRequest
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.PushService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.pushRoutes(pushService: PushService) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/push") {
                post("/register") {
                    val principal = call.userPrincipal()
                    val request = call.receive<RegisterPushRequest>()

                    val result = pushService.registerToken(
                        deviceId = principal.deviceId,
                        token = request.token,
                        platform = request.platform,
                    )
                    result.fold(
                        onSuccess = { call.respond(HttpStatusCode.NoContent) },
                        onFailure = { throw it },
                    )
                }
            }
        }
    }
}
