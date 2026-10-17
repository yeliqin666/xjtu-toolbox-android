@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.util.decodeUrlComponentOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.time.TimeSource

/** :web 的数据入口。 */

/** 同源基址：留空 ⇒ 页面请求 `/api/...`，由 webpack dev-server（开发）/ nginx（生产）同源反代到`127.0.0.1:3099`。 */
const val API_BASE: String = ""

/** 直连基址：只用来在自检屏上当场证明 CORS 真的会说 no。 */
const val DIRECT_BASE: String = "http://127.0.0.1:3099"

/** 当前页面的 query string（深链与自检屏用），给无头浏览器截图也靠它。 */
@JsFun("() => window.location.search")
external fun browserSearch(): String

/** 在新标签页打开一个外链。 */
@JsFun("(url) => { window.open(url, '_blank', 'noopener'); }")
external fun openInNewTab(url: String)

/** 在当前标签页导航到一个外链。 */
@JsFun("(url) => { window.location.assign(url); }")
external fun navigateSameTab(url: String)

/** `?route=<id>` 的值（URL 解码过；没写或为空返回 null）。 */
fun browserRouteParam(): String? = browserSearchParam("route")

/** `?<name>=<value>` 的值（URL 解码过；没写或为空返回 null）。 */
fun browserSearchParam(name: String): String? {
    val raw = browserSearch().removePrefix("?").split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?: return null
    if (raw.isEmpty()) return null
    return decodeUrlComponentOrNull(raw) ?: raw
}

/** 复用 :core 的客户端工厂。 */
fun toolboxWebClient(token: ServeTokenHolder = serveToken): HttpClient =
    createToolboxClient().config {
        defaultRequest {
            token.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

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
