package com.kidsync.app.sync.webdav

import android.util.Log
import com.kidsync.app.BuildConfig
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.data.local.entity.SyncStateEntity
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Manages WebDAV sync operations for the KidSync oplog.
 *
 * Uses OkHttp directly for WebDAV operations (PROPFIND, MKCOL, PUT, GET).
 * WebDAV is standard HTTP with additional methods, so no special library is needed.
 *
 * Folder structure on the WebDAV server:
 * ```
 * kidsync/
 *   buckets/
 *     {bucketId}/
 *       ops/
 *         {globalSequence}-{deviceId}.json   # Individual encrypted op
 *       meta/
 *         checkpoint.json                     # Latest sync checkpoint
 *         lamport.txt                         # Lamport timestamp counter
 * ```
 *
 * All data stored on the WebDAV server remains encrypted -- the server never
 * sees plaintext content. Lamport timestamps are used for ordering since WebDAV
 * has no server-assigned sequence numbers.
 */
@Singleton
class WebDavSyncManager @Inject constructor(
    private val opLogDao: OpLogDao,
    private val syncStateDao: SyncStateDao,
    private val json: Json,
    private val hashChainVerifier: HashChainVerifier = HashChainVerifier()
) {
    private val lock = ReentrantLock()
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var config: WebDavConfig? = null

    companion object {
        private const val TAG = "WebDavSyncManager"
        private const val PROPFIND_DEPTH_1 = "1"
        private const val MAX_OP_FILE_SIZE_BYTES = 10 * 1024 * 1024L // 10 MB per op file
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        // PROPFIND request body to list directory contents
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""
    }

    /**
     * Configure the WebDAV client with server credentials.
     * Creates a new OkHttpClient with Basic auth for the given config.
     *
     * SEC7: Enforces HTTPS in release builds and prevents HTTPS->HTTP downgrade
     * via redirect following. Uses MODERN_TLS connection spec to reject weak ciphers.
     */
    fun configure(config: WebDavConfig) = lock.withLock {
        val scheme = java.net.URI(config.serverUrl).scheme?.lowercase()
        val isHttps = scheme == "https"

        // SEC7: Reject non-HTTPS URLs in release builds
        if (!BuildConfig.DEBUG && !isHttps) {
            throw IllegalArgumentException(
                "WebDAV server URL must use HTTPS in release builds. Got: $scheme"
            )
        }

        this.config = config
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // SEC7: Prevent HTTPS->HTTP downgrade via redirect
            .followRedirects(false)
            .addInterceptor { chain ->
                val original = chain.request()
                val authenticated = original.newBuilder()
                    .header("Authorization", Credentials.basic(config.username, config.password))
                    .build()
                chain.proceed(authenticated)
            }

        // SEC7: Enforce modern TLS for HTTPS connections (skip for HTTP in debug)
        if (isHttps) {
            builder.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        } else {
            builder.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        }

        this.client = builder.build()
    }

    /**
     * Verify that the client has been configured.
     */
    private fun requireConfigured(): Pair<OkHttpClient, WebDavConfig> = lock.withLock {
        val c = client ?: throw IllegalStateException("WebDavSyncManager not configured. Call configure() first.")
        val cfg = config ?: throw IllegalStateException("WebDavConfig is null.")
        c to cfg
    }

    /**
     * Test the connection to the WebDAV server by issuing a PROPFIND on the root.
     */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val (httpClient, cfg) = requireConfigured()
            val url = cfg.serverUrl.trimEnd('/')

            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                // WebDAV PROPFIND returns 207 Multi-Status on success
                if (response.code == 207 || response.isSuccessful) {
                    Result.success(Unit)
                } else if (response.code == 401 || response.code == 403) {
                    Result.failure(WebDavAuthException("Authentication failed (HTTP ${response.code})"))
                } else {
                    Result.failure(WebDavException("Server returned HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: IOException) {
            Result.failure(WebDavException("Connection failed: ${e.message}", e))
        } catch (e: IllegalStateException) {
            Result.failure(e)
        }
    }

    /**
     * Ensure the full directory structure exists for a bucket.
     * Creates: kidsync/buckets/{bucketId}/ops/ and kidsync/buckets/{bucketId}/meta/
     */
    suspend fun ensureDirectoryStructure(bucketId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val (_, cfg) = requireConfigured()
            val base = cfg.resolvedBaseUrl()

            // Create directories in order (parent first)
            val dirs = listOf(
                base,                                    // kidsync/
                "${base}buckets/",                       // kidsync/buckets/
                "${base}buckets/$bucketId/",             // kidsync/buckets/{bucketId}/
                "${base}buckets/$bucketId/ops/",         // kidsync/buckets/{bucketId}/ops/
                "${base}buckets/$bucketId/meta/"          // kidsync/buckets/{bucketId}/meta/
            )

            for (dir in dirs) {
                mkcol(dir)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(WebDavException("Failed to create directory structure: ${e.message}", e))
        }
    }

    /**
     * Push local pending ops to the WebDAV server.
     *
     * For each op, creates a file: ops/{lamportTimestamp}-{deviceId}.json
     * Also updates the lamport.txt counter.
     *
     * @return Result containing the count of ops pushed
     */
    suspend fun pushOps(bucketId: String, ops: List<OpLogEntry>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (ops.isEmpty()) return@withContext Result.success(0)

            val (_, cfg) = requireConfigured()
            val opsDir = "${cfg.resolvedBaseUrl()}buckets/$bucketId/ops/"
            val metaDir = "${cfg.resolvedBaseUrl()}buckets/$bucketId/meta/"

            // Read current Lamport timestamp
            var lamport = readLamportTimestamp(bucketId)

            var pushed = 0
            for (op in ops) {
                lamport++

                val webDavOp = WebDavOp(
                    globalSequence = op.globalSequence,
                    bucketId = op.bucketId,
                    deviceId = op.deviceId,
                    deviceSequence = op.deviceSequence,
                    keyEpoch = op.keyEpoch,
                    encryptedPayload = op.encryptedPayload,
                    devicePrevHash = op.devicePrevHash,
                    currentHash = op.currentHash,
                    lamportTimestamp = lamport,
                    serverTimestamp = op.serverTimestamp?.toString()
                )

                val filename = "$lamport-${op.deviceId}.json"
                val body = json.encodeToString(webDavOp)

                putFile("$opsDir$filename", body, JSON_MEDIA_TYPE)
                pushed++

                // Mark the op as synced in local DB
                val entity = opLogDao.findOp(
                    bucketId = op.bucketId,
                    deviceId = op.deviceId,
                    deviceSequence = op.deviceSequence
                )
                if (entity != null) {
                    opLogDao.markAsSynced(
                        id = entity.id,
                        globalSequence = lamport,
                        serverTimestamp = Instant.now().toString()
                    )
                }
            }

            // Update Lamport timestamp
            putFile("${metaDir}lamport.txt", lamport.toString(), TEXT_MEDIA_TYPE)

            Result.success(pushed)
        } catch (e: Exception) {
            Result.failure(WebDavException("Failed to push ops: ${e.message}", e))
        }
    }

    /**
     * Pull new ops from the WebDAV server that have a Lamport timestamp
     * greater than [afterSequence].
     *
     * Lists the ops/ directory via PROPFIND, parses filenames to find
     * newer ops, downloads each, and inserts them into the local database.
     *
     * @return Result containing the list of newly pulled ops
     */
    suspend fun pullOps(bucketId: String, afterSequence: Long): Result<List<OpLogEntry>> = withContext(Dispatchers.IO) {
        try {
            val (_, cfg) = requireConfigured()
            val opsDir = "${cfg.resolvedBaseUrl()}buckets/$bucketId/ops/"

            // List all op files in the directory
            val fileNames = listDirectory(opsDir)

            // Parse filenames and filter for ops newer than afterSequence
            // Filename format: {lamportTimestamp}-{deviceId}.json
            val newOps = mutableListOf<OpLogEntry>()

            val relevantFiles = fileNames
                .filter { it.endsWith(".json") }
                .mapNotNull { filename ->
                    val lamport = extractLamportFromFilename(filename)
                    if (lamport != null && lamport > afterSequence) {
                        filename to lamport
                    } else null
                }
                .sortedBy { it.second }

            for ((filename, _) in relevantFiles) {
                val content = getFile("$opsDir$filename")
                if (content != null) {
                    // SEC7: Check response body size to prevent oversized op files
                    if (content.length > MAX_OP_FILE_SIZE_BYTES) {
                        Log.w(TAG, "Skipping oversized op file: $filename (${content.length} bytes)")
                        continue
                    }

                    val webDavOp = json.decodeFromString<WebDavOp>(content)

                    // Reject ops with mismatched bucketId (cross-bucket injection protection)
                    if (webDavOp.bucketId != bucketId) {
                        Log.w(TAG, "Skipping op with mismatched bucketId: expected=$bucketId, got=${webDavOp.bucketId}")
                        continue
                    }

                    val opLogEntry = webDavOp.toOpLogEntry()
                    newOps.add(opLogEntry)
                }
            }

            // SEC7: Verify hash chain integrity before inserting any ops
            if (newOps.isNotEmpty()) {
                val localLastOps = opLogDao.getLastOpsPerDeviceForBucket(bucketId)
                val localLastHashes = localLastOps.associate { it.deviceId to it.currentHash }

                val verifyResult = hashChainVerifier.verifyChains(newOps, localLastHashes)
                if (verifyResult.isFailure) {
                    return@withContext Result.failure(
                        WebDavException("Hash chain verification failed: ${verifyResult.exceptionOrNull()?.message}")
                    )
                }
            }

            // Insert verified ops into local database
            for (op in newOps) {
                opLogDao.insertOpLogEntry(
                    OpLogEntryEntity(
                        globalSequence = op.globalSequence,
                        bucketId = op.bucketId,
                        deviceId = op.deviceId,
                        deviceSequence = op.deviceSequence,
                        keyEpoch = op.keyEpoch,
                        encryptedPayload = op.encryptedPayload,
                        devicePrevHash = op.devicePrevHash,
                        currentHash = op.currentHash,
                        serverTimestamp = op.serverTimestamp?.toString(),
                        isPending = false
                    )
                )
            }

            // Update sync state
            if (newOps.isNotEmpty()) {
                val maxLamport = relevantFiles.maxOf { it.second }
                syncStateDao.upsertSyncState(
                    SyncStateEntity(
                        bucketId = bucketId,
                        lastGlobalSequence = maxLamport,
                        lastSyncTimestamp = Instant.now().toString()
                    )
                )
            }

            Result.success(newOps)
        } catch (e: Exception) {
            Result.failure(WebDavException("Failed to pull ops: ${e.message}", e))
        }
    }

    /**
     * Upload a sync checkpoint to the WebDAV server.
     */
    suspend fun uploadCheckpoint(bucketId: String, checkpoint: WebDavCheckpoint): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val (_, cfg) = requireConfigured()
                val metaDir = "${cfg.resolvedBaseUrl()}buckets/$bucketId/meta/"
                val body = json.encodeToString(checkpoint)
                putFile("${metaDir}checkpoint.json", body, JSON_MEDIA_TYPE)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(WebDavException("Failed to upload checkpoint: ${e.message}", e))
            }
        }

    /**
     * Download the latest sync checkpoint from the WebDAV server.
     * Returns null if no checkpoint exists yet.
     */
    suspend fun downloadCheckpoint(bucketId: String): WebDavCheckpoint? = withContext(Dispatchers.IO) {
        try {
            val (_, cfg) = requireConfigured()
            val metaDir = "${cfg.resolvedBaseUrl()}buckets/$bucketId/meta/"
            val content = getFile("${metaDir}checkpoint.json")
            content?.let { json.decodeFromString<WebDavCheckpoint>(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get pending (unsynced) ops for a bucket from the local database,
     * converted to domain model OpLogEntry objects ready for push.
     */
    suspend fun getPendingOpsForPush(bucketId: String): List<OpLogEntry> {
        return opLogDao.getPendingOps(bucketId).map { entity ->
            OpLogEntry(
                globalSequence = entity.globalSequence,
                bucketId = entity.bucketId,
                deviceId = entity.deviceId,
                deviceSequence = entity.deviceSequence,
                keyEpoch = entity.keyEpoch,
                encryptedPayload = entity.encryptedPayload,
                devicePrevHash = entity.devicePrevHash,
                currentHash = entity.currentHash,
                serverTimestamp = entity.serverTimestamp?.let {
                    try { Instant.parse(it) } catch (_: Exception) { null }
                }
            )
        }
    }

    /**
     * Read the current Lamport timestamp from the server.
     * Returns 0 if no timestamp file exists yet.
     */
    internal suspend fun readLamportTimestamp(bucketId: String): Long {
        return try {
            val (_, cfg) = requireConfigured()
            val metaDir = "${cfg.resolvedBaseUrl()}buckets/$bucketId/meta/"
            val content = getFile("${metaDir}lamport.txt")
            content?.trim()?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Low-level WebDAV HTTP operations
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Create a directory on the WebDAV server (MKCOL).
     * Silently succeeds if the directory already exists (405 Method Not Allowed).
     */
    internal fun mkcol(url: String) {
        val (httpClient, _) = requireConfigured()
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .build()

        httpClient.newCall(request).execute().use { response ->
            // 201 Created or 405 Already Exists are both OK
            if (response.code != 201 && response.code != 405 && !response.isSuccessful) {
                throw WebDavException("MKCOL $url failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Upload a file to the WebDAV server (PUT).
     */
    internal fun putFile(url: String, content: String, mediaType: okhttp3.MediaType) {
        val (httpClient, _) = requireConfigured()
        val request = Request.Builder()
            .url(url)
            .put(content.toRequestBody(mediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw WebDavException("PUT $url failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Download a file from the WebDAV server (GET).
     * Returns null if the file doesn't exist (404).
     *
     * SEC7: Checks Content-Length header against MAX_OP_FILE_SIZE_BYTES to
     * reject oversized responses before reading the body.
     */
    internal fun getFile(url: String): String? {
        val (httpClient, _) = requireConfigured()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            return when {
                response.isSuccessful -> {
                    // SEC7: Check Content-Length before reading body
                    val contentLength = response.body?.contentLength() ?: -1L
                    if (contentLength > MAX_OP_FILE_SIZE_BYTES) {
                        throw WebDavException(
                            "GET $url: response too large ($contentLength bytes, max: $MAX_OP_FILE_SIZE_BYTES)"
                        )
                    }
                    response.body?.string()
                }
                response.code == 404 -> null
                else -> throw WebDavException("GET $url failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * List files in a WebDAV directory using PROPFIND.
     * Returns a list of filenames (not full paths).
     */
    internal fun listDirectory(url: String): List<String> {
        val (httpClient, _) = requireConfigured()
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", PROPFIND_DEPTH_1)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code != 207 && !response.isSuccessful) {
                if (response.code == 404) return emptyList()
                throw WebDavException("PROPFIND $url failed: HTTP ${response.code}")
            }

            val body = response.body?.string() ?: return emptyList()
            return parsePropfindResponse(body, url)
        }
    }

    /**
     * Parse a PROPFIND XML response to extract file/directory names.
     *
     * SEC7: Uses XmlPullParser instead of regex for robust XML parsing,
     * preventing injection via crafted XML responses.
     *
     * Extracts href values from DAV: namespace href elements, filters out
     * the parent directory itself, and returns just the filename portion.
     */
    internal fun parsePropfindResponse(xml: String, parentUrl: String): List<String> {
        val results = mutableListOf<String>()
        val parentPath = java.net.URI(parentUrl).path.trimEnd('/')

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var insideHref = false
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        // Match <d:href>, <D:href>, or <href> in DAV: namespace
                        if (parser.name == "href" &&
                            (parser.namespace == "DAV:" || parser.namespace.isNullOrEmpty())) {
                            insideHref = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideHref) {
                            val href = parser.text?.trim() ?: ""
                            val hrefPath = try {
                                java.net.URI(href).path.trimEnd('/')
                            } catch (_: Exception) {
                                href.trimEnd('/')
                            }

                            // Skip the parent directory itself
                            if (hrefPath != parentPath && hrefPath.isNotEmpty()) {
                                val filename = hrefPath.substringAfterLast('/')
                                if (filename.isNotBlank()) {
                                    results.add(filename)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "href") {
                            insideHref = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PROPFIND response with XmlPullParser, falling back to regex", e)
            // Fallback: simple regex-based parsing as last resort
            return parsePropfindResponseRegexFallback(xml, parentPath)
        }

        return results
    }

    /**
     * Regex fallback for PROPFIND parsing in case XmlPullParser is unavailable.
     */
    private fun parsePropfindResponseRegexFallback(xml: String, parentPath: String): List<String> {
        val results = mutableListOf<String>()
        val hrefPattern = Regex("""<[dD]:href>([^<]+)</[dD]:href>""")

        for (match in hrefPattern.findAll(xml)) {
            val href = match.groupValues[1].trim()
            val hrefPath = try {
                java.net.URI(href).path.trimEnd('/')
            } catch (_: Exception) {
                href.trimEnd('/')
            }

            if (hrefPath == parentPath || hrefPath.isEmpty()) continue

            val filename = hrefPath.substringAfterLast('/')
            if (filename.isNotBlank()) {
                results.add(filename)
            }
        }

        return results
    }

    /**
     * Extract the Lamport timestamp from a filename.
     * Expected format: {lamportTimestamp}-{deviceId}.json
     */
    internal fun extractLamportFromFilename(filename: String): Long? {
        val name = filename.removeSuffix(".json")
        val dashIndex = name.indexOf('-')
        if (dashIndex <= 0) return null
        return name.substring(0, dashIndex).toLongOrNull()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Data classes for WebDAV sync
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Checkpoint stored in the meta/ directory on the WebDAV server.
 * Tracks the latest known state for resuming sync.
 */
@Serializable
data class WebDavCheckpoint(
    val lastSequence: Long,
    val lamportTimestamp: Long,
    val updatedAt: String
)

/**
 * Serialized form of an OpLogEntry stored as a JSON file on WebDAV.
 * Uses Lamport timestamps for ordering since WebDAV has no
 * server-assigned sequence numbers.
 */
@Serializable
data class WebDavOp(
    val globalSequence: Long,
    val bucketId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val lamportTimestamp: Long,
    val serverTimestamp: String? = null
) {
    /**
     * Convert to the domain OpLogEntry model.
     * Uses lamportTimestamp as the globalSequence for ordering.
     */
    fun toOpLogEntry(): OpLogEntry = OpLogEntry(
        globalSequence = lamportTimestamp,
        bucketId = bucketId,
        deviceId = deviceId,
        deviceSequence = deviceSequence,
        keyEpoch = keyEpoch,
        encryptedPayload = encryptedPayload,
        devicePrevHash = devicePrevHash,
        currentHash = currentHash,
        serverTimestamp = serverTimestamp?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Exceptions
// ──────────────────────────────────────────────────────────────────────────────

open class WebDavException(message: String, cause: Throwable? = null) : IOException(message, cause)

class WebDavAuthException(message: String) : WebDavException(message)
