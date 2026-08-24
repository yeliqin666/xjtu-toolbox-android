package com.xjtu.toolbox.auth

fun LoginType.siteKey(): String = when (this) {
    LoginType.ATTENDANCE -> "attendance"
    LoginType.POSTGRADUATE_ATTENDANCE -> "pg_attendance"
    LoginType.JWXT -> "jwxt"
    LoginType.JWAPP -> "jwapp"
    LoginType.YWTB -> "ywtb"
    LoginType.LIBRARY -> "library"
    LoginType.CAMPUS_CARD -> "campus_card"
    LoginType.DZPZ -> "dzpz"
    LoginType.VENUE -> "venue"
    LoginType.CLASS -> "class"
    LoginType.LMS -> "lms"
    LoginType.JIAOCAI -> "jiaocai"
    LoginType.COUPON -> "coupon"
    LoginType.FITNESS -> "fitness"
    LoginType.JIAOXIAOZHI -> "jiaoxiaozhi"
    LoginType.ICLASSFACE -> "iclassface"
}

/**
 * @param userInitiated 用户正在前台等这次结果（点功能入口）。为 true 时豁免站点级 60 秒失败冷却
 * ——防刷仍由 CasGate 的全局串行 + 失败退避 + 密码熔断保证。
 * @param silent 后台路径（首页刷新、预热、屁岱的工具调用）必须传 true：撞上短信验证时抛
 * [MfaRequiredException] 直接退出，**不弹窗也不发短信**。用户没有主动要求的登录，
 * 不该让他的手机突然收到验证码。
 */
suspend fun SessionManager.ensureSite(
    siteKey: String,
    userInitiated: Boolean = false,
    silent: Boolean = false,
): SiteSession {
    val site = getSite(siteKey)
    val creds = credentials ?: throw AuthExpiredException(site.siteName, "未配置凭据")
    site.ensureLogin(creds.first, creds.second, userInitiated = userInitiated, silent = silent)
    return site
}

suspend fun SessionManager.ensureSite(
    type: LoginType,
    userInitiated: Boolean = false,
    silent: Boolean = false,
): SiteSession = ensureSite(type.siteKey(), userInitiated, silent)
