package com.xjtu.toolbox.network

import okhttp3.OkHttpClient

/**
 * 进程共享的 OkHttp 基础客户端。各处用 `HttpClients.base.newBuilder()` 派生自己的配置，
 * 共用连接池和调度线程；cookie 由各自的 CookieJar 管，不会串。
 */
object HttpClients {
    val base: OkHttpClient by lazy { OkHttpClient() }
}

/** 手机 Chrome 的 UA：不少学校网关按 UA 拦非浏览器请求。 */
const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
