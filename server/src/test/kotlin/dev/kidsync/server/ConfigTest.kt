package dev.kidsync.server

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for AppConfig defaults and structure.
 */
class ConfigTest {

    @Test
    fun `default session TTL is 3600 seconds`() {
        val config = testConfig()
        assertEquals(3600L, config.sessionTtlSeconds)
    }

    @Test
    fun `default challenge TTL is 60 seconds`() {
        val config = testConfig()
        assertEquals(60L, config.challengeTtlSeconds)
    }

    @Test
    fun `default max payload size is 64KB`() {
        val config = AppConfig()
        assertEquals(64 * 1024, config.maxPayloadSizeBytes)
    }

    @Test
    fun `default max blob size is 10MB`() {
        val config = AppConfig()
        assertEquals(10L * 1024 * 1024, config.maxBlobSizeBytes)
    }

    @Test
    fun `default max snapshot size is 50MB`() {
        val config = AppConfig()
        assertEquals(50L * 1024 * 1024, config.maxSnapshotSizeBytes)
    }

    @Test
    fun `default checkpoint interval is 100`() {
        val config = AppConfig()
        assertEquals(100, config.checkpointInterval)
    }

    @Test
    fun `default host is 0_0_0_0`() {
        val config = AppConfig()
        assertEquals("0.0.0.0", config.host)
    }

    @Test
    fun `default port is 8080`() {
        val config = AppConfig()
        assertEquals(8080, config.port)
    }

    @Test
    fun `test config uses in-memory database`() {
        val config = testConfig()
        assertEquals(":memory:", config.dbPath)
    }

    @Test
    fun `test config uses test server origin`() {
        val config = testConfig()
        assertEquals("https://test.kidsync.app", config.serverOrigin)
    }

    @Test
    fun `config copy preserves other fields while overriding one`() {
        val config = testConfig()
        val modified = config.copy(sessionTtlSeconds = 0L)
        assertEquals(0L, modified.sessionTtlSeconds)
        assertEquals(config.dbPath, modified.dbPath)
        assertEquals(config.serverOrigin, modified.serverOrigin)
        assertEquals(config.challengeTtlSeconds, modified.challengeTtlSeconds)
    }

    @Test
    fun `server version is set`() {
        val config = AppConfig()
        assertTrue(config.serverVersion.isNotEmpty())
    }
}
