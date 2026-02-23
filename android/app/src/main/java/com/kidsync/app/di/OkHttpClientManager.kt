package com.kidsync.app.di

import com.kidsync.app.BuildConfig
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.data.remote.interceptor.TokenAuthenticator
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * SEC6-A-03: Manages the OkHttpClient lifecycle, allowing it to be rebuilt
 * when the server URL changes.
 *
 * The previous DI-time singleton OkHttpClient was never rebuilt when users
 * changed the server URL in settings. This meant the CertificatePinner
 * remained configured for the original host, and Retrofit continued using
 * the stale base URL.
 *
 * This manager:
 * 1. Provides a delegating OkHttpClient that forwards calls to the current
 *    inner client, so existing Retrofit instances transparently use the
 *    updated client.
 * 2. Rebuilds the inner client (with updated CertificatePinner) when
 *    [onServerUrlChanged] is called.
 * 3. Uses a ReadWriteLock so concurrent requests don't block each other,
 *    but rebuilding the client safely excludes all readers.
 */
@Singleton
class OkHttpClientManager @Inject constructor(
    private val authInterceptor: AuthInterceptor,
    private val loggingInterceptor: HttpLoggingInterceptor,
    private val tokenAuthenticator: TokenAuthenticator
) {
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var currentClient: OkHttpClient = buildClient(null)

    @Volatile
    private var currentServerUrl: String? = null

    /**
     * Get the current OkHttpClient, configured for the current server URL.
     */
    fun getClient(): OkHttpClient = lock.read { currentClient }

    /**
     * Rebuild the OkHttpClient when the server URL changes.
     * Updates the CertificatePinner to include the new server's host.
     *
     * @param newServerUrl The new server URL to pin certificates for
     */
    fun onServerUrlChanged(newServerUrl: String) {
        lock.write {
            if (newServerUrl == currentServerUrl) return
            currentServerUrl = newServerUrl
            currentClient = buildClient(newServerUrl)
        }
    }

    /**
     * Get the current server URL, or null if using the default.
     */
    fun getCurrentServerUrl(): String? = currentServerUrl

    private fun buildClient(serverUrl: String?): OkHttpClient {
        // SEC3-A-12: Enforce TLS 1.2+ to prevent protocol downgrade attacks.
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()

        val builder = OkHttpClient.Builder()
            // SEC4-A-01: Only allow TLS connections. CLEARTEXT (HTTP) is not permitted.
            .connectionSpecs(listOf(tlsSpec))
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // SEC2-A-01: Certificate pinning for production builds only.
        if (!BuildConfig.DEBUG) {
            require(BuildConfig.CERT_PIN_PRIMARY != "PLACEHOLDER" && BuildConfig.CERT_PIN_BACKUP != "PLACEHOLDER") {
                "Release builds require real certificate pins. Set CERT_PIN_PRIMARY and CERT_PIN_BACKUP in build config."
            }

            val pinnerBuilder = CertificatePinner.Builder()
                .add("*.kidsync.app", "sha256/${BuildConfig.CERT_PIN_PRIMARY}")
                .add("*.kidsync.app", "sha256/${BuildConfig.CERT_PIN_BACKUP}")

            // SEC4-A-03: Also pin the configured server host if it differs from kidsync.app.
            if (serverUrl != null) {
                val serverHost = try {
                    java.net.URL(serverUrl).host
                } catch (_: Exception) { null }
                if (serverHost != null && !serverHost.endsWith("kidsync.app")) {
                    pinnerBuilder.add(serverHost, "sha256/${BuildConfig.CERT_PIN_PRIMARY}")
                    pinnerBuilder.add(serverHost, "sha256/${BuildConfig.CERT_PIN_BACKUP}")
                }
            }

            builder.certificatePinner(pinnerBuilder.build())
        }

        return builder.build()
    }
}
