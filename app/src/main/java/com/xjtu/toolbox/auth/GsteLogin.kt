package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient

/**
 * 研究生评教系统 (GSTE) 登录
 * 使用 CAS 认证，登录后 cookies 自动管理。
 */
class GsteLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null
) : XJTULogin(GSTE_LOGIN_URL, session, visitorId) {

    companion object {
        const val GSTE_LOGIN_URL =
            "https://cas.xjtu.edu.cn/login?TARGET=http%3A%2F%2Fgste.xjtu.edu.cn%2Flogin.do"
    }
}
