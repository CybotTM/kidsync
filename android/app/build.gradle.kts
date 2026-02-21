import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun signingProp(envVar: String, propKey: String): String? =
    System.getenv(envVar) ?: localProperties.getProperty(propKey)

android {
    namespace = "com.kidsync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kidsync.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    val keystorePath = signingProp("KIDSYNC_KEYSTORE_PATH", "keystore.path")
    val keystorePassword = signingProp("KIDSYNC_KEYSTORE_PASSWORD", "keystore.password")
    val keyAlias = signingProp("KIDSYNC_KEY_ALIAS", "key.alias")
    val keyPassword = signingProp("KIDSYNC_KEY_PASSWORD", "key.password")

    if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // SEC2-A-01: Real certificate pins required for release builds.
            // Set via environment variables or local.properties before building release.
            buildConfigField("String", "CERT_PIN_PRIMARY",
                "\"${signingProp("KIDSYNC_CERT_PIN_PRIMARY", "cert.pin.primary") ?: "PLACEHOLDER"}\"")
            buildConfigField("String", "CERT_PIN_BACKUP",
                "\"${signingProp("KIDSYNC_CERT_PIN_BACKUP", "cert.pin.backup") ?: "PLACEHOLDER"}\"")
        }
        debug {
            isMinifyEnabled = false
            // SEC2-A-01: Placeholder pins for debug builds (pinning is disabled in debug anyway)
            buildConfigField("String", "CERT_PIN_PRIMARY", "\"PLACEHOLDER\"")
            buildConfigField("String", "CERT_PIN_BACKUP", "\"PLACEHOLDER\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Translations are incomplete (German); disable translation lint checks
        disable += setOf("MissingTranslation", "ExtraTranslation")
        // Don't abort build on lint errors -- lint report is still generated and uploaded
        abortOnError = false
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
            }
        }
    }
}

// SEC2-A-22: Dependency versions are managed in gradle/libs.versions.toml.
// Run dependency update checks regularly:
//   ./gradlew dependencyUpdates -Drevision=release
// or use the Renovate/Dependabot bot for automated PRs.
// Key libraries to keep current for security: BouncyCastle, OkHttp, SQLCipher,
// androidx.security:security-crypto, Hilt, Room.
dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room + SQLCipher
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    // Security
    // SEC2-A-12: androidx.security:security-crypto uses alpha-track EncryptedSharedPreferences.
    // No stable release exists as of 2026-02. This is the recommended API for encrypted prefs
    // on Android. Monitor https://developer.android.com/jetpack/androidx/releases/security
    // for a stable release and upgrade when available.
    implementation(libs.androidx.security.crypto)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Crypto
    // SEC2-A-13: Tink dependency removed - it was not actively used for any crypto operations.
    // The app uses BouncyCastle + JCA primitives directly. If Tink is needed in the future,
    // re-add it with a specific use case justification.
    implementation(libs.bouncycastle)

    // QR Code
    implementation(libs.zxing.core)

    // CameraX (QR scanning)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit (barcode scanning)
    implementation(libs.mlkit.barcode.scanning)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
}
