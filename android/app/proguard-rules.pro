# KidSync ProGuard Rules

# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable annotated classes
-keep,includedescriptorclasses class com.kidsync.app.**$$serializer { *; }
-keepclassmembers class com.kidsync.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.kidsync.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher
-keep class net.zetetic.database.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class com.kidsync.app.data.remote.dto.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep,allowobfuscation,allowshrinking @dagger.hilt.EntryPoint interface * { *; }

# SEC-A-11: BouncyCastle crypto (used for Ed25519, HKDF, X25519 conversions)
-keep class org.bouncycastle.** { *; }
-keepnames class org.bouncycastle.** { *; }
-keep class com.kidsync.app.crypto.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
# SEC3-A-24: BouncyCastle dontwarn is required because OkHttp references BouncyCastle's
# org.bouncycastle.jsse classes for TLS provider detection (BouncyCastleJsseProvider),
# which are not included in our bcprov-jdk18on dependency (they are in bctls-jdk18on).
# OkHttp gracefully falls back to the platform TLS provider when these classes are absent.
# This is safe because we use BouncyCastle only for Ed25519/X25519 primitives, not TLS.
# Specifically suppressed packages: jsse (TLS provider), est (certificate enrollment),
# and other optional modules referenced by OkHttp platform detection.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.est.**
-dontwarn org.openjsse.**
-keep class okhttp3.internal.platform.** { *; }

# SEC5-A-10: Strip non-error log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Ktor / WebSocket
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
