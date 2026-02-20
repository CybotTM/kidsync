package dev.kidsync.server

data class AppConfig(
    val dbPath: String = System.getenv("KIDSYNC_DB_PATH") ?: "data/kidsync.db",
    val jwtSecret: String = System.getenv("KIDSYNC_JWT_SECRET") ?: "change-me-in-production-at-least-32-chars!!",
    val jwtIssuer: String = System.getenv("KIDSYNC_JWT_ISSUER") ?: "kidsync-server",
    val jwtAudience: String = System.getenv("KIDSYNC_JWT_AUDIENCE") ?: "kidsync-client",
    val jwtAccessExpirationMinutes: Long = System.getenv("KIDSYNC_JWT_ACCESS_EXP_MIN")?.toLongOrNull() ?: 15L,
    val jwtRefreshExpirationDays: Long = System.getenv("KIDSYNC_JWT_REFRESH_EXP_DAYS")?.toLongOrNull() ?: 30L,
    val blobStoragePath: String = System.getenv("KIDSYNC_BLOB_PATH") ?: "data/blobs",
    val snapshotStoragePath: String = System.getenv("KIDSYNC_SNAPSHOT_PATH") ?: "data/snapshots",
    val maxBlobSizeBytes: Long = 10 * 1024 * 1024, // 10 MB
    val maxSnapshotSizeBytes: Long = 50 * 1024 * 1024, // 50 MB
    val serverVersion: String = "0.1.0",
    val minProtocolVersion: Int = 1,
    val maxProtocolVersion: Int = 1,
    val checkpointInterval: Int = 100,
    val host: String = System.getenv("KIDSYNC_HOST") ?: "0.0.0.0",
    val port: Int = System.getenv("KIDSYNC_PORT")?.toIntOrNull() ?: 8080,
)
