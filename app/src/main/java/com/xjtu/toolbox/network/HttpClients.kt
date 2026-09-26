package com.xjtu.toolbox.network

import okhttp3.OkHttpClient

/**
 * 进程共享的 OkHttp 基础客户端。
 *
 * 每个 `OkHttpClient` 自带连接池和调度线程池。会被反复 new 的 Api 类（页面 remember、
 * Agent 每次调工具、后台任务每轮一个）不要自己 `OkHttpClient.Builder().build()`，
 * 而是在伴生对象里 `HttpClients.base.newBuilder()…build()` 派生一次：派生出的客户端
 * 与基础客户端共用连接池和线程，只是超时、拦截器等配置不同。这是 OkHttp 官方推荐用法。
 *
 * 需要独立 CookieJar 的登录会话（SessionBackend/XJTULogin）也可以这样派生，
 * 连接池共用不会串 cookie——cookie 由各自的 CookieJar 管。
 */
object HttpClients {
    val base: OkHttpClient by lazy { OkHttpClient() }
}
