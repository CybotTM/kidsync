package com.kidsync.app.di

import android.content.SharedPreferences
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        @Named("encrypted_prefs") prefs: SharedPreferences
    ): AuthInterceptor {
        return AuthInterceptor(prefs)
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        // SEC-A-05: Use BASIC level (not HEADERS) to avoid leaking auth headers in logs
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        // SEC3-A-12: Enforce TLS 1.2+ to prevent protocol downgrade attacks.
        // This ConnectionSpec restricts to modern TLS versions and strong cipher suites.
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()

        val builder = OkHttpClient.Builder()
            .connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // SEC2-A-01: Certificate pinning for production builds only.
        // In debug builds, pinning is disabled to allow proxy/MITM debugging.
        // For release builds, real pins MUST be set in BuildConfig before shipping.
        // TODO: Derive real pins before release using:
        //   openssl s_client -connect api.kidsync.app:443 | openssl x509 -pubkey -noout | \
        //     openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
        if (!BuildConfig.DEBUG) {
            require(BuildConfig.CERT_PIN_PRIMARY != "PLACEHOLDER" && BuildConfig.CERT_PIN_BACKUP != "PLACEHOLDER") {
                "Release builds require real certificate pins. Set CERT_PIN_PRIMARY and CERT_PIN_BACKUP in build config."
            }
            val certificatePinner = CertificatePinner.Builder()
                .add("api.kidsync.app", "sha256/${BuildConfig.CERT_PIN_PRIMARY}")
                .add("api.kidsync.app", "sha256/${BuildConfig.CERT_PIN_BACKUP}")
                .build()
            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
        @Named("baseUrl") baseUrl: String
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
