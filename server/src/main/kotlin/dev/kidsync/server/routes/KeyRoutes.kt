package dev.kidsync.server.routes

import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.KeyService
import dev.kidsync.server.util.ValidationUtil
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.keyRoutes(keyService: KeyService) {
    authenticate("auth-session") {
        rateLimit(RateLimitName("general")) {
            route("/keys") {
                // POST /keys/wrapped
                post("/wrapped") {
                    val principal = call.devicePrincipal()
                    val request = call.receive<WrappedKeyRequest>()

                    if (request.targetDevice.isBlank() || request.wrappedDek.isBlank()) {
                        throw ApiException(400, "INVALID_REQUEST", "targetDevice and wrappedDek are required")
                    }

                    keyService.uploadWrappedKey(principal.deviceId, request)
                    call.respond(HttpStatusCode.Created)
                }

                // GET /keys/wrapped?epoch={n}
                get("/wrapped") {
                    val principal = call.devicePrincipal()
                    val keyEpoch = call.request.queryParameters["epoch"]?.toIntOrNull()

                    val response = keyService.getWrappedKey(principal.deviceId, keyEpoch)
                    call.respond(HttpStatusCode.OK, response)
                }

                // POST /keys/attestations
                post("/attestations") {
                    val principal = call.devicePrincipal()
                    val request = call.receive<KeyAttestationRequest>()

                    if (request.attestedDevice.isBlank() || request.attestedKey.isBlank() || request.signature.isBlank()) {
                        throw ApiException(400, "INVALID_REQUEST", "All fields are required")
                    }

                    keyService.uploadAttestation(principal.deviceId, request)
                    call.respond(HttpStatusCode.Created)
                }

                // GET /keys/attestations/{deviceId}
                get("/attestations/{deviceId}") {
                    val deviceId = ValidationUtil.requireUuidPathParam(call, "deviceId", "device id")

                    val attestations = keyService.getAttestations(deviceId)
                    call.respond(HttpStatusCode.OK, attestations)
                }
            }

            route("/recovery") {
                // POST /recovery
                post {
                    val principal = call.devicePrincipal()
                    val request = call.receive<RecoveryBlobRequest>()

                    if (request.encryptedBlob.isBlank()) {
                        throw ApiException(400, "INVALID_REQUEST", "encryptedBlob is required")
                    }

                    keyService.uploadRecoveryBlob(principal.deviceId, request)
                    call.respond(HttpStatusCode.Created)
                }

                // GET /recovery
                get {
                    val principal = call.devicePrincipal()
                    val response = keyService.getRecoveryBlob(principal.deviceId)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }
}
