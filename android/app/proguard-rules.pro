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

# Tink
-keep class com.google.crypto.tink.** { *; }

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

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.internal.platform.** { *; }

# Ktor / WebSocket
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
