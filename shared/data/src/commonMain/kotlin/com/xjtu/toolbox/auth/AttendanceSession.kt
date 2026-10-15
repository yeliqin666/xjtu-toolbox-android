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

/** 考勤（`kq.xjtu.edu.cn`）的站点会话。 */
class AttendanceSession : CasSiteSession("new_attendance", "考勤", mustUseWebVpn = true) {

    /** 当前账号所属的考勤站点根地址（本科 bk-kq / 研究生 yjs-kq），登录成功时写入。原始域名，不含网关。 */
    fun baseUrl(): String =
        localToken[BASE_URL_KEY] ?: AttendanceLogin.BASE_URL

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        AttendanceLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey,
            // 账号类型已知就直接登对应业务站省掉门户三次往返；直连失败 postLogin 会退回门户流程。
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
        // 探活走 KqHttp.buildUrl：校外要经 WebVPN 改写地址，直连必然超时；顺带补 X-System 头。
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