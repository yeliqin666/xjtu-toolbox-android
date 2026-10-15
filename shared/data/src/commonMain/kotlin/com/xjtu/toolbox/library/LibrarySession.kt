package com.xjtu.toolbox.library

import com.xjtu.toolbox.auth.SessionExpiredException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 图书馆数据层的会话缝：`LibraryApi` 只认这三件事。 */
interface LibrarySession {

    /** 本端的 OkHttp 客户端（共享 cookie jar / UA / 超时拦截器）。 */
    val client: OkHttpClient

    /** 发一个业务请求。命中认证失效时由实现方自己重认证并重放一次；重放仍失效则抛会话失效异常。 */
    suspend fun fetch(request: Request): Response

    /** 构造本端的「会话失效」异常。 */
    fun authExpired(siteName: String): SessionExpiredException
}

