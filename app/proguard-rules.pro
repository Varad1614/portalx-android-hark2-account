# PortalX R8 rules.
# Keep the app's own code intact (small, and it uses enum names as cache keys / reflection-free JSON).
-keep class com.pravahax.portalx.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: only JsonElement APIs are used (no @Serializable classes), but keep the
# standard rules in case models are added later.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pravahax.portalx.**$$serializer { *; }
-dontnote kotlinx.serialization.**

# OkHttp / Okio ship consumer rules; these silence optional TLS providers.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# v0.3.0: strip any android.util.Log call that might ever be added (no logging of session/PII in release builds).
-assumenosideeffects class android.util.Log {
    public static int v(...); public static int d(...); public static int i(...);
    public static int w(...); public static int e(...); public static int wtf(...);
}
# Compose autofill (AutofillNode/AutofillTree) and AndroidX ship consumer rules; nothing here is reflective.
