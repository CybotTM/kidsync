package dev.kidsync.server.routes

import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.FamilyService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.familyRoutes(familyService: FamilyService) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/families") {
                post {
                    val principal = call.userPrincipal()
                    val request = call.receive<CreateFamilyRequest>()

                    val response = familyService.createFamily(principal.userId, request)
                    call.respond(HttpStatusCode.Created, response)
                }

                route("/{familyId}") {
                    post("/invite") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")

                        val response = familyService.createInvite(principal.userId, familyId)
                        call.respond(HttpStatusCode.Created, response)
                    }

                    post("/join") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")
                        val request = call.receive<JoinFamilyRequest>()

                        val response = familyService.joinFamily(
                            principal.userId,
                            principal.deviceId,
                            familyId,
                            request,
                        )
                        call.respond(HttpStatusCode.OK, response)
                    }

                    post("/convert") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")

                        val response = familyService.convertToShared(principal.userId, familyId)
                        call.respond(HttpStatusCode.OK, response)
                    }

                    get("/members") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")

                        val response = familyService.getMembers(principal.userId, familyId)
                        call.respond(HttpStatusCode.OK, response)
                    }
                }
            }
        }
    }
}
