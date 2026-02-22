package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.HealthResponse
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SEC6-S-18: Health endpoint tests verifying it returns only status,
 * without version, uptime, or other potentially sensitive information.
 */
class HealthEndpointTest {

    @Test
    fun `health endpoint returns 200 OK`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `health endpoint returns healthy status`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val body = client.get("/health").body<HealthResponse>()
        assertEquals("healthy", body.status)
    }

    @Test
    fun `health endpoint does not expose version`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val body = client.get("/health").body<HealthResponse>()
        assertNull(body.version, "version should not be included in health response")
    }

    @Test
    fun `health endpoint does not expose uptime`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val body = client.get("/health").body<HealthResponse>()
        assertNull(body.uptime, "uptime should not be included in health response")
    }

    @Test
    fun `health endpoint JSON has only status field`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val rawJson = client.get("/health").bodyAsText()
        val jsonObj = Json.parseToJsonElement(rawJson).jsonObject

        // The response should only contain "status"
        assertEquals(setOf("status"), jsonObj.keys, "Health response should only contain 'status' field")
    }

    @Test
    fun `health endpoint is accessible without authentication`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // No auth header
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `health endpoint returns application json content type`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.contains("application/json") == true,
            "Content-Type should be application/json, got: $contentType")
    }
}
