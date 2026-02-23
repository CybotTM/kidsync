package com.kidsync.app.data.remote.interceptor

import android.content.SharedPreferences
import com.kidsync.app.domain.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Named

/**
 * OkHttp Authenticator that handles 401 responses by re-authenticating
 * via the Ed25519 challenge-response flow and retrying the failed request.
 *
 * This replaces the need for callers to handle 401s explicitly. When the
 * server returns a 401 Unauthorized, OkHttp invokes this authenticator
 * which:
 * 1. Checks if a retry has already been attempted (prevents infinite loops)
 * 2. Synchronizes concurrent 401s so only one re-authentication occurs
 * 3. Performs challenge-response auth via AuthRepository
 * 4. Retries the original request with the new session token
 *
 * Uses [dagger.Lazy] for AuthRepository to break the circular Hilt dependency:
 * NetworkModule -> OkHttpClient -> TokenAuthenticator -> AuthRepository -> ApiService -> OkHttpClient
 */
class TokenAuthenticator @Inject constructor(
    private val authRepository: dagger.Lazy<AuthRepository>,
    @Named("encrypted_prefs") private val prefs: SharedPreferences
) : Authenticator {

    private val lock = Object()

    /**
     * Header added to retried requests to prevent infinite retry loops.
     * If the retried request also gets a 401, we return null (give up).
     */
    companion object {
        internal const val HEADER_AUTH_RETRY = "X-Auth-Retry"
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Prevent infinite retry loops: if we already retried, give up
        if (response.request.header(HEADER_AUTH_RETRY) != null) {
            return null
        }

        synchronized(lock) {
            // Check if another thread already refreshed the token since our
            // request was made. Compare the token we sent with the current one.
            val currentToken = prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
            val requestToken = response.request.header(AuthInterceptor.HEADER_AUTHORIZATION)
                ?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                // Token was already refreshed by another thread -- retry with it
                return response.request.newBuilder()
                    .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $currentToken")
                    .header(HEADER_AUTH_RETRY, "true")
                    .build()
            }

            // Perform challenge-response authentication
            val result = runBlocking {
                authRepository.get().authenticate()
            }

            val session = result.getOrNull() ?: return null

            return response.request.newBuilder()
                .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer ${session.sessionToken}")
                .header(HEADER_AUTH_RETRY, "true")
                .build()
        }
    }
}
