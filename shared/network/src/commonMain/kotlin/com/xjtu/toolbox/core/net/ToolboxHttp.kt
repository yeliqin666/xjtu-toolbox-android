package com.xjtu.toolbox.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** 各端提供自己的引擎：Android/jvm = OkHttp，iOS = Darwin，Web = Js。 */
expect fun toolboxEngine(): HttpClientEngine

/** 共享的 Json 配置。 */
val toolboxJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

fun createToolboxClient(
    cookieStorage: CookiesStorage? = null,
    engine: HttpClientEngine = toolboxEngine(),
): HttpClient =
    HttpClient(engine) {
        // 与 App 现有行为一致：HTTP 4xx/5xx 不抛异常，由调用方按 code 判定，
        // 否则校园系统那些「200 里包一个错误」的响应会变成异常路径。
        expectSuccess = false
        install(ContentNegotiation) { json(toolboxJson) }
        if (cookieStorage != null) {
            install(HttpCookies) { storage = cookieStorage }
        }
    }
