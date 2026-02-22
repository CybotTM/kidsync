package dev.kidsync.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for SEC3-S-16: Storage path validation on startup.
 */
class StoragePathValidationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `valid paths pass validation`() {
        val blobDir = File(tempDir, "blobs")
        val snapshotDir = File(tempDir, "snapshots")
        blobDir.mkdirs()
        snapshotDir.mkdirs()

        val config = AppConfig(
            dbPath = ":memory:",
            blobStoragePath = blobDir.absolutePath,
            snapshotStoragePath = snapshotDir.absolutePath,
        )

        // Should not throw
        AppConfig.validateStoragePaths(config)
    }

    @Test
    fun `non-existent path creates directory`() {
        val newDir = File(tempDir, "new-blobs-${System.nanoTime()}")
        val snapshotDir = File(tempDir, "new-snapshots-${System.nanoTime()}")

        val config = AppConfig(
            dbPath = ":memory:",
            blobStoragePath = newDir.absolutePath,
            snapshotStoragePath = snapshotDir.absolutePath,
        )

        AppConfig.validateStoragePaths(config)

        assertTrue(newDir.exists(), "Blob directory should be created")
        assertTrue(newDir.isDirectory, "Blob path should be a directory")
        assertTrue(snapshotDir.exists(), "Snapshot directory should be created")
        assertTrue(snapshotDir.isDirectory, "Snapshot path should be a directory")
    }

    @Test
    fun `empty path fails validation`() {
        val config = AppConfig(
            dbPath = ":memory:",
            blobStoragePath = "",
            snapshotStoragePath = File(tempDir, "snapshots").absolutePath,
        )

        val ex = assertFailsWith<IllegalStateException> {
            AppConfig.validateStoragePaths(config)
        }
        assertTrue(ex.message!!.contains("empty"), "Error should mention empty path")
    }

    @Test
    fun `file path instead of directory fails validation`() {
        val file = File(tempDir, "not-a-dir.txt")
        file.writeText("I am a file")
        val snapshotDir = File(tempDir, "snapshots-${System.nanoTime()}")
        snapshotDir.mkdirs()

        val config = AppConfig(
            dbPath = ":memory:",
            blobStoragePath = file.absolutePath,
            snapshotStoragePath = snapshotDir.absolutePath,
        )

        val ex = assertFailsWith<IllegalStateException> {
            AppConfig.validateStoragePaths(config)
        }
        assertTrue(ex.message!!.contains("not a directory"), "Error should mention not a directory")
    }

    @Test
    fun `deeply nested non-existent path is created`() {
        val deepDir = File(tempDir, "a/b/c/d/e/blobs")
        val snapshotDir = File(tempDir, "a/b/c/d/e/snapshots")

        val config = AppConfig(
            dbPath = ":memory:",
            blobStoragePath = deepDir.absolutePath,
            snapshotStoragePath = snapshotDir.absolutePath,
        )

        AppConfig.validateStoragePaths(config)

        assertTrue(deepDir.exists(), "Deeply nested blob directory should be created")
        assertTrue(snapshotDir.exists(), "Deeply nested snapshot directory should be created")
    }

    @Test
    fun `db path parent directory created if needed`() {
        val dbPath = File(tempDir, "newdb/kidsync.db").absolutePath
        val blobDir = File(tempDir, "blobs-${System.nanoTime()}")
        val snapshotDir = File(tempDir, "snapshots-${System.nanoTime()}")

        val config = AppConfig(
            dbPath = dbPath,
            blobStoragePath = blobDir.absolutePath,
            snapshotStoragePath = snapshotDir.absolutePath,
        )

        AppConfig.validateStoragePaths(config)

        assertTrue(File(dbPath).parentFile.exists(), "DB parent directory should be created")
    }
}
