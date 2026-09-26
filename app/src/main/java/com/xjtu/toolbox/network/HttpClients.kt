package com.xjtu.toolbox.network

import okhttp3.OkHttpClient

/**
 * 进程共享的 OkHttp 基础客户端。各处用 `HttpClients.base.newBuilder()` 派生自己的配置，
 * 共用连接池和调度线程；cookie 由各自的 CookieJar 管，不会串。
 */
object HttpClients {
    val base: OkHttpClient by lazy { OkHttpClient() }
}
