package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.webvpn.WebVpnAsyncCodec
import io.ktor.client.HttpClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * WebVPN 网址互转的 **serve 版实现**：给 `:web` 用，实现 [WebVpnAsyncCodec]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `POST /api/webvpn/convert` | [toVpnUrl]（`to_vpn`）与 [fromVpnUrl]（`from_vpn`） |
 *
 * 契约 §5.7；`:server` 那一侧是 `WebVpnRoutes.kt`（一行算法都不重做，直接走 `:data` 的
 * `WebVpnUtil` —— AES-128-CFB，key / iv 都是 `wrdvpnisthebest!`）。
 *
 * ## 为什么浏览器这一端要有这个类（而不是本地转）
 *
 * `:data` 的 `WebVpnUtil` 在 wasm 上**用不了**：它要 `javax.crypto`。退一步说，浏览器能白拿的
 * 对称密码只有 `crypto.subtle`，而它**只在安全上下文里存在** —— serve 默认明文 HTTP，
 * `--host 0.0.0.0` 时页面就不是 localhost 了 ⇒ 非安全上下文 ⇒ `crypto.subtle` 直接被禁
 *（契约 §5.7 把这条写成了这一族放在服务端的**唯一理由**：那条路根本不存在，不是「慢一点」）。
 * 于是本端这两个方向各打一发 `POST /api/webvpn/convert`，转换本体仍在服务端那一份实现里 ——
 * 与 Android / 桌面注入的**同一个算法**，不是两处各写一遍。
 *
 * ## 两个方向的失败口径**刻意不一样**（照 §5.7）
 *
 * - `to_vpn`：认不出的输入（没有 `://`）**原样返回**、已经是网关地址的输入也原样返回 —— 那是
 *   `:data` 的老口径，本层不改。所以这一个方向上服务端唯一可能的 `400` 是「url 空」，而那个
 *   调用点（屏）自己会先挡住空白 ⇒ 真失败（`401` 令牌闸门那一档）就如实抛出去；
 * - `from_vpn`：解不出来（不是网关地址 / 路径不成形）服务端答 `400` —— 契约 §5.7 明确说这一档
 *   **不是** `data:null`（「这不是上游说没有，是你给的不是网关地址」）。本类把 `400` **如实翻成
 *   `null`**：屏上那句「无法解析此 WebVPN 网址，请确认格式正确」说的正是这件事。其余失败
 *   （`401` 之类）抛错 —— 把「没登录」说成「网址格式不对」就是编话。
 *
 * ## 这一族不收会话，也不碰任何上游
 *
 * 纯函数：不发上游请求、不落盘、不改任何状态、没有任何个人数据。所以它**没有 `502`**，
 * 屏上也不该有「先登录校园账号」那一步（令牌闸门是页面级的，见 `:web` 的 `toolboxWebClient`）。
 *
 * ## 默认形态（campus-api）
 *
 * WebVPN 转换在 campus-api 里没有对应端点（两端走 `:data` 的 `WebVpnUtil`，是本地纯函数）⇒
 * 非 serve 分支一律 [serveOnly]（抛错，不返回一份空数据 —— 那会把「本地转换不可用」说成
 * 「转换结果是空」）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusWebVpnApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份实现只认 serve 契约（§5.7）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : WebVpnAsyncCodec {

    override suspend fun toVpnUrl(url: String): String {
        requireServe()
        val outcome = convert(url, DIRECTION_TO_VPN)
        if (!outcome.ok) error("WebVPN 转换：${outcome.message}")
        return outcome.readUrl("to_vpn")
    }

    override suspend fun fromVpnUrl(vpnUrl: String): String? {
        requireServe()
        val outcome = convert(vpnUrl, DIRECTION_FROM_VPN)
        if (!outcome.ok) {
            // 见类 KDoc：400 = 「你给的不是网关地址」⇒ 如实翻成 null（屏上那句话正好说的是这件事）
            if (outcome.code == HTTP_BAD_REQUEST) return null
            error("WebVPN 转换：${outcome.message}")
        }
        return outcome.readUrl("from_vpn")
    }

    /**
     * 一发转换。请求体固定就是契约里那两个字段（`direction` 用小写下划线那套写法，服务端自己会
     * 再小写化一次，本层不做第二个口径）。
     */
    private suspend fun convert(url: String, direction: String): ServeActionOutcome = client.serveAction(
        baseUrl,
        CONVERT_PATH,
        buildJsonObject {
            put("url", url)
            put("direction", direction)
        }.toString(),
    )

    /**
     * 读成功响应里的那一格 `url`。缺了 / 空白就抛错 —— **不编一个默认值**（画一个空地址等于
     * 让用户拿着一个打不开的链接）。
     */
    private fun ServeActionOutcome.readUrl(direction: String): String {
        val converted = data?.get("url").safeString().orEmpty()
        if (converted.isBlank()) {
            error(
                "WebVPN 转换：服务端返回的 url 是空的（契约 §5.7 的 data.url —— $direction 这一向" +
                    "只该给一个地址，给不出就如实报错）",
            )
        }
        return converted
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("WebVPN 网址转换", CONVERT_PATH)
    }
}

/** 网址互转（纯函数，两个方向共用一个端点）。 */
private const val CONVERT_PATH = "/api/webvpn/convert"

/** 普通网址 → 网关地址。 */
private const val DIRECTION_TO_VPN = "to_vpn"

/** 网关地址 → 原网址。 */
private const val DIRECTION_FROM_VPN = "from_vpn"

/** `400`：`from_vpn` 这一向的「你给的不是网关地址」（契约 §5.7）。 */
private const val HTTP_BAD_REQUEST = 400
