package com.xjtu.toolbox.library

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 图书馆桌面二维码链接的**解析**（`:app` 侧）。
 *
 * 为什么是顶层函数、而不是原来那个 `LibrarySeatQr.parse`：[LibrarySeatQr] 这个数据类搬进了 `:core`
 *（共享屏与共享 ViewModel 要用它），而 Kotlin 的伴生对象不能长在类的外面 ⇒ 解析只能变成一个别的名字。
 * 解析**本身留在 `:app`**：它用 okhttp 的 `HttpUrl`（`toHttpUrlOrNull`），`:core` 的 commonMain 里没有那个库；
 * 而需要跨模块共享的只是「座位号 + 区域码 + 这个区域属于哪个校区」这几个字段。
 *
 * 认不出返回 null。只认图书馆座位系统的 `/qavail/`，别把别的链接当成座位码。
 */
fun parseLibrarySeatQr(text: String): LibrarySeatQr? {
    val url = text.trim().toHttpUrlOrNull() ?: return null
    if (!url.host.equals(LIBRARY_HOST, ignoreCase = true)) return null
    if (url.pathSegments.firstOrNull { it.isNotEmpty() } != "qavail") return null
    val seat = url.queryParameter("seat")?.trim().orEmpty()
    val sp = url.queryParameter("sp")?.trim().orEmpty()
    if (seat.isEmpty() || sp.isEmpty()) return null
    if (!SAFE_SEAT_VALUE.matches(seat) || !SAFE_SEAT_VALUE.matches(sp)) return null
    return LibrarySeatQr(seat, sp)
}

private const val LIBRARY_HOST = "rg.lib.xjtu.edu.cn"

/** 只放行字母数字，座位号和区域码会原样拼进预约地址。 */
private val SAFE_SEAT_VALUE = Regex("[A-Za-z0-9_-]{1,32}")

/**
 * 查单个座位空不空：就是桌面二维码本身打开的那个 `/qavail/` 页面，一次请求，
 * 比拉整层座位表（`qspace` + `qseat`）快得多，扫码弹确认时用。
 *
 * 这一枪直连图书馆站点（okhttp + Jsoup 解析页面），所以留在 `:app`：
 * 共享屏通过 `LibrarySource.seatAvailability` 端口调它（Web 端没有这个端点，见 `CampusLibraryApi` 的 KDoc）。
 */
object LibrarySeatAvailability {
    /** `/qavail/` 页面标题「西安交通大学图书馆图书预约系统」，座位不存在时也有。 */
    private const val PAGE_MARK = "图书馆图书预约系统"
    private val STATUS_RE = Regex("""座位号：\S+\s+预约状态:\s*(\S+)""")

    /**
     * @param client 图书馆会话的客户端（校外自动经 WebVPN）
     * @throws java.io.IOException 拿回来的不是预约系统页面（如 WebVPN 登录页）——那是「查不了」，不是「没有这个座位」
     */
    fun fetch(client: OkHttpClient, qr: LibrarySeatQr): LibrarySeatStatus {
        val url = "${LibraryPages.BASE_URL}/qavail/?seat=${qr.seat}&sp=${qr.areaCode}"
        val html = client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("座位状态查询失败：HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (PAGE_MARK !in html) throw java.io.IOException("不是图书馆预约系统的页面")
        return parse(html)
    }

    /** 页面是 `<h4>座位号：X</h4>…<h4>预约状态:</h4><h3>状态<h3>`（h3 没闭合），脚本里也有「空闲」字样，先去掉脚本。 */
    internal fun parse(html: String): LibrarySeatStatus {
        if ("座位号" !in html) return LibrarySeatStatus.NotFound
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val text = doc.body()?.text().orEmpty().replace('\u00A0', ' ')
        val m = STATUS_RE.find(text) ?: return LibrarySeatStatus.NotFound
        return LibrarySeatStatus.Known(m.groupValues[1])
    }
}
