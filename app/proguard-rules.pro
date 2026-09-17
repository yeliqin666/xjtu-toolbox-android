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
# 不再整包 keep：okhttp 4.12.0 自带 META-INF/proguard/okhttp3.pro，PublicSuffixDatabase
# 那条 keepnames 也是逐字重复。-dontwarn 留着（缺失的可选依赖类否则会让 R8 直接构建失败）。
-dontwarn okhttp3.**
-dontwarn okio.**

# ── OkHttp Brotli ─────────────────────
-keep class okhttp3.brotli.** { *; }
-keep class org.brotli.** { *; }
-dontwarn org.brotli.**

# ── Gson ──────────────────────────────
# 不再整包 keep：gson 2.11.0 自带 META-INF/proguard/gson.pro，已覆盖 Signature/注解、
# TypeAdapterFactory/JsonSerializer/JsonDeserializer 无参构造、@SerializedName 字段、
# 以及反射用到的 TypeToken 匿名子类。app 这边原来的 `-keep class com.google.gson.**`
# 反而会阻止 Gson 自身被裁剪。

# ── Jsoup ─────────────────────────────
# 没有自带 proguard 规则，但代码里也没有对它按名反射，keep 可以去掉，dontwarn 留着。
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
# composite build 源码依赖，没有按类名反射（唯一用 KClass 当 map key 的 miuix-nav
# 本项目未依赖），keep 去掉，dontwarn 留着。
-dontwarn top.yukonga.miuix.**

# ── AndroidX Security (EncryptedSharedPreferences) ──
# 保留整包 keep，不要动：AccountStore 在 Tink/security-crypto 初始化失败时会清掉
# 本地保存的全部账号（shared_prefs 下 xjtu_accounts* 文件），这条规则出一点问题
# 后果是用户账号被清空，而这个库只有十来个类，删了也省不出体积，不值当冒这个险。
-keep class androidx.security.crypto.** { *; }

# ── 项目数据类：Gson 读写字段名/枚举常量名不能被 R8 改 ──
#
# cacheDir 下 DataCache 缓存的模型类（HomeStat/TermScore/ReportedGrade/HelloProfile/
# YwtbIdentity/CourseItem/ExamItem/TextbookItem/Jiaocai1Category/YellowPageData 等）
# 不在这里：那些本来就有 TTL，系统随时可能清掉，代码必须能处理缓存缺失，R8 改名只要
# 同一次编译内部自洽（写的时候和读的时候用的是同一份 mapping）就没问题，不用额外保护。
#
# 真正的外部协议字段（服务器 JSON key 名）用 @SerializedName 锁在数据类定义处，
# 见 YellowPageCategory/YellowPageDepartment（yellowpage/YellowPageApi.kt）、
# SliderResult/TrackPoint（venue/SliderCaptcha.kt）——注解本身就会被 gson.pro 保护，
# 这里不用重复写规则。
#
# 剩下这批是 filesDir/SharedPreferences/EncryptedSharedPreferences 里"只有这一份"的
# 持久状态：服务器没有副本，丢了不会在下次联网时自愈（账号要重登，会话记录直接消失）。
-keepclassmembers class com.xjtu.toolbox.account.Account { <fields>; }
-keep class com.xjtu.toolbox.auth.AccountType { *; }

-keepclassmembers class com.xjtu.toolbox.agent.AgentSession { <fields>; }
-keepclassmembers class com.xjtu.toolbox.agent.StoredConversation { <fields>; }
-keepclassmembers class com.xjtu.toolbox.agent.StoredMessage { <fields>; }
-keepclassmembers class com.xjtu.toolbox.agent.StoredWidget { <fields>; }
# 这 7 个 Widget 子类的类名本身就是判别式（AgentWidget.kt 用 javaClass.simpleName 存、
# 字面量 when 分支读回），字段名和类名都不能改；类都很小，整类原样保留的体积代价可忽略。
-keep class com.xjtu.toolbox.agent.ScheduleWidget { *; }
-keep class com.xjtu.toolbox.agent.ExamWidget { *; }
-keep class com.xjtu.toolbox.agent.RoomWidget { *; }
-keep class com.xjtu.toolbox.agent.AttendanceWidget { *; }
-keep class com.xjtu.toolbox.agent.GradeWidget { *; }
-keep class com.xjtu.toolbox.agent.CardWidget { *; }
-keep class com.xjtu.toolbox.agent.ZyxfWidget { *; }
-keepclassmembers class com.xjtu.toolbox.agent.AgentToolRegistry$YwtbIdentity { <fields>; }

-keepclassmembers class com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiSession { <fields>; }
-keepclassmembers class com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiConversation { <fields>; }
-keepclassmembers class com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiMessage { <fields>; }

-keepclassmembers class com.xjtu.toolbox.card.CampusCardSnapshot { <fields>; }
-keepclassmembers class com.xjtu.toolbox.card.CardInfo { <fields>; }
-keepclassmembers class com.xjtu.toolbox.card.Transaction { <fields>; }

-keepclassmembers class com.xjtu.toolbox.attendance.AttendanceRecordStore$Shard { <fields>; }
-keepclassmembers class com.xjtu.toolbox.attendance.AttendanceSnapshot { <fields>; }
-keepclassmembers class com.xjtu.toolbox.attendance.AttendanceWaterRecord { <fields>; }
-keepclassmembers class com.xjtu.toolbox.attendance.TermInfo { <fields>; }
-keepclassmembers class com.xjtu.toolbox.attendance.CourseAttendanceStat { <fields>; }
-keep class com.xjtu.toolbox.attendance.WaterType { *; }

-keepclassmembers class com.xjtu.toolbox.schedule.ScheduleChangeEvent { <fields>; }
-keep class com.xjtu.toolbox.schedule.ScheduleChangeEvent$Kind { *; }

# ScoreItem 本身挂在 cacheDir 缓存的 TermScore 里（可以不管），但它的 source 字段
# 是非空枚举——Gson 靠反射走 Class.getEnumConstants()，R8 的枚举优化会把这条路断掉，
# 断了不是"值回落默认"而是直接 NPE/崩溃，跟"丢了能自愈"的缓存类不是一回事，单独锁住。
# courseGroup 字段是可空的，丢了顶多变 null，不需要额外保护。
-keep class com.xjtu.toolbox.jwapp.ScoreSource { *; }

# ── Release 版移除 debug/verbose 日志（安全：避免泄露 token/cookie 信息）──
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}