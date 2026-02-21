package dev.kidsync.server

data class AppConfig(
    val dbPath: String = System.getenv("KIDSYNC_DB_PATH") ?: "data/kidsync.db",
    val serverOrigin: String = System.getenv("KIDSYNC_SERVER_ORIGIN") ?: "https://api.kidsync.app",
    val sessionTtlSeconds: Long = System.getenv("KIDSYNC_SESSION_TTL_SECONDS")?.toLongOrNull() ?: 3600L,
    val challengeTtlSeconds: Long = System.getenv("KIDSYNC_CHALLENGE_TTL_SECONDS")?.toLongOrNull() ?: 60L,
    val blobStoragePath: String = System.getenv("KIDSYNC_BLOB_PATH") ?: "data/blobs",
    val snapshotStoragePath: String = System.getenv("KIDSYNC_SNAPSHOT_PATH") ?: "data/snapshots",
    val maxBlobSizeBytes: Long = 10 * 1024 * 1024, // 10 MB
    val maxSnapshotSizeBytes: Long = 50 * 1024 * 1024, // 50 MB
    val maxPayloadSizeBytes: Int = 256 * 1024, // 256 KB per op
    val serverVersion: String = "0.2.0",
    val checkpointInterval: Int = 100,
    val host: String = System.getenv("KIDSYNC_HOST") ?: "0.0.0.0",
    val port: Int = System.getenv("KIDSYNC_PORT")?.toIntOrNull() ?: 8080,
)
