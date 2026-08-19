package com.xjtu.toolbox.agent

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

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

    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().trim().trim('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "0.0.0.0" || h == "::1" || h == "[::1]") return true
        val bare = h.removePrefix("[").removeSuffix("]")
        if (bare == "::1") return true
        val ipv4 = parseIpv4(h) ?: return false
        val a = ipv4[0]
        val b = ipv4[1]
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }

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

    /**
     * Jina Search `s.jina.ai`：给模型用的检索，中文比 Bing RSS 稳，也不走搜狗验证码页。
     * 同时认 JSON（`Accept: application/json`）和默认 Markdown。
     */
    fun parseJinaSearch(body: String, limit: Int): List<Triple<String, String, String>> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching { com.google.gson.JsonParser.parseString(trimmed) }.getOrNull()
            val arr = when {
                parsed == null -> null
                parsed.isJsonArray -> parsed.asJsonArray
                parsed.isJsonObject -> parsed.asJsonObject.getAsJsonArray("data")
                    ?: parsed.asJsonObject.getAsJsonArray("results")
                else -> null
            }
            if (arr != null) {
                return arr.asSequence().mapNotNull { el ->
                    val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val title = obj.get("title")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    val link = (obj.get("url") ?: obj.get("link"))?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    val snippet = (obj.get("description") ?: obj.get("content") ?: obj.get("snippet"))
                        ?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    if (title.isBlank() || !link.startsWith("http")) null
                    else Triple(title, link, snippet)
                }.take(limit.coerceAtLeast(1)).toList()
            }
        }
        val blocks = Regex(
            """Title:\s*(.+?)\s*\nURL Source:\s*(\S+)\s*(?:\nDescription:\s*(.+?))?(?=\nTitle:|\z)""",
            RegexOption.DOT_MATCHES_ALL
        )
        return blocks.findAll(trimmed).mapNotNull { m ->
            val title = m.groupValues[1].trim()
            val link = m.groupValues[2].trim()
            val snippet = m.groupValues.getOrNull(3)?.trim().orEmpty()
            if (title.isBlank() || !link.startsWith("http")) null
            else Triple(title, link, snippet)
        }.take(limit.coerceAtLeast(1)).toList()
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

    /** Brave SERP 网页结果。 */
    fun parseBraveHtml(html: String, limit: Int): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        return doc.select(
            "div.snippet[data-type=web], div.snippet[data-sn-type=web], div#results div.snippet, div.fdb"
        ).asSequence().mapNotNull { el ->
            val a = el.selectFirst("a[href^=http], a.heading-serpresult, a.h") ?: return@mapNotNull null
            val title = a.text().trim()
            val link = a.absUrl("href").ifBlank { a.attr("href") }
            if (title.isBlank() || !link.startsWith("http")) return@mapNotNull null
            if (link.contains("brave.com/search", ignoreCase = true)) return@mapNotNull null
            val snippet = el.selectFirst(".snippet-description, .snippet-content, p")?.text()?.trim().orEmpty()
            Triple(title, link, snippet)
        }.distinctBy { it.second }.take(limit.coerceAtLeast(1)).toList()
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
            t.contains("验证码") ||
            t.contains("滑动验证") ||
            t.contains("请完成验证") ||
            t.contains("unusual traffic") ||
            (t.contains("enable javascript") && t.contains("challenge"))
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
