package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient

/**
 * 研究生管理信息系统 (GMIS) 登录
 * GMIS 使用 org.xjtu.edu.cn 的 OAuth 流程，登录后 cookies 自动管理。
 */
class GmisLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null
) : XJTULogin(GMIS_LOGIN_URL, session, visitorId) {

    companion object {
        const val GMIS_LOGIN_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1036&state=abcd1234&redirectUri=http://gmis.xjtu.edu.cn/pyxx/sso/login&responseType=code&scope=user_info"
    }
}
