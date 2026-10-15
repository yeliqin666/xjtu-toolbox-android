package com.xjtu.toolbox.network

import com.xjtu.toolbox.core.net.toolboxJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient

/** 「用 Ktor 的 API，跑同一套 okhttp 底座」 —— 把 okhttp → Ktor 这件事从「换传输层」降级成「换写法」。 */
fun OkHttpClient.asToolboxKtorClient(preconfigured: OkHttpClient = this): HttpClient =
    HttpClient(OkHttp) {
        engine { this.preconfigured = `preconfigured` }
        expectSuccess = false
        install(ContentNegotiation) { json(toolboxJson) }
        // 只在缺 UA 时补（统一认证绑定 UA）；不能用 DefaultRequest，它是 append，会把 SuperApp UA 拼成两个值。
        install(
            createClientPlugin("ToolboxUserAgent") {
                onRequest { request, _ ->
                    if (request.headers[HttpHeaders.UserAgent] == null) {
                        request.headers.append(HttpHeaders.UserAgent, APP_UA)
                    }
                }
            }
        )
    }
