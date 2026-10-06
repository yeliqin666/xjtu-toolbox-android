package com.xjtu.toolbox.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 各端提供自己的引擎：Android/jvm = OkHttp，iOS = Darwin，Web = Js。
 *
 * 这是整个探针里**唯一**的 expect/actual —— 刻意把粒度压到「能力」而不是「文件」：
 * App 里 79 个文件直接用 okhttp3 的那些调用点，将来只在 Ktor 这一层收口一次。
 */
expect fun toolboxEngine(): HttpClientEngine

/**
 * 共享的 Json 口径。`ignoreUnknownKeys` 必须开：上游 47 列的课表随时加列，
 * App 现有的解析习惯也是如此（见 `util` 包），换成 Ktor 后不能比现在更脆。
 */
val toolboxJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * @param cookieStorage 传 null 表示这一端不需要会话保持（探针对照用）。
 *   Android 传 SharedPreferences 版、Web 传 localStorage 版、iOS 传 UserDefaults 版 ——
 *   但接口和语义完全共享，这就是为什么 Cookie 存储要放 commonMain。
 */
/**
 * @param cookieStorage null 表示这一端不需要会话保持（探针对照用）。
 * @param engine 默认取本端引擎；测试传 MockEngine，好让 Cookie 链路
 *   **在任意目标上都能离线验证**，不需要真服务器——这是探针能进 CI 的前提。
 */
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
