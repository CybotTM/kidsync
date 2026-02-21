package com.kidsync.app.data.remote.interceptor

import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that adds the session token to all outgoing requests.
 *
 * In the zero-knowledge architecture, authentication uses challenge-response
 * with Ed25519 signing keys. The server issues an opaque session token
 * (not JWT) after successful verification. There is no refresh flow;
 * when the session expires, the client re-authenticates via challenge-response.
 *
 * Requests to unauthenticated endpoints (/register, /auth/*, /health) are
 * sent without a token if none is available.
 */
class AuthInterceptor @Inject constructor(
    private val prefs: SharedPreferences
) : Interceptor {

    companion object {
        const val PREF_SESSION_TOKEN = "session_token"
        const val HEADER_AUTHORIZATION = "Authorization"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()

        // Add Bearer session token if available
        val token = prefs.getString(PREF_SESSION_TOKEN, null)
        if (!token.isNullOrBlank()) {
            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
