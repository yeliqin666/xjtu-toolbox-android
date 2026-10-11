package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.isNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 电子凭证系统（`dzpz.xjtu.edu.cn` 的工作流引擎）的站点会话。
 *
 * 与 [DzpzLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 14 条真数据路由：
 * 桌面要自己登 `dzpz`、用 `:data` 的 `AppTranscriptSource` 走完成绩单那七步）。类名与包路径
 * 一字未改 ⇒ `:app` 的 `AppLoginState` 里那处 `register(DzpzSession())` 一行不用改。
 *
 * ## 登录入口（如实交代）
 *
 * **它有自己的独立登录页**，不是借别的已搬站点的会话：`DzpzLogin` 走
 * `dzpz.xjtu.edu.cn/login/Login.jsp`（Ecology 先种 `oauth2_redirect_uri` cookie）→ 302 到
 * `login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=new9940` → CAS 登录 → 回调 Login.jsp?code
 * → 换 `access_token` + 下发 `loginidweaver`。所以本类在 `:desktop` 既进 `SESSION_SITE_KEYS`
 * （登录页那一步顺手预热）、也进那条路由的 `DesktopSiteGate`（进门时确保会话）。
 *
 * `mustUseWebVpn = false`：永远直连原域名（与 `:app` 里那一条逐字相同）。
 *
 * 三处覆写与搬迁前逐字一致：
 *  - `onLoginSuccess`：把 `userId`（= `loginidweaver`）写进 `localToken["user_id"]` ——
 *    `TranscriptApi` 的每个请求体都要它；
 *  - `validateLogin`：登录取 `resourceid`（= loginidweaver），匿名访问时该字段缺失。
 *    **不能用 `/api/ecode/sync`** —— 它匿名访问也返回 200 且不跳 CAS，探不出失效。
 *  - `isAuthFailureResponse` 沿用基类（dzpz 那半边没有额外的局部失效信号，见搬迁前的注释）。
 *
 * 搬迁时被替换的写法只有一处：原来用的 `Sites.kt` 里那个私有的 `withIo { }`（它留在那边给
 * 其余站点用）换成本文件里的 `withContext(Dispatchers.IO)` —— 与 `JwxtSession` 那一次同一条口径。
 */
class DzpzSession : CasSiteSession(SITE_KEY, "电子凭证", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        DzpzLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? DzpzLogin)?.userId?.takeIf { it.isNotEmpty() }?.let {
            localToken["user_id"] = it
        }
    }

    /**
     * getOSinfo 登录态下返回 `resourceid`（= loginidweaver），匿名访问时该字段缺失。
     * 不能用 /api/ecode/sync —— 它匿名访问也返回 200 且不跳 CAS，探不出失效。
     */
    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder()
                .url("${DzpzLogin.OS_INFO_URL}?__random__=${System.currentTimeMillis()}")
                .header("Referer", "${DzpzLogin.BASE_URL}/wui/index.html")
                .get().build()
        ).execute()
        try {
            if (resp.code != 200) return@withContext false
            val id = (resp.body.string()).safeParseJsonObject()
                .get("resourceid")?.takeIf { !it.isNull }?.stringValue
                ?.takeIf { it.isNotBlank() && it != "0" } ?: return@withContext false
            localToken["user_id"] = id
            true
        } finally { resp.close() }
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.DZPZ_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "dzpz"
    }
}
