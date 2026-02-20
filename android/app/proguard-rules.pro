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
