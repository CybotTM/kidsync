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
 *
 * TODO(SEC3-A-21): Implement an OkHttp Authenticator (TokenAuthenticator) to handle
 * 401 responses with automatic re-authentication via challenge-response. This would
 * transparently retry failed requests after obtaining a new session token, avoiding
 * the need for callers to handle 401s explicitly. Implementation requires injecting
 * AuthRepository or KeyManager + ApiService (careful to avoid circular dependencies
 * with Hilt). Consider using OkHttp's `Authenticator` interface which is specifically
 * designed for this purpose.
 */
class AuthInterceptor @Inject constructor(
    @Named("encrypted_prefs") private val prefs: SharedPreferences
) : Interceptor {

    companion object {
        const val PREF_SESSION_TOKEN = "session_token"
        const val PREF_SESSION_EXPIRES_AT = "session_expires_at"
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

        // Add Bearer session token if available and not expired
        val token = prefs.getString(PREF_SESSION_TOKEN, null)
        if (!token.isNullOrBlank()) {
            // SEC2-A-21: Check if the session has expired before adding the Bearer token.
            // If expired, clear the token and skip the header -- the server will return 401,
            // which the app can handle by triggering re-authentication via challenge-response.
            val expiresAt = prefs.getLong(PREF_SESSION_EXPIRES_AT, 0L)
            if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) {
                // Session expired -- clear token and do not send it
                prefs.edit()
                    .remove(PREF_SESSION_TOKEN)
                    .remove(PREF_SESSION_EXPIRES_AT)
                    .apply()
            } else {
                requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $token")
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}
