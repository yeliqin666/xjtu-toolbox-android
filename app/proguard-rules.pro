# ── 调试信息 ──────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── OkHttp ────────────────────────────
# OkHttp 自带 consumer 规则；-dontwarn 防止缺失的可选依赖让 R8 失败
-dontwarn okhttp3.**
-dontwarn okio.**

# ── OkHttp Brotli ─────────────────────
-keep class okhttp3.brotli.** { *; }
-keep class org.brotli.** { *; }
-dontwarn org.brotli.**

# ── Jsoup ─────────────────────────────
# 不按名反射，无需 keep
-dontwarn org.jsoup.**

# ── flexmark HTML→Markdown（web_fetch，对应 smolagents markdownify）──
-dontwarn com.vladsch.flexmark.**

# ── Kotlin ────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }

# ── Compose ───────────────────────────
-dontwarn androidx.compose.**

# ── Miuix UI Library ──────────────────
# 不按名反射，无需 keep
-dontwarn top.yukonga.miuix.**

# ── AndroidX Security (EncryptedSharedPreferences) ──
# 保留整包 keep，不要动：AccountStore 在 Tink/security-crypto 初始化失败时会清掉
# 本地保存的全部账号（shared_prefs 下 xjtu_accounts* 文件），这条规则出一点问题
# 后果是用户账号被清空，而这个库只有十来个类，删了也省不出体积，不值当冒这个险。
-keep class androidx.security.crypto.** { *; }

# ── Release 版移除 debug/verbose 日志（安全：避免泄露 token/cookie 信息）──
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ── miuix-nav 返回栈（nav/AppRoute.kt）──
# 返回栈用 kotlinx.serialization 存进 rememberSaveable，切后台时序列化、进程被杀后读回来。
# 库自带的规则覆盖了常规写法，这里再显式留住整个路由层级和生成的序列化器当保险：
# 预览包不混淆，这类问题只会在正式版上才暴露。
-keep class com.xjtu.toolbox.nav.AppRoute { *; }
-keep class com.xjtu.toolbox.nav.AppRoute$* { *; }
