package com.xjtu.toolbox.network

import okhttp3.OkHttpClient

/**
 * App 发往学校的唯一 UA，写死，不随系统 WebView 或 OkHttp 版本变化。
 *
 * 统一认证的登录态（TGC）绑定提交密码时的 UA，换 UA 访问就被当成没登录；两种 UA 轮流提交密码会互相
 * 挤掉登录态。所以 OkHttp 和内置浏览器都用这一串。改它会让每个用户重新提交一次密码（可能还要短信验证）。
 * 扫码登录另加 [SUPERAPP_UA_SUFFIX]。
 */
const val APP_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/** 统一认证的扫码接口只认 UA 以它结尾的客户端（学校官方 App 的标识）；扫码接口不校验 TGC 绑定的 UA。 */
const val SUPERAPP_UA_SUFFIX = " SuperApp"

/**
 * 进程共享的 OkHttp 基础客户端。各处用 `HttpClients.base.newBuilder()` 派生自己的配置，
 * 共用连接池和调度线程；cookie 由各自的 CookieJar 管，不会串。
 */
object HttpClients {
    /** 没自带 UA 的请求补上 [APP_UA]。OkHttp 跟随跳转时沿用原请求头，进统一认证的每一跳也是这一串。 */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") != null) request
                    else request.newBuilder().header("User-Agent", APP_UA).build(),
                )
            }
            .build()
    }
}
