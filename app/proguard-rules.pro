# Project-specific ProGuard/R8 rules. Minification is disabled for the v1 release
# build, but these rules keep a future isMinifyEnabled=true switch safe.

# OkHttp references optional TLS providers via reflection; they are not bundled.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep the CallScreeningService entry point resolvable by the platform.
-keep class com.pcdeni.aicallerid.screening.CallScreenerService { *; }
