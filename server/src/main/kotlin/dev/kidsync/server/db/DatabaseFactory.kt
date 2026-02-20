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

object DatabaseFactory {

    fun init(config: AppConfig) {
        val dbFile = File(config.dbPath)
        dbFile.parentFile?.mkdirs()

        // Configure SQLite pragmas via the driver's config API.
        // This avoids issues with PRAGMA inside transactions and
        // ensures they are applied per-connection.
        val sqliteConfig = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
            enforceForeignKeys(true)
        }

        val dataSource = SQLiteDataSource(sqliteConfig).apply {
            url = "jdbc:sqlite:${config.dbPath}"
        }

        val database = Database.connect(dataSource)

        transaction(database) {
            SchemaUtils.create(
                Users,
                Devices,
                Families,
                FamilyMembers,
                OpLog,
                Blobs,
                PushTokens,
                Invites,
                RefreshTokens,
                Snapshots,
                Checkpoints,
                OverrideStates,
                WrappedKeys,
                RecoveryBlobs,
            )
        }
    }

    /**
     * Convenience wrapper for executing suspended database transactions.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
