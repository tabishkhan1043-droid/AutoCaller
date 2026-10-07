# Keep Gson model classes
-keep class com.autocaller.app.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp (built-in proguard rules ship with the library; this is a fallback)
-dontwarn okhttp3.**
-dontwarn okio.**
