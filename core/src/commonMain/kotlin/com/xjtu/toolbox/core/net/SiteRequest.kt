package com.xjtu.toolbox.core.net

import io.ktor.client.request.HttpRequestBuilder

/**
 * **传输缝**：业务 API 类需要从「某个站点的会话」拿到的那几件事，一个 okhttp 类型都不出现。
 *
 * ## 为什么需要这个接口
 *
 * `:app` 里 60 处 `SiteSession.executeWithReAuth(Request): Response` 的调用点是「44 屏还搬不动」
 * 的**主根因**：`okhttp3.Request/Response` 在 wasm/iOS 上不存在，所以每一个拿到 `SiteSession`
 * 的 API 类（`DormPowerApi`、`LibraryApi`、`JwappApi`……）都被钉在 Android 侧，连带它们的
 * ViewModel 与屏。
 *
 * 而 `SiteSession` 的**实现**（CAS 状态机、WebVPN、带持久 cookie jar 的 OkHttpClient、
 * `SessionManager` 的换绑/快照）短期内不该搬进 `:core` —— 它要 `Context`、要 okhttp。
 *
 * 所以缝切在**能力**上，而不是文件上：
 *
 * - Android：`SiteSession`（`com.xjtu.toolbox.auth`）实现本接口，[sendWithReAuth] 直接委派给自己的
 *   Ktor 形状出口（同一个 OkHttpClient / cookie jar / UA 拦截器 / WebVPN 改写，行为零变化）；
 * - Web：`WebSiteRequest`（`web/` 里那个）用 Ktor 的 JS 引擎 + campus-api 同源反代实现同一套语义。
 *
 * 于是「业务 API 类 → 共享层」这一步就与「会话内核 → 共享层」解耦了：可以先搬 API，
 * 内核慢慢来。
 *
 * ## 迁移一个站点的两件事（缺一不可）
 *
 * 1. 把 `Sites.kt` 里该站点的两个重写对应到 Ktor 版本：
 *    [com.xjtu.toolbox.auth.SiteSession.decorateRequest] → `decorateKtorRequest`，
 *    `isAuthFailureResponse` → `isAuthFailureReply`。**漏掉第一个的后果是请求少了本站特有 header**
 *    （`Authorization` / `x-id-token` / `Synjones-Auth`……），服务端回 401，表现成「登录态莫名失效」。
 * 2. 把该站点的 API 类与调用点从 `executeWithReAuth(Request)` 换成 [sendWithReAuth]（Ktor 形状）。
 */
interface SiteRequest {
    /** 站点标识（`jwxt` / `library` / `ssn`……）。缓存键、诊断、日志都用它。 */
    val siteKey: String

    /** 站点中文名，用于拼面向用户的错误文案。 */
    val siteName: String

    /**
     * 本站点局部令牌（登录时由站点会话写入：`auth_token` / `cid` / `business_token` / `kq_base_url`……）。
     *
     * 只读视图：实现方（`SiteSession`）内部仍是可变 map。API 类**只该读**，注入请求头一律走
     * 会话的 decorate 钩子（重放时才能用上新令牌）。
     */
    val localToken: Map<String, String>

    /**
     * **Ktor 形状的业务请求出口** = 「发一次 → 判认证失效 → 重登 → 原样重放一次」。
     *
     * 与 `SiteSession.executeWithReAuth` 的语义逐条对齐（重放循环在 [ReAuthCall] 里，有单测钉着）。
     */
    suspend fun sendWithReAuth(block: HttpRequestBuilder.() -> Unit): KtorReply
}
