package com.xjtu.toolbox.auth

import com.xjtu.toolbox.attendance.AttendanceLogin
import com.xjtu.toolbox.attendance.KqHttp
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 考勤（`kq.xjtu.edu.cn`）的站点会话。
 *
 * 与 [AttendanceLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 16 条真数据
 * 路由：桌面要自己登 kq、自己取考勤流水 / 请假 / 课表）。类名与包路径一字未改 ⇒ `:app` 的
 * `AppLoginState` / `HeadlessSessions` 里那两处 `register(AttendanceSession())` 一行不用改。
 *
 * ## 登录形态（如实交代）
 *
 * **它有自己的独立登录入口**（`AttendanceLogin`：`/sa/auth/cas/login/pc` → 统一认证 → 回调带
 * `loginRequestId`+`ticket` 换业务令牌，三域名部署 bk-kq / yjs-kq / 门户），不借校园卡 / 教务的
 * 会话。`mustUseWebVpn = true`：考勤这几个域名只在校内网络可达，校外直连 443 连超时都不给
 *（卡满 12 秒）。
 *
 * 四处覆写与搬迁前逐字一致：
 *  - `baseUrl()`：本科 / 研究生两套部署的根地址（`localToken["kq_base_url"]`，登录时写入）；
 *  - `onLoginSuccess`：把业务令牌与解析出的站点根地址写进 `localToken`；
 *  - `decorateRequest`：每个业务请求带 `X-Business-Token` + `X-System`（网页端同款头）；
 *  - `isAuthFailureResponse`：`code=4001` 或「业务令牌 … 过期/无效/未登录」都算会话失效
 *    （与 `KqHttp.execute` 的兜底同一套信号）；
 *  - `validateLogin`：带令牌探活 `/student/home`，走与正常业务请求相同的 `KqHttp.buildUrl`。
 *
 * 搬迁时被替换的写法只有一处：原来用的 `Sites.kt` 里那个私有的 `withIo { }`（它留在那边给其余站点用）
 * 换成本文件里的 `withContext(Dispatchers.IO)` —— 与 `DzpzSession` / `JwxtSession` / `SsnSession`
 * 同一条口径。`BASE_URL_KEY` 常量跟着类一起搬（它只被本类用）。
 */
class AttendanceSession : CasSiteSession("new_attendance", "考勤", mustUseWebVpn = true) {

    /** 当前账号所属的考勤站点根地址（本科 bk-kq / 研究生 yjs-kq），登录成功时写入。原始域名，不含网关。 */
    fun baseUrl(): String =
        localToken[BASE_URL_KEY] ?: AttendanceLogin.BASE_URL

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        AttendanceLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey,
            // 账号类型来自一网通办身份判断（见 AccountType.fromIdentityName），跟
            // ScheduleSourceRouter 挑 kq 部署用的是同一个信号。已知的话直接登对应
            // 业务站，省掉门户那三次往返；AttendanceLogin.postLogin 里若直连失败
            // 会自动退回门户流程，不会因为猜错身份就登不上。
            knownAccountType = accountType,
        )

    override fun onLoginSuccess(login: XJTULogin) {
        val kq = login as? AttendanceLogin
        val token = kq?.authToken
        if (!token.isNullOrBlank()) localToken["business_token"] = token
        // 本科与研究生是两套部署（bk-kq / yjs-kq），业务请求必须打到签发令牌的那一套。
        kq?.resolvedBaseUrl?.let { localToken[BASE_URL_KEY] = it }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["business_token"]?.let { builder.header(AttendanceLogin.TOKEN_HEADER, it) }
        // 网页端每个业务请求都带这一条，跟着带上，免得日后服务端开始校验。
        builder.header(
            AttendanceLogin.SYSTEM_HEADER,
            AttendanceLogin.SYSTEM_VALUE,
        )
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val preview = bodyPreview ?: return false
        if (Regex("\"code\"\\s*:\\s*4001").containsMatchIn(preview)) return true
        return preview.contains("业务令牌") && (
            preview.contains("过期") || preview.contains("无效") || preview.contains("未登录")
        )
    }

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val token = localToken["business_token"] ?: return@withContext false
        // 走跟正常业务请求一样的 KqHttp.buildUrl：校外经 WebVPN 网关改写地址，
        // 直连域名探活必然超时；同时补上网页端每个请求都带的 X-System 头，
        // 免得服务端哪天开始校验就把探活单独漏掉。
        val resp = client.newCall(
            Request.Builder()
                .url(KqHttp.buildUrl(this@AttendanceSession, "/student/home"))
                .header(AttendanceLogin.TOKEN_HEADER, token)
                .header(
                    AttendanceLogin.SYSTEM_HEADER,
                    AttendanceLogin.SYSTEM_VALUE,
                )
                .get()
                .build()
        ).execute()
        try {
            if (resp.code != 200) return@withContext false
            val body = resp.body.string()
            if (XJTULogin.isAuthFailureResponse(body)) return@withContext false
            body.safeParseJsonObject().get("code")?.takeIf { !it.isNull }?.intValue == 0
        } finally {
            resp.close()
        }
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.ATTENDANCE_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "new_attendance"

        /** [AttendanceSession.localToken] 里存考勤站点根地址的键。 */
        const val BASE_URL_KEY = "kq_base_url"
    }
}