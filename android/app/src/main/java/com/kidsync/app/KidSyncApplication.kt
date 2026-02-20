package com.kidsync.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KidSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tink initialization is handled in CryptoModule
    }
}
