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
    LoginType.SUPER_APP -> "super_app"
    LoginType.FITNESS -> "fitness"
    LoginType.JIAOXIAOZHI -> "jiaoxiaozhi"
}

suspend fun SessionManager.ensureSite(siteKey: String): SiteSession {
    val site = getSite(siteKey)
    val creds = credentials ?: throw AuthExpiredException(site.siteName, "未配置凭据")
    site.ensureLogin(creds.first, creds.second)
    return site
}

suspend fun SessionManager.ensureSite(type: LoginType): SiteSession = ensureSite(type.siteKey())
