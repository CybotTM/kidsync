package com.kidsync.app.data.remote.interceptor

import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that adds JWT Bearer token and protocol version header
 * to all outgoing requests.
 *
 * The token is read from SharedPreferences. Requests to auth endpoints
 * (register, login, refresh) are sent without a token if none is available.
 */
class AuthInterceptor @Inject constructor(
    private val prefs: SharedPreferences
) : Interceptor {

    companion object {
        const val PREF_ACCESS_TOKEN = "access_token"
        const val PREF_REFRESH_TOKEN = "refresh_token"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_PROTOCOL_VERSION = "X-Protocol-Version"
        const val PROTOCOL_VERSION = "1"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()
            .header(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION)

        // Add Bearer token if available
        val token = prefs.getString(PREF_ACCESS_TOKEN, null)
        if (!token.isNullOrBlank()) {
            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
