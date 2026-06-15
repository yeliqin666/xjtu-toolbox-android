package com.xjtu.toolbox.auth

import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder

// ─────────────────────────────────────────────────────────────────────
//  13 个业务子系统的 SiteSession 实现。
//
//  设计原则：
//  - 每个子类内部仍然依赖一个 *Login 实例完成 CAS 登录与局部 token 抽取，
//    这是过渡期复用——XJTULogin 状态机本身已经成熟，无须重写。
//  - 局部 token / 标识在 onLoginSuccess 钩子里写入 SiteSession.localToken 供业务层读取。
//  - 业务 API 类只接 SiteSession，不持有 *Login，便于后续整体替换。
// ─────────────────────────────────────────────────────────────────────

// ── JWXT 教务系统 ─────────────────────────────────────────────────────

class JwxtSession : CasSiteSession("jwxt", "教务系统", supportsWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwxtLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withIo {
        val resp = client.newCall(
            Request.Builder().url(VALIDATE_URL).get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            resp.code == 200 && "login.xjtu.edu.cn" !in finalUrl
        } finally { resp.close() }
    }

    companion object {
        private const val VALIDATE_URL = "https://jwxt.xjtu.edu.cn/api/v2/system/term-info"
    }
}

// ── JWAPP 移动教务系统 ───────────────────────────────────────────────

class JwappSession : CasSiteSession("jwapp", "移动教务", supportsWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwappLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? JwappLogin)?.authToken?.takeIf { it.isNotEmpty() }?.let {
            localToken["auth_token"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["auth_token"]?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    override suspend fun validateLogin(): Boolean = withIo {
        val token = localToken["auth_token"] ?: return@withIo false
        val resp = client.newCall(
            Request.Builder()
                .url("https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time")
                .header("Authorization", "Bearer $token")
                .get().build()
        ).execute()
        try {
            resp.code == 200
        } finally { resp.close() }
    }
}

// ── YWTB 一网通办 ─────────────────────────────────────────────────────

class YwtbSession : CasSiteSession("ywtb", "一网通办", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        YwtbLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? YwtbLogin)?.idToken?.takeIf { it.isNotEmpty() }?.let {
            localToken["id_token"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["id_token"]?.let { builder.header("x-id-token", it) }
        return builder
    }
}

// ── LIBRARY 图书馆座位 ────────────────────────────────────────────────

class LibrarySession : CasSiteSession("library", "图书馆", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LibraryLogin(existingClient = client, visitorId = visitorId)

    override suspend fun validateLogin(): Boolean = withIo {
        val resp = client.newCall(
            Request.Builder().url("http://rg.lib.xjtu.edu.cn:8086/seat/").get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            resp.code in 200..399 && "login.xjtu.edu.cn" !in finalUrl
        } finally { resp.close() }
    }
}

// ── LMS 思源学堂 ─────────────────────────────────────────────────────

class LmsSession : CasSiteSession("lms", "思源学堂", supportsWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.lms.LmsLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

// ── CLASS 课程回放 ────────────────────────────────────────────────────

class ClassSession : CasSiteSession("class", "课程回放", supportsWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.classreplay.ClassLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

// ── JIAOCAI 教材中心 ──────────────────────────────────────────────────

class JiaocaiSession : CasSiteSession("jiaocai", "教材中心", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.jiaocai.JiaocaiLogin(existingClient = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? com.xjtu.toolbox.jiaocai.JiaocaiLogin)?.enc?.takeIf { it.isNotEmpty() }?.let {
            localToken["enc"] = it
        }
    }
}

// ── COUPON 餐券 ──────────────────────────────────────────────────────

class CouponSession : CasSiteSession("coupon", "餐券系统", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        CouponLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

class SuperAppSession : CasSiteSession("super_app", "移动交大", supportsWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        SuperAppLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withIo {
        val response = client.newCall(
            Request.Builder().url(SuperAppLogin.HOME_URL).get().build()
        ).execute()
        try {
            response.code == 200 && "login.xjtu.edu.cn" !in response.request.url.host
        } finally {
            response.close()
        }
    }
}

class FitnessSession : CasSiteSession("fitness", "体测查询", supportsWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.fitness.FitnessLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey
        )
}

// ── DZPZ 电子凭证（成绩单） ───────────────────────────────────────────

class DzpzSession : CasSiteSession("dzpz", "电子凭证", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        DzpzLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? DzpzLogin)?.userId?.takeIf { it.isNotEmpty() }?.let {
            localToken["user_id"] = it
        }
    }
}

// ── VENUE 场馆预订 ────────────────────────────────────────────────────

class VenueSession : CasSiteSession("venue", "场馆预订", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        VenueLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

// ── GMIS 研究生管理 ──────────────────────────────────────────────────

class GmisSession : CasSiteSession("gmis", "研究生管理", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        GmisLogin(session = client, visitorId = visitorId)
}

// ── GSTE 研究生评教 ──────────────────────────────────────────────────

class GsteSession : CasSiteSession("gste", "研究生评教", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        GsteLogin(session = client, visitorId = visitorId)
}

// ── ATTENDANCE 考勤系统（本科 / 研究生） ──────────────────────────────

/**
 * 考勤系统会话。登录后将 Synjones-Auth token 写入 [localToken]，业务请求自动注入 header。
 * 通过 [isPostgraduate] 区分本科（bkkq）/ 研究生（yjskq）。
 */
class AttendanceSession(
    private val isPostgraduate: Boolean,
) : CasSiteSession(
    siteKey = if (isPostgraduate) "pg_attendance" else "attendance",
    siteName = if (isPostgraduate) "研究生考勤" else "本科考勤",
    supportsWebVpn = true,
) {
    val attendanceDomain: String
        get() = if (isPostgraduate) "yjskq.xjtu.edu.cn" else "bkkq.xjtu.edu.cn"

    override val accountType: XJTULogin.AccountType
        get() = if (isPostgraduate) XJTULogin.AccountType.POSTGRADUATE
                else XJTULogin.AccountType.UNDERGRADUATE

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin {
        val useWebVpn = currentAccessMode == AccessMode.WEBVPN
        return AttendanceLogin(
            session = client,
            visitorId = visitorId,
            useWebVpn = useWebVpn,
            isPostgraduate = isPostgraduate,
        )
    }

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? AttendanceLogin)?.authToken?.takeIf { it.isNotEmpty() }?.let {
            localToken["synjones_auth"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["synjones_auth"]?.let { builder.header("Synjones-Auth", "bearer $it") }
        return builder
    }

    override suspend fun validateLogin(): Boolean = withIo {
        val token = localToken["synjones_auth"] ?: return@withIo false
        val url = "https://$attendanceDomain/attendance-student/global/getStuInfo"
        val resp = client.newCall(
            Request.Builder()
                .url(url)
                .header("Synjones-Auth", "bearer $token")
                .post("".toRequestBody(null))
                .build()
        ).execute()
        try {
            if (resp.code != 200) return@withIo false
            val body = resp.body?.string() ?: return@withIo false
            if (XJTULogin.isAuthFailureResponse(body)) return@withIo false
            body.safeParseJsonObject().get("success")?.asBoolean == true
        } finally { resp.close() }
    }
}

// ── CAMPUS CARD 校园卡 ───────────────────────────────────────────────

/**
 * 校园卡会话。流程独立于标准 CAS：访问入口 → org.xjtu.edu.cn → login.xjtu.edu.cn → ticket → JWT。
 * 用 CasSiteSession 套壳——XJTULogin 状态机仍负责走完 CAS 部分，[CampusCardLogin.postLogin] 接管 ticket 兑换。
 */
class CampusCardSession : CasSiteSession("campus_card", "校园卡", supportsWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        CampusCardLogin(existingClient = client, visitorId = visitorId)

    override fun onLoginSuccess(login: XJTULogin) {
        val cc = login as? CampusCardLogin ?: return
        cc.accessToken?.let { localToken["access_token"] = it }
        cc.cardAccount?.let { localToken["card_account"] = it }
        if (cc.userName.isNotEmpty()) localToken["user_name"] = cc.userName
        if (cc.studentNo.isNotEmpty()) localToken["student_no"] = cc.studentNo
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["access_token"]?.let { builder.header("Synjones-Auth", "bearer $it") }
        return builder
    }
}

// ─────────────────────────────────────────────────────────────────────
//  辅助
// ─────────────────────────────────────────────────────────────────────

private suspend inline fun <T> withIo(crossinline block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
