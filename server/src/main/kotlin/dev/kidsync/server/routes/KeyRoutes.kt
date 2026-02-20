package dev.kidsync.server.routes

import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.KeyService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.keyRoutes(keyService: KeyService) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/keys") {
                // POST /keys/wrapped
                post("/wrapped") {
                    val principal = call.userPrincipal()
                    val request = call.receive<UploadWrappedKeyRequest>()

                    keyService.uploadWrappedKey(principal.userId, request)
                    call.respond(HttpStatusCode.Created)
                }

                // GET /keys/wrapped/{deviceId}
                get("/wrapped/{deviceId}") {
                    val principal = call.userPrincipal()
                    val deviceId = call.parameters["deviceId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing deviceId")
                    val keyEpoch = call.request.queryParameters["keyEpoch"]?.toIntOrNull()

                    val response = keyService.getWrappedKey(principal.userId, deviceId, keyEpoch)
                    call.respond(HttpStatusCode.OK, response)
                }

                // POST /keys/recovery
                post("/recovery") {
                    val principal = call.userPrincipal()
                    val request = call.receive<UploadRecoveryBlobRequest>()

                    keyService.uploadRecoveryBlob(principal.userId, request)
                    call.respond(HttpStatusCode.Created)
                }

                // GET /keys/recovery
                get("/recovery") {
                    val principal = call.userPrincipal()

                    val response = keyService.getRecoveryBlob(principal.userId)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }
}
