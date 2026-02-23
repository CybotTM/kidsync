package dev.kidsync.server.db

import dev.kidsync.server.AppConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * SEC2-S-20: The SQLite database is not encrypted at rest by default. For production
 * deployments handling sensitive data, consider:
 * 1. Using SQLCipher (drop-in replacement for sqlite-jdbc) for encryption at rest
 * 2. Deploying on an encrypted filesystem (LUKS, dm-crypt, etc.)
 * 3. Using full-disk encryption on the host
 * To enable SQLCipher, replace the sqlite-jdbc dependency with sqlcipher-android or
 * a JVM SQLCipher driver, and provide the encryption key via KIDSYNC_DB_KEY env var.
 */
object DatabaseFactory {

    private lateinit var database: Database

    /** Counter for generating unique temporary database paths across tests. */
    private val tempDbCounter = AtomicLong(0)

    fun init(config: AppConfig) {
        val isMemory = config.dbPath == ":memory:"

        val effectiveDbPath = if (isMemory) {
            // For in-memory mode (testing), use a unique temporary file instead.
            // SQLite :memory: creates separate databases per connection, which
            // breaks with Exposed's connection pooling. Temporary files ensure
            // all connections share the same database.
            val tempDir = System.getProperty("java.io.tmpdir")
            val uniqueName = "kidsync_test_${tempDbCounter.incrementAndGet()}_${System.nanoTime()}.db"
            "$tempDir/$uniqueName"
        } else {
            config.dbPath
        }

        val dbFile = File(effectiveDbPath)
        dbFile.parentFile?.mkdirs()
        // Ensure clean state for test databases
        if (isMemory) {
            dbFile.delete()
        }

        val sqliteConfig = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
            enforceForeignKeys(true)
        }

        val dataSource = SQLiteDataSource(sqliteConfig).apply {
            url = "jdbc:sqlite:$effectiveDbPath"
        }

        database = Database.connect(dataSource)

        transaction(database) {
            SchemaUtils.create(
                Devices,
                Buckets,
                BucketAccess,
                Ops,
                Blobs,
                WrappedKeys,
                RecoveryBlobs,
                PushTokens,
                Checkpoints,
                Snapshots,
                InviteTokens,
                KeyAttestations,
                Sessions,
                Challenges,
                CheckpointAcknowledgments,
            )
        }
    }

    /**
     * Convenience wrapper for executing suspended database transactions.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
