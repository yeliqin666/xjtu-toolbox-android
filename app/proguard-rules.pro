# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── 调试信息 ──────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── OkHttp ────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── OkHttp Brotli ─────────────────────
-keep class okhttp3.brotli.** { *; }
-keep class org.brotli.** { *; }
-dontwarn org.brotli.**

# ── Gson ──────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# 保留被 Gson 反序列化的数据类
-keep class com.xjtu.toolbox.**.* { *; }

# ── Jsoup ─────────────────────────────
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ── Kotlin ────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }

# ── Compose ───────────────────────────
-dontwarn androidx.compose.**

# ── AndroidX Security (EncryptedSharedPreferences) ──
-keep class androidx.security.crypto.** { *; }

# ── 项目数据类（防止 Gson/JSON 解析失败）──
-keepclassmembers class com.xjtu.toolbox.attendance.** { *; }
-keepclassmembers class com.xjtu.toolbox.schedule.** { *; }
-keepclassmembers class com.xjtu.toolbox.score.** { *; }
-keepclassmembers class com.xjtu.toolbox.jwapp.** { *; }
-keepclassmembers class com.xjtu.toolbox.ywtb.** { *; }
-keepclassmembers class com.xjtu.toolbox.library.** { *; }
-keepclassmembers class com.xjtu.toolbox.notification.** { *; }
-keepclassmembers class com.xjtu.toolbox.gmis.** { *; }

# ── Release 版移除 debug/verbose 日志（安全：避免泄露 token/cookie 信息）──
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}