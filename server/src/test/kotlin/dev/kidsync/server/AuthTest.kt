package dev.kidsync.server

import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthTest {

    private fun ApplicationTestBuilder.createJsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    @Test
    fun `register creates new user and returns tokens`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "test@example.com", password = "strong-password-12345"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<RegisterResponse>()
        assertNotNull(body.userId)
        assertNotNull(body.deviceId)
        assertNotNull(body.token)
        assertNotNull(body.refreshToken)
    }

    @Test
    fun `register rejects invalid email`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "invalid", password = "strong-password-12345"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register rejects weak password`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "test@example.com", password = "short"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register rejects duplicate email`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "dup@example.com", password = "strong-password-12345"))
        }

        val response2 = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "dup@example.com", password = "strong-password-12345"))
        }

        assertEquals(HttpStatusCode.Conflict, response2.status)
    }

    @Test
    fun `login succeeds with correct credentials`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Register first
        client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "login@example.com", password = "strong-password-12345"))
        }

        // Login
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "login@example.com", password = "strong-password-12345"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LoginResponse>()
        assertNotNull(body.token)
        assertNotNull(body.refreshToken)
    }

    @Test
    fun `login fails with wrong password`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "wrong@example.com", password = "strong-password-12345"))
        }

        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "wrong@example.com", password = "wrong-password-12345"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `refresh token flow works`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val regResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "refresh@example.com", password = "strong-password-12345"))
        }
        assertEquals(HttpStatusCode.Created, regResponse.status,
            "Register failed: ${regResponse.status}")
        val regBody = regResponse.body<RegisterResponse>()
        assertNotNull(regBody.refreshToken, "Register response missing refreshToken")

        val refreshResponse = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = regBody.refreshToken))
        }

        assertEquals(HttpStatusCode.OK, refreshResponse.status,
            "Refresh failed: ${refreshResponse.status}")
        val refreshBody = refreshResponse.body<RefreshResponse>()
        assertNotNull(refreshBody.token)
        assertNotNull(refreshBody.refreshToken)
    }

    @Test
    fun `refresh token cannot be reused`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val regResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "reuse@example.com", password = "strong-password-12345"))
        }
        val regBody = regResponse.body<RegisterResponse>()

        // First refresh succeeds
        client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = regBody.refreshToken))
        }

        // Second refresh with same token fails
        val response2 = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = regBody.refreshToken))
        }

        assertEquals(HttpStatusCode.Unauthorized, response2.status)
    }
}

fun testConfig(): AppConfig {
    val dbPath = "data/test-${System.nanoTime()}.db"
    return AppConfig(
        dbPath = dbPath,
        jwtSecret = "test-secret-that-is-at-least-32-characters-long!!",
        blobStoragePath = "data/test-blobs-${System.nanoTime()}",
        snapshotStoragePath = "data/test-snapshots-${System.nanoTime()}",
    )
}
