package com.xjtu.toolbox.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 移动教务（`jwapp.xjtu.edu.cn`）的站点会话。
 *
 * 与 [JwappLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 19 条真数据路由：
 * 桌面要自己登移动教务、自己取学期成绩与分项成绩）。类名与包路径一字未改 ⇒ `:app` 的
 * `AppLoginState` / `HeadlessSessions` 里那两处 `register(JwappSession())` 一行不用改。
 *
 * ## 登录形态（如实交代）
 *
 * **它有自己的独立登录链，但借的是统一认证的开放平台那一跳**（部分独立）：
 *
 * ```
 * GET  org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index
 *      → 302 到统一认证 login.xjtu.edu.cn（CAS，RSA 加密密码 / TGC 都在那里）
 *      → 302 回 http://jwapp.xjtu.edu.cn/app/index?token=<业务令牌>
 * ```
 *
 * 与场馆（`appId=1659`）/ 加餐券（`client_id=1596`）/ 宿舍电费（`appId=1759`）同一套形状，
 * 差别只有 `appId`、`redirectUri`，以及**令牌是从回调 URL 里抠出来的**（`?token=`，不是页面
 * 里内嵌的 `cid`，也不需要换票）：登录成功后由 `onLoginSuccess` 存进 `localToken`，
 * 之后每个业务请求由 [decorateRequest] 带上 `Authorization: <token>`。
 *
 * `mustUseWebVpn = false`：永远直连原域名（搬迁前 `Sites.kt` 里就是这么写的，注释原文：
 * 「CAS 入口走 org.xjtu.edu.cn 开放平台，若该入口与 jwapp.xjtu.edu.cn 本身对公网开放，校外可直连」）。
 *
 * ⚠️ 成绩页的**第二条上游是教务**（`jwxt`）：`CjcxApi` / `JudgeApi` / `ScoreReportApi` 走的是
 * [JwxtSession]，不是本站点 —— 两个站点的会话各自独立（见 `:data` 的 `AppJwappScoreSource`）。
 *
 * 搬迁时被替换的写法只有一处：原来用的 `Sites.kt` 里那个私有的 `withIo { }`（它留在那边给其余
 * 站点用）换成本文件里的 `withContext(Dispatchers.IO)` —— 与 `AttendanceSession` / `DzpzSession` /
 * `JwxtSession` 同一条口径。
 */
class JwappSession : CasSiteSession("jwapp", "移动教务", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwappLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? JwappLogin)?.authToken?.takeIf { it.isNotEmpty() }?.let {
            localToken["auth_token"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["auth_token"]?.let { builder.header("Authorization", it) }
        return builder
    }

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val token = localToken["auth_token"] ?: return@withContext false
        val resp = client.newCall(
            Request.Builder()
                .url("https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time")
                .header("Authorization", token)
                .get().build()
        ).execute()
        try {
            resp.code == 200
        } finally { resp.close() }
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        // 分项成绩接口对「这门课没有细则」也会给 JSON code=401，不能单凭数字当掉登录。
        return body.contains("Authentication error", ignoreCase = true) ||
            (body.contains("token", ignoreCase = true) && body.contains("过期")) ||
            (""""code"\s*:\s*401""".toRegex().containsMatchIn(body) &&
                (body.contains("authentication", ignoreCase = true) ||
                    body.contains("未登录") ||
                    body.contains("过期")))
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.JWAPP_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "jwapp"
    }
}
