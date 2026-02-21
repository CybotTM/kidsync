package dev.kidsync.server.routes

import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.BlobService
import dev.kidsync.server.util.ValidationUtil
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream

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
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    val maxBlobSize = 10 * 1024 * 1024L // 10 MB max
                    val multipart = call.receiveMultipart()
                    var fileBytes: ByteArray? = null
                    var clientSha256: String? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                if (part.name == "file") {
                                    // SEC2-S-04: Track bytes read during multipart processing to
                                    // guard against chunked transfer encoding bypassing Content-Length.
                                    val channel = part.provider()
                                    val buffer = ByteArrayOutputStream()
                                    val chunk = ByteArray(8192)
                                    var totalRead = 0L
                                    while (!channel.isClosedForRead) {
                                        val read = channel.readAvailable(chunk)
                                        if (read == -1) break
                                        totalRead += read
                                        if (totalRead > maxBlobSize) {
                                            part.dispose()
                                            throw ApiException(413, "PAYLOAD_TOO_LARGE", "File exceeds size limit")
                                        }
                                        buffer.write(chunk, 0, read)
                                    }
                                    fileBytes = buffer.toByteArray()
                                }
                            }
                            is PartData.FormItem -> {
                                if (part.name == "sha256") {
                                    clientSha256 = part.value.trim()
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    val bytes = fileBytes
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing 'file' part")
                    val declaredHash = clientSha256
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing 'sha256' part")

                    // Verify SHA-256 of the uploaded file matches client-declared hash
                    // SEC-S-03: Use constant-time comparison to prevent timing side-channel attacks
                    val computedHash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    if (!java.security.MessageDigest.isEqual(computedHash.toByteArray(), declaredHash.toByteArray())) {
                        throw ApiException(
                            400,
                            "HASH_MISMATCH",
                            "Blob SHA-256 mismatch: expected $declaredHash, got $computedHash"
                        )
                    }

                    val response = blobService.uploadBlob(principal.deviceId, bucketId, bytes)
                    call.respond(HttpStatusCode.Created, response)
                }

                /**
                 * GET /buckets/{id}/blobs/{blobId}
                 * Download an encrypted blob.
                 */
                get("/{blobId}") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                    val blobId = ValidationUtil.requireUuidPathParam(call, "blobId", "blob id")

                    val (bytes, sha256) = blobService.downloadBlob(blobId, principal.deviceId, bucketId)
                    call.response.header("X-Blob-SHA256", sha256)
                    call.respondBytes(bytes, ContentType.Application.OctetStream)
                }
            }
        }
    }
}
