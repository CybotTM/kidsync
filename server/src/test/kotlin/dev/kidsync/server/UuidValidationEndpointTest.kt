package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals

/**
 * Tests that all endpoints reject non-UUID path parameters with 400 INVALID_REQUEST.
 */
class UuidValidationEndpointTest {

    @Test
    fun `ops endpoint rejects non-uuid bucket id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.get("/buckets/not-a-uuid/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `devices endpoint rejects non-uuid bucket id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.get("/buckets/not-a-uuid/devices") {
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `invite endpoint rejects non-uuid bucket id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.post("/buckets/not-a-uuid/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(InviteRequest(tokenHash = "a".repeat(64)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `join endpoint rejects non-uuid bucket id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.post("/buckets/not-a-uuid/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = "any-token"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `delete bucket rejects non-uuid bucket id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.delete("/buckets/not-a-uuid") {
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `blob upload rejects non-uuid bucket id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.get("/buckets/not-a-uuid/blobs/00000000-0000-0000-0000-000000000000") {
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `blob download rejects non-uuid blob id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/blobs/not-a-uuid") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }

    @Test
    fun `attestations endpoint rejects non-uuid device id with 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.get("/keys/attestations/not-a-uuid") {
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVALID_REQUEST", body.error)
    }
}
