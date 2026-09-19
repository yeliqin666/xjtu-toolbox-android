package com.xjtu.toolbox.agent

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory

/**
 * 联网工具的页面处理，对齐成熟开源 Agent 而不是自研解析器。
 *
 * - 抓页：HuggingFace [smolagents VisitWebpageTool](https://github.com/huggingface/smolagents/blob/main/src/smolagents/default_tools.py)
 *   （GET → markdownify → 折叠空行 → 截断）+ Cline [UrlContentFetcher](https://github.com/cline/cline/blob/main/src/services/browser/UrlContentFetcher.ts)
 *   （去掉 script/style/nav/header/footer 再转 Markdown）。Java 侧 markdownify/turndown 的对应库是 flexmark-html2md。
 * - 搜索默认走 Jina / 360 / Brave / DuckDuckGo HTML，Bing RSS 只作兜底（中文结果差）。
 */
internal object AgentWeb {

    /** 只为防 OOM 截读；超了按已读部分转 Markdown，不整页拒绝。smolagents 则是转完再截字符。 */
    const val HTML_READ_BYTES = 8L * 1024 * 1024

    /** smolagents VisitWebpageTool 默认 40_000；本地模型上下文更紧，用硬上限。 */
    const val MARKDOWN_CHARS = ContextBudget.HARD_CAP

    private val BINARY_TYPES = listOf(
        "application/pdf", "application/zip", "application/gzip", "application/octet-stream",
        "application/x-rar", "application/msword", "application/vnd.",
        "image/", "audio/", "video/", "font/",
    )

    fun isPublicHttpUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trim('.') ?: return false
        return !isBlockedHost(host)
    }

    fun requirePublicHttpUrl(raw: String) {
        if (!isPublicHttpUrl(raw)) {
            throw java.io.IOException("拒绝访问非公开 HTTP(S) 地址")
        }
    }

    /**
     * URL 层面的快速预检，只能挡住字面写出来的内网地址，给出友好报错。
     *
     * **真正的防线是 [publicOnlySocketFactory]**：域名解析到内网、DNS 重绑定、302 跳到内网，
     * 这些在 URL 字符串上都看不出来，只有在真正建连时检查目标 IP 才可靠。
     */
    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().trim().trim('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        val bare = h.removePrefix("[").removeSuffix("]")
        val literal = when {
            parseIpv4(bare) != null -> bare
            bare.contains(':') -> bare          // IPv6 字面量
            else -> return false                // 域名：交给建连时检查
        }
        // 字面量不会触发 DNS 查询
        val addr = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return true
        return isNonPublicAddress(addr)
    }

    /**
     * 不允许 Agent 访问的地址：回环、私网、链路本地、组播、未指定地址、CGNAT、IPv6 ULA，
     * 以及 IPv4 映射/兼容的 IPv6 里包着的这些地址。
     */
    fun isNonPublicAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return true
        val b = addr.address
        if (addr is Inet4Address) {
            val a0 = b[0].toInt() and 0xff
            val a1 = b[1].toInt() and 0xff
            // 不拦 198.18.0.0/15：Clash/Surge 等 VPN 的"虚拟网卡 + fake-ip"模式把所有域名都解析到
            // 这个网段，拦了它，开着这类 VPN 的人屁岱就一个网页也打不开。这个网段只用于基准测试，
            // 不会是谁家的内网服务。
            return a0 == 0 ||                               // 0.0.0.0/8
                (a0 == 100 && a1 in 64..127)                // 100.64.0.0/10 运营商级 NAT
        }
        if (addr is Inet6Address) {
            if ((b[0].toInt() and 0xfe) == 0xfc) return true // fc00::/7 ULA
            // ::ffff:a.b.c.d（映射）与 ::a.b.c.d（兼容）：按里面的 IPv4 判
            val first10Zero = (0 until 10).all { b[it].toInt() == 0 }
            if (first10Zero) {
                val mapped = (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
                val compat = b[10].toInt() == 0 && b[11].toInt() == 0
                if (mapped || compat) {
                    val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
                    return isNonPublicAddress(v4)
                }
            }
        }
        return false
    }

    /**
     * 只允许连公网地址的 SocketFactory，给 Agent 联网工具的 OkHttpClient 用。
     *
     * 在 [Socket.connect] 时检查的是**即将连接的真实 IP**：不管它来自 DNS 解析（包括解析到
     * 内网的域名、DNS 重绑定）、URL 里的字面 IP，还是重定向后的新地址，都绕不过去。
     * 以前只在 URL 字符串上判断，域名一律放行，响应回来后才复查——那时请求早已发出。
     *
     * 走系统 HTTP 代理（Clash 的代理模式等）时，这里连的是代理本身，通常在 127.0.0.1——
     * 这一个地址放行（见 [isSystemProxyEndpoint]），否则开着代理的人屁岱完全上不了网。
     * 代理之后连去哪由用户自己的代理决定，这里管不到；URL 里字面写出的内网地址仍由
     * [isBlockedHost] 预先拦下。
     */
    val publicOnlySocketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = GuardedSocket()
        override fun createSocket(host: String?, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            GuardedSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            GuardedSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    private class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress
            val addr = target?.address
            if (addr == null || (isNonPublicAddress(addr) && !isSystemProxyEndpoint(target))) {
                throw java.io.IOException("拒绝访问非公开地址")
            }
            super.connect(endpoint, timeout)
        }
    }

    /**
     * [endpoint] 是不是系统当前配置的 HTTP 代理。OkHttp 默认按 [ProxySelector.getDefault] 选代理，
     * 这里用同一个来源比对，只放行代理自己那个地址和端口。
     */
    internal fun isSystemProxyEndpoint(
        endpoint: InetSocketAddress,
        selector: ProxySelector? = ProxySelector.getDefault(),
    ): Boolean {
        val addr = endpoint.address ?: return false
        val proxies = selector ?: return false
        return PROXY_PROBES.asSequence()
            .flatMap { probe -> runCatching { proxies.select(probe) }.getOrNull().orEmpty().asSequence() }
            .filter { it.type() != Proxy.Type.DIRECT }
            .mapNotNull { it.address() as? InetSocketAddress }
            .any { proxy ->
                proxy.port == endpoint.port &&
                    (proxy.address?.let { it == addr }
                        ?: runCatching { InetAddress.getAllByName(proxy.hostString) }.getOrNull()
                            .orEmpty().any { it == addr })
            }
    }

    private val PROXY_PROBES = listOf(URI("http://example.com/"), URI("https://example.com/"))

    /**
     * 给接收不可信 URL 的客户端应用公网访问策略：建连时拒绝内网/回环地址（系统代理本身除外）。
     * 代理沿用系统设置，不强行直连——开着代理的人多半是要靠它访问外网搜索引擎。
     */
    fun applyPublicNetworkPolicy(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder =
        builder.socketFactory(publicOnlySocketFactory)

    fun isBinaryContentType(contentType: String?): Boolean {
        val t = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return false
        if (t.startsWith("text/")) return false
        if (t.contains("html") || t.contains("xml") || t.contains("json") || t.contains("javascript") || t.contains("rss")) {
            return false
        }
        return BINARY_TYPES.any { t.startsWith(it) }
    }

    fun parseHtml(bytes: ByteArray, baseUrl: String) =
        Jsoup.parse(bytes.inputStream(), null, baseUrl)

    /**
     * Cline：cheerio 去掉 chrome 后再 turndown。
     * smolagents：markdownify 后把连续空行收成两行。
     */
    fun htmlToMarkdown(html: String, baseUrl: String = ""): String {
        val doc = if (baseUrl.isBlank()) Jsoup.parse(html) else Jsoup.parse(html, baseUrl)
        val wechat = doc.selectFirst("#js_content, #page-content, .rich_media_content")
        if (wechat != null && wechat.text().trim().length >= 40) {
            wechat.select("img[data-src]").forEach { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.isNotBlank()) img.attr("src", src)
            }
            wechat.select("script, style").remove()
            val markdown = FlexmarkHtmlConverter.builder().build().convert(wechat.outerHtml()).trim()
            return markdown.replace(Regex("\n{3,}"), "\n\n")
        }
        doc.select("script, style, nav, footer, header, noscript").remove()
        val markdown = FlexmarkHtmlConverter.builder().build().convert(doc.html()).trim()
        return markdown.replace(Regex("\n{3,}"), "\n\n")
    }

    /** smolagents VisitWebpageTool._truncate_content */
    fun truncateMarkdown(content: String, maxLength: Int = MARKDOWN_CHARS): String {
        if (content.length <= maxLength) return content
        return content.take(maxLength) +
            "\n..._This content has been truncated to stay below $maxLength characters_...\n"
    }

    /** smolagents WebSearchTool.search_bing：Bing RSS，避开 HTML 验证码页。 */
    fun parseBingRss(xml: String, limit: Int): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").asSequence().mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val snippet = item.selectFirst("description")?.text()?.trim().orEmpty()
            if (title.isBlank() || !link.startsWith("http")) null
            else Triple(title, link, snippet)
        }.take(limit.coerceAtLeast(1)).toList()
    }

    /**
     * smolagents WebSearchTool.search_duckduckgo：lite 版三个 class 对齐成一条结果。
     * 真实 URL 在 `span.link-text`（不含 scheme），不要用 DDG 的跳转 href。
     */
    fun parseDuckDuckGoLite(html: String, limit: Int): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        val titles = doc.select("a.result-link")
        val snippets = doc.select("td.result-snippet")
        val links = doc.select("span.link-text")
        return (0 until minOf(titles.size, limit.coerceAtLeast(1))).mapNotNull { i ->
            val title = titles[i].text().trim()
            if (title.isBlank()) return@mapNotNull null
            val link = when {
                i < links.size -> {
                    val raw = links[i].text().trim()
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                }
                else -> titles[i].absUrl("href").ifBlank { titles[i].attr("href") }
            }
            if (!link.startsWith("http")) return@mapNotNull null
            val snippet = snippets.getOrNull(i)?.text()?.trim().orEmpty()
            Triple(title, link, snippet)
        }
    }

    /** DuckDuckGo html 版（`html.duckduckgo.com/html/`），可带 `kl=cn-zh`。 */
    fun parseDuckDuckGoHtml(html: String, limit: Int): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        return doc.select("div.result, div.results_links, div.web-result").asSequence().mapNotNull { el ->
            val a = el.selectFirst("a.result__a, a.result-link") ?: return@mapNotNull null
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val link = normalizeDdgRedirect(href)
            if (!link.startsWith("http")) return@mapNotNull null
            val snippet = el.selectFirst("a.result__snippet, td.result-snippet, .result__snippet")
                ?.text()?.trim().orEmpty()
            Triple(title, link, snippet)
        }.take(limit.coerceAtLeast(1)).toList()
    }

    /** 360 搜索（so.com），国内可访问、比搜狗少弹验证码。 */
    fun parseSo360Html(html: String, limit: Int): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        return doc.select("li.res-list, .res-list, .result").asSequence().mapNotNull { el ->
            val a = el.selectFirst("h3 a, .res-title a, a") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = a.absUrl("href").ifBlank { a.attr("href") }
            if (title.isBlank() || !link.startsWith("http")) return@mapNotNull null
            val host = runCatching { URI(link).host.orEmpty().lowercase() }.getOrDefault("")
            if (host.contains("so.com") || host.contains("360.cn")) return@mapNotNull null
            val snippet = el.selectFirst(".res-desc, .res-rich, .res-list-summary, p")?.text()?.trim().orEmpty()
            Triple(title, link, snippet)
        }.distinctBy { it.second }.take(limit.coerceAtLeast(1)).toList()
    }

    fun looksLikeCaptcha(body: String): Boolean {
        val t = body.lowercase()
        return t.contains("captcha") ||
            t.contains("geetest") ||
            t.contains("recaptcha") ||
            t.contains("antispider") ||
            t.contains("sg_anti") ||
            t.contains("验证码") ||
            t.contains("滑动验证") ||
            t.contains("请完成验证") ||
            t.contains("访问过于频繁") ||
            t.contains("unusual traffic") ||
            (t.contains("enable javascript") && t.contains("challenge"))
    }

    fun looksLikeWeChatBlock(html: String): Boolean {
        val t = html.lowercase()
        if (t.contains("环境异常") || t.contains("请在微信客户端打开链接") || t.contains("该内容已被发布者删除")) {
            return true
        }
        if (!t.contains("js_content") && !t.contains("rich_media")) return false
        val text = Jsoup.parse(html).selectFirst("#js_content, #page-content, .rich_media_content")
            ?.text()?.trim().orEmpty()
        return text.length < 40
    }

    fun isWeChatUrl(raw: String): Boolean {
        val host = runCatching { URI(raw.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        return host.contains("mp.weixin.qq.com")
    }

    fun isSogouJumpUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        if (!host.contains("sogou.com")) return false
        val path = uri.path.orEmpty().lowercase()
        val q = uri.query.orEmpty()
        return path.contains("/link") || q.contains("url=")
    }

    /**
     * 搜狗微信结果页点击时会给 `/link?url=` 补 `k`/`h`（WechatSogou #235）。
     * 偏移随页面脚本偶尔改；缺省按公开的 `url=` 后 15+k。
     */
    fun withSogouClickParams(url: String, k: Int? = null, extraOffset: Int = 15): String {
        if (url.contains("&k=") || !url.contains("url=")) return url
        val a = url.indexOf("url=")
        val b = (k ?: ((1..100).random())).coerceIn(1, 100)
        val idx = a + extraOffset + b
        if (idx !in url.indices) return url
        return "$url&k=$b&h=${url[idx]}"
    }

    fun jinaReaderUrl(target: String): String {
        val u = target.trim()
        if (u.contains("r.jina.ai")) return u
        return "https://r.jina.ai/$u"
    }

    fun looksLikeJinaMarkdown(body: String): Boolean {
        val t = body.trimStart()
        return t.startsWith("Title:") || t.contains("Markdown Content:") || t.startsWith("# ")
    }

    /** 中文维基 OpenSearch：`[query, titles[], descs[], urls[]]`，无验证码。 */
    fun parseWikiOpenSearch(body: String, limit: Int): List<Triple<String, String, String>> {
        val arr = runCatching { com.google.gson.JsonParser.parseString(body).asJsonArray }.getOrNull()
            ?: return emptyList()
        if (arr.size() < 4) return emptyList()
        val titles = arr[1].takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val descs = arr[2].takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val urls = arr[3].takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val n = minOf(titles.size(), descs.size(), urls.size(), limit.coerceAtLeast(1))
        return (0 until n).mapNotNull { i ->
            val title = runCatching { titles[i].asString.trim() }.getOrDefault("")
            val url = runCatching { urls[i].asString.trim() }.getOrDefault("")
            val desc = runCatching { descs[i].asString.trim() }.getOrDefault("")
            if (title.isBlank() || !url.startsWith("http")) null
            else Triple(title, url, desc)
        }
    }

    private fun normalizeDdgRedirect(href: String): String {
        val raw = href.trim()
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
            if (uri.host?.contains("duckduckgo.com", ignoreCase = true) == true) {
                val uddg = uri.query?.split('&')?.firstOrNull { it.startsWith("uddg=") }
                    ?.substringAfter("uddg=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (!uddg.isNullOrBlank()) return uddg
            }
            return raw
        }
        return raw
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            nums[i] = n
        }
        return nums
    }
}
