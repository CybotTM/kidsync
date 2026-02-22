package dev.kidsync.server

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// SEC3-S-16: Storage paths are validated on startup via validateStoragePaths().
data class AppConfig(
    val dbPath: String = System.getenv("KIDSYNC_DB_PATH") ?: "data/kidsync.db",
    val serverOrigin: String = System.getenv("KIDSYNC_SERVER_ORIGIN") ?: "https://api.kidsync.app",
    val sessionTtlSeconds: Long = System.getenv("KIDSYNC_SESSION_TTL_SECONDS")?.toLongOrNull() ?: 3600L,
    val challengeTtlSeconds: Long = System.getenv("KIDSYNC_CHALLENGE_TTL_SECONDS")?.toLongOrNull() ?: 60L,
    val blobStoragePath: String = System.getenv("KIDSYNC_BLOB_PATH") ?: "data/blobs",
    val snapshotStoragePath: String = System.getenv("KIDSYNC_SNAPSHOT_PATH") ?: "data/snapshots",
    val maxBlobSizeBytes: Long = 10 * 1024 * 1024, // 10 MB
    val maxSnapshotSizeBytes: Long = 50 * 1024 * 1024, // 50 MB
    val maxPayloadSizeBytes: Int = 64 * 1024, // 64 KB per op
    val maxSnapshotsPerBucket: Int = System.getenv("KIDSYNC_MAX_SNAPSHOTS_PER_BUCKET")?.toIntOrNull() ?: 10,
    val serverVersion: String = "0.2.0",
    val checkpointInterval: Int = 100,
    val host: String = System.getenv("KIDSYNC_HOST") ?: "0.0.0.0",
    val port: Int = System.getenv("KIDSYNC_PORT")?.toIntOrNull() ?: 8080,
    val maxDevicesPerBucket: Int = System.getenv("KIDSYNC_MAX_DEVICES_PER_BUCKET")?.toIntOrNull() ?: 10,
    val snapshotRateLimitPerHour: Int = System.getenv("KIDSYNC_SNAPSHOT_RATE_LIMIT")?.toIntOrNull() ?: 1,
    val allowedBlobContentTypes: Set<String> = System.getenv("KIDSYNC_ALLOWED_BLOB_CONTENT_TYPES")
        ?.split(",")?.map { it.trim() }?.toSet()
        ?: setOf(
            "application/octet-stream",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "video/mp4",
            "video/quicktime",
        ),
    val pushTokenEncryptionKey: String? = System.getenv("KIDSYNC_PUSH_TOKEN_KEY"),
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AppConfig::class.java)

        /**
         * SEC3-S-16: Validate that configured storage paths exist and are writable.
         * Creates directories if they don't exist. Fails fast with a clear error
         * if paths are invalid or not writable.
         */
        fun validateStoragePaths(config: AppConfig) {
            val pathsToValidate = mapOf(
                "blobStoragePath" to config.blobStoragePath,
                "snapshotStoragePath" to config.snapshotStoragePath,
            )

            for ((name, path) in pathsToValidate) {
                if (path.isBlank()) {
                    throw IllegalStateException("SEC3-S-16: $name is empty. Configure a valid storage path.")
                }

                val dir = File(path)

                // Create directory if it doesn't exist
                if (!dir.exists()) {
                    logger.info("Creating storage directory for {}: {}", name, dir.absolutePath)
                    if (!dir.mkdirs()) {
                        throw IllegalStateException(
                            "SEC3-S-16: Failed to create directory for $name: ${dir.absolutePath}"
                        )
                    }

                    // Set directory permissions to 700 (owner rwx only)
                    try {
                        Files.setPosixFilePermissions(
                            dir.toPath(),
                            PosixFilePermissions.fromString("rwx------")
                        )
                    } catch (_: UnsupportedOperationException) {
                        // Windows doesn't support POSIX file permissions
                    }
                }

                // Verify it's a directory
                if (!dir.isDirectory) {
                    throw IllegalStateException(
                        "SEC3-S-16: $name path is not a directory: ${dir.absolutePath}"
                    )
                }

                // Verify it's writable
                if (!dir.canWrite()) {
                    throw IllegalStateException(
                        "SEC3-S-16: $name path is not writable: ${dir.absolutePath}"
                    )
                }

                logger.info("Validated storage path {}: {}", name, dir.canonicalPath)
            }

            // Validate DB path parent directory
            if (config.dbPath != ":memory:") {
                val dbDir = File(config.dbPath).parentFile
                if (dbDir != null && !dbDir.exists()) {
                    logger.info("Creating database directory: {}", dbDir.absolutePath)
                    if (!dbDir.mkdirs()) {
                        throw IllegalStateException(
                            "SEC3-S-16: Failed to create database directory: ${dbDir.absolutePath}"
                        )
                    }
                }
            }
        }
    }
}
