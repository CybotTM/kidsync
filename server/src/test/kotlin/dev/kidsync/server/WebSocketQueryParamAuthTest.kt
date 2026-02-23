package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for SEC5-S-03: WebSocket query parameter authentication.
 *
 * Tests verify that:
 * 1. Valid query param token authenticates successfully
 * 2. Invalid query param token closes with 4001
 * 3. Absent query param falls through to in-band auth (existing behavior preserved)
 */
class WebSocketQueryParamAuthTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `valid query param token authenticates successfully`() = testApplication {
        application { module(testConfig()) }
        val httpClient = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(httpClient)
        val bucketId = device.bucketId!!

        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/buckets/$bucketId/ws?token=${device.sessionToken}") {
            // Should receive auth_ok without needing to send an auth message
            val frame = incoming.receive() as Frame.Text
            val authResponse = json.parseToJsonElement(frame.readText()).jsonObject
            assertEquals("auth_ok", authResponse["type"]?.jsonPrimitive?.content)
            assertEquals(device.deviceId, authResponse["deviceId"]?.jsonPrimitive?.content)
            assertEquals(bucketId, authResponse["bucketId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `invalid query param token closes with 4001`() = testApplication {
        application { module(testConfig()) }
        val httpClient = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(httpClient)
        val bucketId = device.bucketId!!

        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/buckets/$bucketId/ws?token=sess_invalidtoken12345") {
            // Should receive close with code 4001
            val reason = closeReason.await()
            assertEquals(4001, reason?.code)
            assertTrue(reason?.message?.contains("Invalid token") == true)
        }
    }

    @Test
    fun `absent query param falls through to in-band auth`() = testApplication {
        application { module(testConfig()) }
        val httpClient = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(httpClient)
        val bucketId = device.bucketId!!

        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/buckets/$bucketId/ws") {
            // Send in-band auth message (existing behavior)
            val authMsg = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("type", "auth")
                    put("token", device.sessionToken)
                }
            )
            send(Frame.Text(authMsg))

            // Should receive auth_ok
            val frame = incoming.receive() as Frame.Text
            val authResponse = json.parseToJsonElement(frame.readText()).jsonObject
            assertEquals("auth_ok", authResponse["type"]?.jsonPrimitive?.content)
            assertEquals(device.deviceId, authResponse["deviceId"]?.jsonPrimitive?.content)
        }
    }
}
