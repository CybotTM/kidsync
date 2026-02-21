package com.kidsync.app.data.remote.interceptor

import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Named

/**
 * OkHttp interceptor that adds the session token to authenticated requests.
 *
 * In the zero-knowledge architecture, authentication uses challenge-response
 * with Ed25519 signing keys. The server issues an opaque session token
 * (not JWT) after successful verification. There is no refresh flow;
 * when the session expires, the client re-authenticates via challenge-response.
 *
 * Requests to unauthenticated endpoints (/register, /auth/*, /health) skip
 * the Authorization header entirely, even when a token is available.
 *
 * Session tokens are stored in EncryptedSharedPreferences to prevent extraction
 * from device backups or root access.
 */
class AuthInterceptor @Inject constructor(
    @Named("encrypted_prefs") private val prefs: SharedPreferences
) : Interceptor {

    companion object {
        const val PREF_SESSION_TOKEN = "session_token"
        const val HEADER_AUTHORIZATION = "Authorization"

        /**
         * Paths that do not require authentication.
         * The token is not sent to these endpoints to avoid leaking session
         * credentials to pre-authentication flows.
         */
        private val UNAUTHENTICATED_PATH_PREFIXES = listOf(
            "/register",
            "/auth/"
        )
        private val UNAUTHENTICATED_EXACT_PATHS = setOf(
            "/health"
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        // Skip auth header on unauthenticated endpoints
        val skipAuth = UNAUTHENTICATED_EXACT_PATHS.contains(path) ||
            UNAUTHENTICATED_PATH_PREFIXES.any { path.startsWith(it) }

        if (skipAuth) {
            return chain.proceed(originalRequest)
        }

        val requestBuilder = originalRequest.newBuilder()

        // Add Bearer session token if available
        val token = prefs.getString(PREF_SESSION_TOKEN, null)
        if (!token.isNullOrBlank()) {
            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
