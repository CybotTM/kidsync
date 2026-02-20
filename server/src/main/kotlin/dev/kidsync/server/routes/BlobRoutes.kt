package dev.kidsync.server.routes

import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.BlobService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*

fun Route.blobRoutes(blobService: BlobService) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/blobs") {
                post {
                    val principal = call.userPrincipal()
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "User is not a member of any family")

                    val multipart = call.receiveMultipart()
                    var fileBytes: ByteArray? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                if (part.name == "file") {
                                    fileBytes = part.provider().toByteArray()
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    val bytes = fileBytes
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing 'file' part")

                    val result = blobService.uploadBlob(principal.userId, familyId, bytes)
                    result.fold(
                        onSuccess = { call.respond(HttpStatusCode.Created, it) },
                        onFailure = { throw it },
                    )
                }

                get("/{blobId}") {
                    val principal = call.userPrincipal()
                    val blobId = call.parameters["blobId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing blobId")
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "Not a family member")

                    val result = blobService.downloadBlob(blobId, principal.userId, familyId)
                    result.fold(
                        onSuccess = { (bytes, sha256) ->
                            call.response.header("X-Blob-SHA256", sha256)
                            call.respondBytes(bytes, ContentType.Application.OctetStream)
                        },
                        onFailure = { throw it },
                    )
                }

                delete("/{blobId}") {
                    val principal = call.userPrincipal()
                    val blobId = call.parameters["blobId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing blobId")

                    val result = blobService.deleteBlob(blobId, principal.userId)
                    result.fold(
                        onSuccess = { call.respond(HttpStatusCode.NoContent) },
                        onFailure = { throw it },
                    )
                }
            }
        }
    }
}
