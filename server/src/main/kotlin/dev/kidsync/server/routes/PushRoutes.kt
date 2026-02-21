package dev.kidsync.server.routes

import dev.kidsync.server.models.PushTokenRequest
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.PushService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.pushRoutes(pushService: PushService) {
    authenticate("auth-session") {
        rateLimit(RateLimitName("general")) {
            route("/push") {
                /**
                 * POST /push/token
                 * Register a push notification token for the authenticated device.
                 */
                post("/token") {
                    val principal = call.devicePrincipal()
                    val request = call.receive<PushTokenRequest>()

                    pushService.registerToken(
                        deviceId = principal.deviceId,
                        token = request.token,
                        platform = request.platform,
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
