package com.kidsync.app.di

import android.content.SharedPreferences
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.data.remote.interceptor.TokenAuthenticator
import com.kidsync.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
    fun provideOkHttpClientManager(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClientManager {
        return OkHttpClientManager(authInterceptor, loggingInterceptor, tokenAuthenticator)
    }

    /**
     * SEC6-A-03: The OkHttpClient is now managed by [OkHttpClientManager], which
     * rebuilds it (including CertificatePinner) when the server URL changes.
     * This provider returns the current client from the manager.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        clientManager: OkHttpClientManager
    ): OkHttpClient {
        return clientManager.getClient()
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
