package dev.kidsync.server.routes

import dev.kidsync.server.plugins.devicePrincipal
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
    authenticate("auth-session") {
        rateLimit(RateLimitName("general")) {
            route("/buckets/{id}/blobs") {
                /**
                 * POST /buckets/{id}/blobs
                 * Upload an encrypted blob to a bucket.
                 */
                post {
                    val principal = call.devicePrincipal()
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

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

                    val response = blobService.uploadBlob(principal.deviceId, bucketId, bytes)
                    call.respond(HttpStatusCode.Created, response)
                }

                /**
                 * GET /buckets/{id}/blobs/{blobId}
                 * Download an encrypted blob.
                 */
                get("/{blobId}") {
                    val principal = call.devicePrincipal()
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")
                    val blobId = call.parameters["blobId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing blobId")

                    val (bytes, sha256) = blobService.downloadBlob(blobId, principal.deviceId, bucketId)
                    call.response.header("X-Blob-SHA256", sha256)
                    call.respondBytes(bytes, ContentType.Application.OctetStream)
                }
            }
        }
    }
}
