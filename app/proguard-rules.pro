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

# ── 移除 debug/verbose 日志（安全：避免泄露 token/cookie 信息）──
# release 与 preview 共用：预览版与正式版用同一套 R8 规则。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ── miuix-nav 返回栈（nav/AppRoute.kt）──
# 返回栈用 kotlinx.serialization 存进 rememberSaveable，切后台时序列化、进程被杀后读回来。
# 库自带的规则覆盖了常规写法，这里再显式留住整个路由层级和生成的序列化器当保险：
# 预览版与正式版现在用同一套 R8 规则，这类问题能在预览阶段就提前暴露。
-keep class com.xjtu.toolbox.nav.AppRoute { *; }
-keep class com.xjtu.toolbox.nav.AppRoute$* { *; }

# ── 插件 API（plugin/api）──
# 插件 APK 单独编译，按原类名引用这些接口；宿主 R8 改了名，插件就找不到宿主。
# 真正的契约在 plugin/api/consumer-rules.pro；这里显式再写一条，避免 consumer 规则没生效。
-keep class com.xjtu.toolbox.plugin.api.** { *; }

# ── 插件：共享 lifecycle / savedstate ──
# 插件 ComposeView 用父加载器的 LifecycleOwner / SavedStateRegistryOwner / R.id，
# 这些类被 R8 改名后插件按原类名就找不到了；它们体量不大，整包保留。
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }

# 插件代码和宿主的 lifecycle 一起用宿主 Kotlin stdlib；R8 改名后插件按原类名找不到。
-keep class kotlin.** { *; }
