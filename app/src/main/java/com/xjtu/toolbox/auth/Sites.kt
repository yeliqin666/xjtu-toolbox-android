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

// ── CLASS 课程回放 ────────────────────────────────────────────────────

// mustUseWebVpn=false：永远直连原域名，公网可达性未逐一验证，取决于学校当前网络策略。
class ClassSession : CasSiteSession("class", "课程回放", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.classreplay.ClassLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

// ── ICLASSFACE 人脸识别签到 ──────────────────────────────────────────────

class IclassfaceSession : CasSiteSession("iclassface", "快速考勤流水", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        com.xjtu.toolbox.iclassface.IclassfaceLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)
}

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

    // 令牌是否还在只能靠实际调用来判断，这里不额外做一次探活往返：
    // 业务请求失败时 executeWithReAuth 会自愈重登。
    override suspend fun validateLogin(): Boolean = localToken["access_token"].isNullOrBlank().not()
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

// mustUseWebVpn=true：跟随全局网络检测在 NORMAL/WEBVPN 间切换，校外走 WebVPN 代理。
// 产品决策（非技术判定）：superapp.xjtu.edu.cn 主域名在此前一次真机测试里校外
// 直连本身是通的（能拿到 200 + ticket），但站内子服务（如 jwapp）的二次 CAS 接力
// 在校外出现 404，为保证整站体验一致改为统一走 WebVPN。
class SuperAppSession : CasSiteSession("super_app", "移动交大", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        SuperAppLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        val superApp = login as? SuperAppLogin ?: return
        // WebVPN 模式下 launchUrl 是加密后的 webvpn.xjtu.edu.cn/... 地址，域名部分被加密，
        // 不能再用字符串 contains("superapp.xjtu.edu.cn") 判断，需用 isAtTargetSite 兼容两种模式。
        fun isValidLaunchUrl(url: String) =
            com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(url, "superapp.xjtu.edu.cn") && url.contains("ticket=")
        val launchUrl = superApp.launchUrl
            .takeIf(::isValidLaunchUrl)
            ?: SuperAppLogin.lastSuccessfulLaunchUrl.takeIf(::isValidLaunchUrl)
            ?: superApp.launchUrl
        android.util.Log.d(
            "SuperAppSession",
            "onLoginSuccess launchUrlHasTicket=${launchUrl.contains("ticket=")} valid=${superApp.isLaunchValid()}"
        )
        launchUrl.takeIf { it.isNotBlank() }?.let {
            localToken["launch_url"] = it
        }
        localToken["launch_valid"] = launchUrl.contains("ticket=").toString()
    }

    override suspend fun validateLogin(): Boolean = withIo {
        val response = client.newCall(
            Request.Builder().url(SuperAppLogin.HOME_URL).get().build()
        ).execute()
        try {
            // WebVPN 模式下失效会跳到 webvpn.xjtu.edu.cn/https/{加密login.xjtu.edu.cn}/...，
            // host 字面上不是 "login.xjtu.edu.cn"，仅凭 host 字符串排除判断不出来，
            // 用 isAtTargetSite 才能兼容两种模式正确判断是否仍停留在登录页。
            response.code == 200 &&
                com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(
                    response.request.url.toString(), "superapp.xjtu.edu.cn"
                )
        } finally {
            response.close()
        }
    }
}

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
        (login as? com.xjtu.toolbox.fitness.FitnessLogin)?.refererUrl?.takeIf { it.isNotBlank() }?.let {
            localToken["referer_url"] = it
        }
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

// ── GMIS 研究生管理 ──────────────────────────────────────────────────

class GmisSession : CasSiteSession("gmis", "研究生管理", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        GmisLogin(session = client, visitorId = visitorId)
}

// ── GSTE 研究生评教 ──────────────────────────────────────────────────

class GsteSession : CasSiteSession("gste", "研究生评教", mustUseWebVpn = true) {
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
    mustUseWebVpn = true,
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
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            body.contains("Unauthorized", ignoreCase = true) ||
            body.contains("token", ignoreCase = true) && body.contains("过期")
    }
}

// ─────────────────────────────────────────────────────────────────────
//  辅助
// ─────────────────────────────────────────────────────────────────────

private suspend inline fun <T> withIo(crossinline block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
