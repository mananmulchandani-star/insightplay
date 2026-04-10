# Insight Play ProGuard Rules

# Keep AirPlay library classes
-keep class com.github.serezhka.** { *; }

# Keep Netty classes
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Keep jmDNS
-keep class javax.jmdns.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep SLF4J
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Keep ExoPlayer / Media3
-keep class androidx.media3.** { *; }

# Keep our service and receiver
-keep class com.insightplay.airplay.AirPlayService { *; }
-keep class com.insightplay.airplay.BootReceiver { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Suppress common warnings
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn sun.misc.**
-dontwarn com.google.errorprone.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
