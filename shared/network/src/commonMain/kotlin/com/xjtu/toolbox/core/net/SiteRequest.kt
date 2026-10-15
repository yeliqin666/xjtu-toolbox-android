package com.xjtu.toolbox.core.net

import io.ktor.client.request.HttpRequestBuilder

/** 传输缝：业务 API 类需要从「某个站点的会话」拿到的那几件事，一个 okhttp 类型都不出现。 */
interface SiteRequest {
    /** 站点标识（`jwxt` / `library` / `ssn`……）。缓存键、诊断、日志都用它。 */
    val siteKey: String

    /** 站点中文名，用于拼面向用户的错误文案。 */
    val siteName: String

    /** 本站点局部令牌（登录时由站点会话写入：`auth_token` / `cid` / `business_token` / `kq_base_url`……）。 */
    val localToken: Map<String, String>

    /** Ktor 形状的业务请求出口 = 「发一次 → 判认证失效 → 重登 → 原样重放一次」。 */
    suspend fun sendWithReAuth(block: HttpRequestBuilder.() -> Unit): KtorReply
}
