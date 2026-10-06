@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.createToolboxClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.time.TimeSource

/**
 * :web 的数据入口。
 *
 * 这里**没有任何模型/解析**：课表模型、周次展开、学期日期换算都在 :core 的
 * `com.xjtu.toolbox.schedule`，HTTP 客户端与 JSON 口径在 :core 的 `core.net`。
 * 本文件只回答两个浏览器问题：**今天是几号**（JS 才有），以及**同源还是跨源**。
 */

/**
 * 同源基址：留空 ⇒ 页面请求 `/api/...`，由 webpack dev-server（开发）/ nginx（生产）同源反代到
 * `127.0.0.1:3099`。这是 Web 端**唯一**可用的数据路径。
 */
const val API_BASE: String = ""

/**
 * 直连基址：只用来在自检屏上**当场证明 CORS 真的会说 no**。
 * campus-api 刻意不给零鉴权端点发 `Access-Control-Allow-Origin`——否则任意网页都能读个人数据。
 */
const val DIRECT_BASE: String = "http://127.0.0.1:3099"

/** 浏览器的本地日期，`2026-10-06`。Kotlin/Wasm 没有 `java.time`，也不值得为这一处引时区库。 */
@JsFun("() => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0'); }")
external fun browserTodayIso(): String

/** 当前页面的 query string（自检屏的深链：`?tab=probe`），用来给无头浏览器截图用。 */
@JsFun("() => window.location.search")
external fun browserSearch(): String

/** 起始标签页：默认课表；`?tab=probe` 直达后端自检屏。 */
fun initialTabIsProbe(): Boolean = browserSearch().contains("tab=probe")

/** 复用 :core 的客户端工厂：引擎、JSON 口径、Cookie 策略全在共享层，各端不各配一遍。 */
fun toolboxWebClient(): HttpClient = createToolboxClient()

data class ProbeResult(
    val label: String,
    val url: String,
    val ok: Boolean,
    val detail: String,
    val ms: Long,
)

suspend fun probe(client: HttpClient, label: String, url: String): ProbeResult {
    val mark = TimeSource.Monotonic.markNow()
    return try {
        val resp = client.get(url)
        val text = resp.bodyAsText()
        ProbeResult(label, url, true, "HTTP ${resp.status.value} · ${text.take(90)}", mark.elapsedNow().inWholeMilliseconds)
    } catch (e: Throwable) {
        // CORS 失败在 fetch 这一层就抛了，拿不到 HTTP 状态 —— 这本身就是「跨源不可用」的证据
        ProbeResult(label, url, false, e.message?.take(160) ?: e.toString(), mark.elapsedNow().inWholeMilliseconds)
    }
}
