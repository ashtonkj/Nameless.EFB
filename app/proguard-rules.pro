# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools proguard/proguard-android.txt

# Room — preserve entity and DAO class names
-keep class com.nameless.efb.data.db.entity.** { *; }
-keep class com.nameless.efb.data.db.dao.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.nameless.efb.** {
    kotlinx.serialization.KSerializer serializer(...);
}
