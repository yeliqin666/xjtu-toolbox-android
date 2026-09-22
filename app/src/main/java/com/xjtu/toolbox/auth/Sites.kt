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

// mustUseWebVpn=false：永远直连原域名。护网结束后 jwxt 已放开公网直连，校外通常也可用，
// 但这是学校当前网络策略决定的，不是本字段保证的行为——若域名被重新收紧仅限校内，
// 校外需连接校园官方 VPN 或回到校园网，App 内置 WebVPN 代理对本站点不生效。
class JwxtSession : CasSiteSession("jwxt", "教务系统", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwxtLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withIo {
        val resp = client.newCall(
            Request.Builder().url(VALIDATE_URL).get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            // WebVPN 下被踢回 CAS 时 URL 是 webvpn.xjtu.edu.cn/https/{加密login域名}/cas/login…，
            // 明文 "login.xjtu.edu.cn" 不出现，`!in` 反而成立 → 失效会话被误判为"仍然有效"，
            // 于是跳过重登，后续接口拿到的是登录页。isAtTargetSite 兼容直连/WebVPN 两种模式。
            resp.code == 200 && com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(finalUrl, "jwxt.xjtu.edu.cn")
        } finally { resp.close() }
    }

    companion object {
        private const val VALIDATE_URL = "https://jwxt.xjtu.edu.cn/api/v2/system/term-info"
    }
}

// ── JWAPP 移动教务系统 ───────────────────────────────────────────────

// mustUseWebVpn=false：永远直连原域名。CAS 入口走 org.xjtu.edu.cn 开放平台，若该入口
// 与 jwapp.xjtu.edu.cn 本身对公网开放，校外可直连；若学校收紧访问，需连校园官方 VPN。
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

    override suspend fun validateLogin(): Boolean = withIo {
        val token = localToken["auth_token"] ?: return@withIo false
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
}

// ── YWTB 一网通办 ─────────────────────────────────────────────────────

class YwtbSession : CasSiteSession("ywtb", "一网通办", mustUseWebVpn = true) {
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

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            body.contains("未登录") || body.contains("登录过期")
    }
}

// ── LIBRARY 图书馆座位 ────────────────────────────────────────────────

class LibrarySession : CasSiteSession("library", "图书馆", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LibraryLogin(existingClient = client, visitorId = visitorId)

    override suspend fun validateLogin(): Boolean = withIo {
        val resp = client.newCall(
            Request.Builder().url("http://rg.lib.xjtu.edu.cn:8086/seat/").get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            // 同 JwxtSession：WebVPN 下明文域名判断会把失效会话误判为有效。
            resp.code in 200..399 &&
                com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(finalUrl, "rg.lib.xjtu.edu.cn")
        } finally { resp.close() }
    }
}

// ── LMS 思源学堂 ─────────────────────────────────────────────────────

// mustUseWebVpn=false：永远直连原域名。护网结束后 lms.xjtu.edu.cn 已放开公网直连，
// 校外通常也可用；若学校重新收紧，需连校园官方 VPN 或回到校园网。
class LmsSession : CasSiteSession("lms", "思源学堂", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.lms.LmsLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        // 活动已结束时 /api/uploads/{id}/blob 也是 403 + 「没有权限」。
        // 这是业务拒绝，不是掉登录，按 403 重登只会空转。
        if (response.code == 403 && bodyPreview?.contains("没有权限") == true) return false
        if (response.code == 401) return true
        if (bodyPreview != null) return XJTULogin.isAuthFailureResponse(bodyPreview)
        return false
    }
}

// ── ICLASSFACE 人脸识别签到 ──────────────────────────────────────────────

class IclassfaceSession : CasSiteSession("iclassface", "快速考勤流水", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.iclassface.IclassfaceLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

// ── NEW ATTENDANCE 新版考勤 kq.xjtu.edu.cn ──────────────────────────────

// mustUseWebVpn=true：考勤这几个域名只在校内网络可达，校外直连连不上（443 端口
// 连超时都不给，卡满 12 秒）。写成 false 会被 SessionManager 永久锁死在直连，
// 校外必然打不开——旧考勤一直是走网关的，这里跟齐。
class NewAttendanceSession : CasSiteSession("new_attendance", "新版考勤", mustUseWebVpn = true) {

    /** 当前账号所属的考勤站点根地址（本科 bk-kq / 研究生 yjs-kq），登录成功时写入。原始域名，不含网关。 */
    fun baseUrl(): String =
        localToken[BASE_URL_KEY] ?: com.xjtu.toolbox.newattendance.NewAttendanceLogin.BASE_URL

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.newattendance.NewAttendanceLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey,
            useWebVpn = currentAccessMode == AccessMode.WEBVPN,
            // 账号类型来自一网通办身份判断（见 AccountType.fromIdentityName），跟
            // ScheduleSourceRouter 挑 kq 部署用的是同一个信号。已知的话直接登对应
            // 业务站，省掉门户那三次往返；NewAttendanceLogin.postLogin 里若直连失败
            // 会自动退回门户流程，不会因为猜错身份就登不上。
            knownAccountType = accountType,
        )

    override fun onLoginSuccess(login: XJTULogin) {
        val kq = login as? com.xjtu.toolbox.newattendance.NewAttendanceLogin
        val token = kq?.authToken
        if (!token.isNullOrBlank()) localToken["business_token"] = token
        // 本科与研究生是两套部署（bk-kq / kq），业务请求必须打到签发令牌的那一套。
        kq?.resolvedBaseUrl?.let { localToken[BASE_URL_KEY] = it }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["business_token"]?.let { builder.header(com.xjtu.toolbox.newattendance.NewAttendanceLogin.TOKEN_HEADER, it) }
        // 网页端每个业务请求都带这一条，跟着带上，免得日后服务端开始校验。
        builder.header(
            com.xjtu.toolbox.newattendance.NewAttendanceLogin.SYSTEM_HEADER,
            com.xjtu.toolbox.newattendance.NewAttendanceLogin.SYSTEM_VALUE,
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

    override suspend fun validateLogin(): Boolean = withIo {
        val token = localToken["business_token"] ?: return@withIo false
        // 走跟正常业务请求一样的 KqHttp.buildUrl：校外经 WebVPN 网关改写地址，
        // 直连域名探活必然超时；同时补上网页端每个请求都带的 X-System 头，
        // 免得服务端哪天开始校验就把探活单独漏掉。
        val resp = client.newCall(
            Request.Builder()
                .url(com.xjtu.toolbox.newattendance.KqHttp.buildUrl(this@NewAttendanceSession, "/student/home"))
                .header(com.xjtu.toolbox.newattendance.NewAttendanceLogin.TOKEN_HEADER, token)
                .header(
                    com.xjtu.toolbox.newattendance.NewAttendanceLogin.SYSTEM_HEADER,
                    com.xjtu.toolbox.newattendance.NewAttendanceLogin.SYSTEM_VALUE,
                )
                .get()
                .build()
        ).execute()
        try {
            if (resp.code != 200) return@withIo false
            val body = resp.body?.string() ?: return@withIo false
            if (XJTULogin.isAuthFailureResponse(body)) return@withIo false
            body.safeParseJsonObject().get("code")?.takeIf { !it.isJsonNull }?.asInt == 0
        } finally {
            resp.close()
        }
    }
}

/** [NewAttendanceSession.localToken] 里存考勤站点根地址的键。 */
const val BASE_URL_KEY = "kq_base_url"

// ── HELLO 迎新/个人信息 ────────────────────────────────────────────────

/**
 * hello.xjtu.edu.cn。凭据是登录落地 URL 上的 JWT，不是 cookie，所以必须在
 * [onLoginSuccess] 里把它转存到 localToken 供 [com.xjtu.toolbox.hello.HelloApi] 取用。
 */
class HelloSession : CasSiteSession("hello", "个人信息", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.hello.HelloLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        val hello = login as? com.xjtu.toolbox.hello.HelloLogin
        if (hello == null) {
            android.util.Log.w("HelloSession", "onLoginSuccess: unexpected login type ${login.javaClass.name}")
            return
        }
        hello.accessToken.takeIf { it.isNotBlank() }?.let { localToken["access_token"] = it }
        localToken["system_type"] = hello.systemType
        android.util.Log.d(
            "HelloSession",
            "onLoginSuccess: tokenLen=${hello.accessToken.length} stored=${localToken.containsKey("access_token")} keys=${localToken.keys}"
        )
    }

    // JWT 必须由会话层注入：HelloApi 若在建请求时写死 token，executeWithReAuth
    // 重登后重放的仍是旧头，新令牌用不上。
    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["access_token"]?.let { token ->
            builder.header("access-token", token)
            builder.header("access_token", token)
        }
        builder.header("systemtype", localToken["system_type"] ?: "yingxin_student_pc")
        builder.header("synAccessSource", "pc")
        return builder
    }

    // 过期 JWT 仍回 HTTP 200 + JSON state!=200，基类只认 401/CAS 页，探活必须读 state。
    override suspend fun validateLogin(): Boolean = withIo {
        val token = localToken["access_token"] ?: return@withIo false
        val resp = client.newCall(
            Request.Builder()
                .url("${com.xjtu.toolbox.hello.HelloLogin.BASE_URL}/yingxin/user/afterLogin?synAccessSource=pc")
                .header("access-token", token)
                .header("access_token", token)
                .header("systemtype", localToken["system_type"] ?: "yingxin_student_pc")
                .header("synAccessSource", "pc")
                .header("Referer", "${com.xjtu.toolbox.hello.HelloLogin.BASE_URL}/yingxin-pc/")
                .get()
                .build()
        ).execute()
        try {
            if (resp.code != 200) return@withIo false
            val body = resp.body?.string() ?: return@withIo false
            if (XJTULogin.isAuthFailureResponse(body)) return@withIo false
            runCatching { body.safeParseJsonObject().get("state")?.asInt }.getOrNull() == 200
        } finally {
            resp.close()
        }
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        val json = runCatching { body.safeParseJsonObject() }.getOrNull() ?: return false
        val state = json.get("state")?.takeIf { !it.isJsonNull }
            ?.runCatching { asInt }?.getOrNull() ?: return false
        if (state == 200) return false
        val path = response.request.url.encodedPath
        if ("/yingxin/user/" in path) return true
        val message = json.get("message")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return state == 401 || state == 403 ||
            message.contains("未登录") ||
            message.contains("过期") ||
            message.contains("token", ignoreCase = true)
    }
}

// ── JIAOCAI 教材中心 ──────────────────────────────────────────────────

class JiaocaiSession : CasSiteSession("jiaocai", "教材中心", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.jiaocai.JiaocaiLogin(existingClient = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? com.xjtu.toolbox.jiaocai.JiaocaiLogin)?.enc?.takeIf { it.isNotEmpty() }?.let {
            localToken["enc"] = it
        }
    }
}

// ── COUPON 餐券 ──────────────────────────────────────────────────────

class CouponSession : CasSiteSession("coupon", "餐券系统", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        CouponLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? CouponLogin)?.authToken?.takeIf { it.isNotBlank() }?.let {
            localToken["auth_token"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["auth_token"]?.let { builder.header("Authorization", it) }
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            body.contains("登录过期") || body.contains("未登录")
    }
}

// ── 体测查询 ─────────────────────────────────────────────────────────

/**
 * 体测查询。钉死直连（`mustUseWebVpn = false`），与 jwxt/jwapp/lms/class 同策略——
 * 这些域名公网可达，多绕一层 WebVPN 网关只会更慢。
 *
 * 2026-08-01 排查记录：校外点体测必失败，直连 `tyxylp.xjtu.edu.cn` 秒回 **HTTP 502**。
 * 一度据此推断"校外不可达、应改走 WebVPN"，遂改为跟随全局模式——**实测证伪**：
 * 走 WebVPN（从校园网内部发起）拿到的仍是同一个 502。两条独立路径同样结果，
 * 说明反向代理是通的、接不到后端，即体测应用自身故障，与访问路径无关。故已改回直连。
 */
class FitnessSession : CasSiteSession("fitness", "体测查询", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.fitness.FitnessLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey
        )

    override fun onLoginSuccess(login: XJTULogin) {
        val fitness = login as? com.xjtu.toolbox.fitness.FitnessLogin ?: return
        val launch = fitness.launch ?: return
        com.xjtu.toolbox.fitness.FitnessProtocol.writeTokens(localToken, launch)
    }

    override suspend fun validateLogin(): Boolean = withIo {
        val session = com.xjtu.toolbox.fitness.FitnessProtocol.sessionFromTokens(localToken) ?: return@withIo false
        val referer = localToken["referer_url"] ?: com.xjtu.toolbox.fitness.FitnessProtocol.H5_HOME_URL
        com.xjtu.toolbox.fitness.FitnessProtocol.requestUserInfo(client, session, referer) != null
    }
}

// ── DZPZ 电子凭证（成绩单） ───────────────────────────────────────────

class DzpzSession : CasSiteSession("dzpz", "电子凭证", mustUseWebVpn = false) {
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
    override suspend fun validateLogin(): Boolean = withIo {
        val resp = client.newCall(
            Request.Builder()
                .url("${DzpzLogin.OS_INFO_URL}?__random__=${System.currentTimeMillis()}")
                .header("Referer", "${DzpzLogin.BASE_URL}/wui/index.html")
                .get().build()
        ).execute()
        try {
            if (resp.code != 200) return@withIo false
            val id = (resp.body?.string()).safeParseJsonObject()
                .get("resourceid")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() && it != "0" } ?: return@withIo false
            localToken["user_id"] = id
            true
        } finally { resp.close() }
    }
}

// ── VENUE 场馆预订 ────────────────────────────────────────────────────

class VenueSession : CasSiteSession("venue", "场馆预订", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        VenueLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

// ── CAMPUS CARD 校园卡 ───────────────────────────────────────────────

/**
 * 校园卡会话。流程独立于标准 CAS：访问入口 → org.xjtu.edu.cn → login.xjtu.edu.cn → ticket → JWT。
 * 用 CasSiteSession 套壳——XJTULogin 状态机仍负责走完 CAS 部分，[CampusCardLogin.postLogin] 接管 ticket 兑换。
 */
class CampusCardSession : CasSiteSession("campus_card", "校园卡", mustUseWebVpn = false) {
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
        builder.header("synAccessSource", "h5")
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return com.xjtu.toolbox.card.CampusCardContract.isAuthFailureBody(body)
    }

    override suspend fun validateLogin(): Boolean = withIo {
        val token = localToken["access_token"] ?: return@withIo false
        val resp = client.newCall(
            Request.Builder()
                .url("https://ncard.xjtu.edu.cn/berserker-app/ykt/tsm/queryCard?synAccessSource=h5")
                .header("Synjones-Auth", "bearer $token")
                .header("synAccessSource", "h5")
                .get()
                .build()
        ).execute()
        try {
            if (!resp.isSuccessful) return@withIo false
            val body = resp.body?.string() ?: return@withIo false
            if (isAuthFailureResponse(resp, body)) return@withIo false
            val root = runCatching { body.safeParseJsonObject() }.getOrNull() ?: return@withIo false
            if (com.xjtu.toolbox.card.CampusCardContract.businessCode(root) != "200") return@withIo false
            if (listOf("user_name", "student_no", "card_account").any { localToken[it].isNullOrBlank() }) {
                runCatching { reloadCampusCardProfile() }.getOrElse { return@withIo false }
            }
            true
        } finally {
            resp.close()
        }
    }

    private fun reloadCampusCardProfile() {
        val token = localToken["access_token"] ?: return
        val resp = client.newCall(
            Request.Builder()
                .url("https://ncard.xjtu.edu.cn/berserker-base/user?synAccessSource=h5")
                .header("Synjones-Auth", "bearer $token")
                .header("synAccessSource", "h5")
                .get()
                .build()
        ).execute()
        resp.use {
            val body = it.body?.string() ?: throw RuntimeException("校园卡用户资料请求失败")
            if (!it.isSuccessful) throw RuntimeException("校园卡用户资料请求失败")
            val root = body.safeParseJsonObject()
            com.xjtu.toolbox.card.CampusCardContract.requireSuccess(root, "校园卡用户资料")
            val data = com.xjtu.toolbox.card.CampusCardContract.requireDataObject(root, "校园卡用户资料")
            localToken["user_name"] = com.xjtu.toolbox.card.CampusCardContract.requiredText(data, "name", "校园卡用户资料")
            localToken["student_no"] = com.xjtu.toolbox.card.CampusCardContract.requiredText(data, "sno", "校园卡用户资料")
            localToken["card_account"] = com.xjtu.toolbox.card.CampusCardContract.requiredText(data, "cardAccount", "校园卡用户资料")
        }
    }
}

// ── GSTE 研究生评教 / GMIS 研究生管理信息系统 ─────────────────────────

/**
 * 只需走完 CAS、落到本站就算登录成功的站点。CAS 回跳偶尔停在「200 + 表单自动提交」上，
 * OkHttp 不会替你提交，这时 TGC 已经建好，重访一次入口就能把跳转链走完（同 [JwxtLogin]）。
 */
private class LandingCasLogin(
    private val entryUrl: String,
    private val targetHost: String,
    existingClient: OkHttpClient,
    visitorId: String?,
    cachedRsaKey: String?,
) : XJTULogin(entryUrl, existingClient, visitorId, cachedRsaKey) {
    override fun postLogin(response: Response) {
        if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(response.request.url.toString(), targetHost)) return
        client.newCall(Request.Builder().url(entryUrl).get().build()).execute().use { retry ->
            val body = retry.body?.string().orEmpty()
            if (XJTULogin.isSafetyVerifyPage(body)) throw SafetyVerifyRequiredException(retry, body)
            if (!com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(retry.request.url.toString(), targetHost)) {
                throw IOException("$targetHost SSO 未完成跳转，需要重新登录")
            }
        }
    }
}

/**
 * 研究生评教 gste.xjtu.edu.cn。只在校园网内可达，校外走 WebVPN。
 * 身份固定选研究生：本站只服务研究生，同时有本科身份的账号也要登研究生那一支。
 */
class GsteSession : CasSiteSession("gste", "研究生评教", mustUseWebVpn = true) {
    override val accountType: XJTULogin.AccountType get() = XJTULogin.AccountType.POSTGRADUATE

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LandingCasLogin(LOGIN_URL, "gste.xjtu.edu.cn", client, visitorId, cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withIo {
        client.newCall(Request.Builder().url(LIST_URL).get().build()).execute().use { resp ->
            resp.code == 200 &&
                com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(resp.request.url.toString(), "gste.xjtu.edu.cn") &&
                resp.body?.string().orEmpty().trimStart().startsWith("[")
        }
    }

    companion object {
        const val LOGIN_URL = "https://cas.xjtu.edu.cn/login?TARGET=http%3A%2F%2Fgste.xjtu.edu.cn%2Flogin.do"
        const val LIST_URL = "http://gste.xjtu.edu.cn/app/sshd4Stu/list.do"
    }
}

/** 研究生管理信息系统 gmis.xjtu.edu.cn。研究生评教要从这里取教材、授课语言、学位课信息来填问卷。 */
class GmisSession : CasSiteSession("gmis", "研究生管理信息系统", mustUseWebVpn = true) {
    override val accountType: XJTULogin.AccountType get() = XJTULogin.AccountType.POSTGRADUATE

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LandingCasLogin(LOGIN_URL, "gmis.xjtu.edu.cn", client, visitorId, cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withIo {
        client.newCall(Request.Builder().url(SCORE_URL).get().build()).execute().use { resp ->
            resp.code == 200 &&
                com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(resp.request.url.toString(), "gmis.xjtu.edu.cn")
        }
    }

    companion object {
        const val LOGIN_URL = "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1036&state=abcd1234" +
            "&redirectUri=http://gmis.xjtu.edu.cn/pyxx/sso/login&responseType=code&scope=user_info"
        const val SCORE_URL = "https://gmis.xjtu.edu.cn/pyxx/pygl/xscjcx/index"
    }
}

// ── 智慧教室平台 js.xjtu.edu.cn（空闲教室实时状态 / 课表源） ──────────────

/**
 * 智慧教室运维平台（网页标题「智慧教室运维-服务端」，XJTUToolBox 里叫「教学服务平台」）。
 * 两处在用：空闲教室页的「实时状态」、设置里可选的课表源。凭据是 loginCas 换来的
 * `TOKEN-AUTH` 请求头（10 小时有效），不是 cookie，登录细节见 [JsLogin]。
 *
 * mustUseWebVpn=true：跟随全局模式，校外走 WebVPN。XJTUToolBox 的 JsSession 是固定直连，
 * 但 2026-09-22 真机实测校外直连 202.117.52.120:443 连接超时——这个域名只在校内可达。
 */
class JsSession : CasSiteSession(SITE_KEY, "智慧教室平台", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.auth.JsLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        val grant = (login as? com.xjtu.toolbox.auth.JsLogin)?.grantOrNull
            ?: throw IOException("智慧教室登录失败：未取得令牌")
        localToken[TOKEN_KEY] = grant.token
        localToken[EXPIRES_KEY] = (grant.obtainedAtMs + grant.timeoutSeconds * 1000L).toString()
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken[TOKEN_KEY]?.let { builder.header(com.xjtu.toolbox.auth.JsLogin.TOKEN_HEADER, it) }
        builder.header(
            com.xjtu.toolbox.auth.JsLogin.SYSTEM_HEADER,
            com.xjtu.toolbox.auth.JsLogin.SYSTEM_VALUE,
        )
        return builder
    }

    /**
     * 不发请求：令牌寿命服务端直接告诉了我们，留 10 分钟余量，过了就重换。
     * 提前失效（服务端重启、被踢）由 [executeWithReAuth] 按 401 兜底重登。
     * 也不拿 getUserInfoForPersonal 探活——那个接口会把密码哈希一起回过来。
     */
    override suspend fun validateLogin(): Boolean {
        if (localToken[TOKEN_KEY].isNullOrBlank()) return false
        val expiresAt = localToken[EXPIRES_KEY]?.toLongOrNull() ?: return false
        return System.currentTimeMillis() < expiresAt - 10 * 60_000L
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            (body.contains("token", ignoreCase = true) && body.contains("过期"))
    }

    companion object {
        const val SITE_KEY = "js"
        private const val TOKEN_KEY = "token_auth"
        private const val EXPIRES_KEY = "token_expires_at"
    }
}

// ─────────────────────────────────────────────────────────────────────
//  辅助
// ─────────────────────────────────────────────────────────────────────

private suspend inline fun <T> withIo(crossinline block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
