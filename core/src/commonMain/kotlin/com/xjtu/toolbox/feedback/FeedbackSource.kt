package com.xjtu.toolbox.feedback

/**
 * 反馈的**取数端口** —— `:core` 的反馈屏只认它，不认任何具体后端（现在是飞书多维表格，
 * 见 `:data` 的 `FeedbackApi` / `AppFeedbackSource`）。
 *
 * ## 为什么它必须是一条缝
 *
 * 搬迁前反馈屏长在 `:app`：它直接写死 `FeedbackApi`（飞书开放平台 + 打进 APK 的
 * `BuildConfig` 凭据 + `android.util.Log` + `okhttp`）。这条屏要能在桌面端复用，就得把
 * 「怎么问后端」整个让出去 —— 屏只描述它要什么。
 *
 * ⚠️ 反馈**没有校园站点会话**：它不走 `login.xjtu.edu.cn`，也不认识 `SiteSession`
 * （这与其余二十多条真数据路由都不同）。所谓"登录"是客户端拿 `app_id` + `app_secret` 换一张
 * `tenant_access_token`（见 `FeedbackApi.token`），凭据由宿主在启动时注入。所以桌面端这条路由
 * **不套 `DesktopSiteGate`** —— 没有学校会话可建，凭据没配全时整条路由降级成外链。
 *
 * ## 每条方法在两端分别是谁
 *
 * | | Android | 桌面 |
 * |---|---|---|
 * | 凭据从哪来 | `BuildConfig.FEEDBACK_*`（构建期注入，见 `app/build.gradle.kts`） | 同一份 `feedback.properties` / 环境变量，构建期注入（见 `desktop/build.gradle.kts`） |
 * | `机型` / `系统` 字段 | `Build.MANUFACTURER` / `Build.VERSION.RELEASE` | 宿主机 OS（如实填，不假装是手机） |
 * | 实现 | `:data` 的 `AppFeedbackSource` | **同一个** `AppFeedbackSource` |
 */
interface FeedbackSource {

    /**
     * 后端凭据是否配全。false 时反馈页**整块表单隐藏**，只剩 GitHub Issue 外链
     * （见屏的 KDoc）—— 别人 clone 下来照样能编译出可用的包。
     */
    val isConfigured: Boolean

    /**
     * 提交一条反馈。成功返回即完成（**工单号由调用方生成**，不是服务端下发）。
     *
     * [version] 为空取当前版本；崩溃日志是下次启动才补报的，传崩溃时的版本，免得升级后记错。
     */
    suspend fun submit(
        ticket: String,
        category: String,
        content: String,
        contact: String,
        anonId: String,
        version: String? = null,
    )

    /** 拉取这些工单号对应的回复（一次最多认 20 个，见实现里的 `take(20)`）。 */
    suspend fun replies(tickets: List<String>): Map<String, FeedbackReply>

    /** 拉「大家在问」那一栏（没配凭据或字段还没建时返回空表，绝不退回整表拉取）。 */
    suspend fun publicQa(): List<FeedbackPublicQa>
}
