package com.xjtu.toolbox.library

import com.xjtu.toolbox.auth.SessionExpiredException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 图书馆数据层的**会话缝**：`LibraryApi` 只认这三件事 —— 「发一个已登好的请求」、
 * 「拿到本端那份 OkHttpClient」、以及「会话失效时抛哪个异常」。
 *
 * ## 为什么这三件正好是缝的全部
 *
 * `LibraryApi` 搬出 `:app` 之前直接吃着 `SiteSession`（Android 侧的 CAS 会话基类，
 * 6 个文件、几千行，与 `Context` / `CredentialStore` / `WebVPN` 绑在一起）。它真正用到的
 * 只有三处，所以缝就照这三处切，宽度刚好：
 *
 * | 用到的地方 | 缝上的成员 |
 * |---|---|
 * | `site.executeWithReAuth(req)`（每个业务请求） | [fetch] |
 * | `site.client.newCall(…)`（WebVPN 那条 path-cookie 补取） | [client] |
 * | `throw AuthExpiredException("图书馆")`（响应体是登录页） | [authExpired] |
 *
 * ## 为什么「抛哪个异常」也得在缝上，而不是 `:data` 自己定一个
 *
 * 「会话失效」在两端**不是同一个类**：`SiteSession` 抛 `:app` 的 `AuthExpiredException`
 * （它实现 `:core` 的 `SessionExpiredFailure` 标记接口），而 `:app` 里有一批调用点
 * **精确按那个类**分支（`AgentTool` 的图书馆工具、`AppInboxSource`、`HomeStatsRefresher`），
 * 文案与走向都不同。`:data` 如果替它们换一个类型，Android 侧的行为就变了（C1 不允许）。
 *
 * 所以缝上传的是一个**工厂**：谁提供会话，谁决定抛什么。[DirectLibrarySession] 给没有会话内核的
 * 宿主（桌面窗口模式）一份默认实现，`AppLibrarySession` 则原样给回 `AuthExpiredException`
 * —— 即搬迁前那一行 `throw com.xjtu.toolbox.auth.AuthExpiredException("图书馆")`。
 *
 * ## 实现约定
 *
 * - [fetch] **自己负责重认证与重放**（`:app` 那份就是 `SiteSession.executeWithReAuth`）：
 *   `LibraryApi` 不做重试，只负责「响应体看起来是登录页就抛会话失效」。
 * - [fetch] 不做调度器切换：阻塞调用由调用方包 `Dispatchers.IO`（搬迁前的 ViewModel 就是这么包的，
 *   现在由各端的 `LibrarySource` 实现包 —— 见 `LibrarySource` 的 KDoc）。
 */
interface LibrarySession {

    /** 本端的 OkHttp 客户端（共享 cookie jar / UA / 超时拦截器）。 */
    val client: OkHttpClient

    /** 发一个业务请求。命中认证失效时由实现方自己重认证并重放一次；重放仍失效则抛会话失效异常。 */
    suspend fun fetch(request: Request): Response

    /**
     * 构造本端的「会话失效」异常。
     *
     * @param siteName 站点名（图书馆那份一直是 `"图书馆"`，与 `SiteSession.siteName` 一致
     *   —— 异常文案由构造函数拼成「图书馆登录态已失效」）。
     */
    fun authExpired(siteName: String): SessionExpiredException
}

