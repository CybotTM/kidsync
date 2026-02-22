package com.kidsync.app.sync.webdav

import java.io.Closeable

/**
 * Configuration for connecting to a WebDAV/NextCloud server.
 *
 * Stored encrypted in [EncryptedSharedPreferences] to protect credentials at rest.
 * The [basePath] defines the root folder name within the WebDAV server where
 * KidSync stores its encrypted oplog data.
 *
 * Password is stored as a [CharArray] so it can be explicitly zeroed when no longer
 * needed. Call [close] or [clearCredentials] when done with this config. The
 * short-lived String created by [passwordAsString] at the point of OkHttp Basic
 * Auth use is left for GC since the JVM String pool cannot be zeroed.
 *
 * SEC8: The [basePath] is validated in [resolvedBaseUrl] to reject path traversal
 * patterns (e.g., ".." segments, absolute paths).
 */
class WebDavConfig(
    val serverUrl: String,       // e.g. "https://cloud.example.com/remote.php/dav/files/user/"
    val username: String,
    password: CharArray,
    val basePath: String = "kidsync"  // folder name within WebDAV
) : Closeable {

    private val _password: CharArray = password.copyOf()

    /**
     * Convert the password to a String for point-of-use in OkHttp Basic Auth.
     * The returned String is short-lived and will be collected by GC.
     */
    fun passwordAsString(): String = String(_password)

    /**
     * Zero out the password CharArray so it doesn't linger in memory.
     */
    fun clearCredentials() {
        _password.fill('\u0000')
    }

    /**
     * Closeable implementation that delegates to [clearCredentials].
     */
    override fun close() {
        clearCredentials()
    }

    /**
     * Returns the full base URL for KidSync data, ensuring proper trailing slash.
     *
     * SEC8: Validates [basePath] to prevent path traversal attacks.
     * Rejects paths containing ".." segments, absolute paths, or URL-encoded
     * traversal sequences.
     *
     * @throws IllegalArgumentException if the basePath contains traversal patterns
     */
    fun resolvedBaseUrl(): String {
        validateBasePath(basePath)
        val base = serverUrl.trimEnd('/')
        val sanitizedPath = basePath.trim('/').trim()
        return "$base/$sanitizedPath/"
    }

    companion object {
        /**
         * Validate that a basePath does not contain path traversal sequences.
         *
         * @throws IllegalArgumentException if the path is unsafe
         */
        internal fun validateBasePath(path: String) {
            val decoded = path
                .replace("%2e", ".", ignoreCase = true)
                .replace("%2f", "/", ignoreCase = true)
                .replace("%5c", "\\", ignoreCase = true)

            val segments = decoded.replace('\\', '/').split('/')
            for (segment in segments) {
                if (segment == "..") {
                    throw IllegalArgumentException(
                        "basePath must not contain '..' traversal segments"
                    )
                }
            }

            // Reject absolute paths
            if (decoded.startsWith("/") || decoded.startsWith("\\")) {
                throw IllegalArgumentException(
                    "basePath must be a relative path, not absolute"
                )
            }

            // Reject paths with null bytes
            if (decoded.contains('\u0000') || path.contains("%00")) {
                throw IllegalArgumentException(
                    "basePath must not contain null bytes"
                )
            }
        }
    }
}
