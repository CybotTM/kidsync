package com.kidsync.app.sync.webdav

import kotlinx.serialization.Serializable

/**
 * Configuration for connecting to a WebDAV/NextCloud server.
 *
 * Stored encrypted in [EncryptedSharedPreferences] to protect credentials at rest.
 * The [basePath] defines the root folder name within the WebDAV server where
 * KidSync stores its encrypted oplog data.
 */
@Serializable
data class WebDavConfig(
    val serverUrl: String,       // e.g. "https://cloud.example.com/remote.php/dav/files/user/"
    val username: String,
    val password: String,
    val basePath: String = "kidsync"  // folder name within WebDAV
) {
    /**
     * Returns the full base URL for KidSync data, ensuring proper trailing slash.
     */
    fun resolvedBaseUrl(): String {
        val base = serverUrl.trimEnd('/')
        return "$base/$basePath/"
    }
}
