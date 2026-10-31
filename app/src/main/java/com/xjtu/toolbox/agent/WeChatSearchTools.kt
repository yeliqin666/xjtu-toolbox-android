package com.xjtu.toolbox.agent

import com.xjtu.toolbox.network.APP_UA
import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.TimeUnit

/**
 * `wechat_search`：搜狗微信搜公众号文章。DeepSeek 搜索覆盖不到公众号，这一档留在端上。
 *
 * 结果链接是搜狗跳转链，DeepSeek Fetch 读不了，所以 [readArticle] 交给 [DeepSeekWebTools] 的 `localFetch`：
 * 搜狗跳转链和 `mp.weixin.qq.com` 由这里用微信 UA 读，其余地址走 DeepSeek。
 */
internal class WeChatSearchTools : AgentTools {

    override val toolDefinitions: String = kotlinx.serialization.json.JsonArray(
        listOf(
            agentTool(
                "wechat_search",
                "搜微信公众号文章（搜狗微信），返回标题、URL、摘要；用 web_fetch 读原文。" +
                    "局限：收录不全、偏旧（新推文常搜不到）、相关度一般，结果只作线索，日期要看原文核对。" +
                    "书院、学院的通知和集体活动（讲座、志愿、晚会、报名）搜不到或不确定时，直接告诉用户去微信里搜对应公众号，别拿旧结果凑答案。",
                agentParams(
                    "query" to agentStrProp("关键词。"),
                    "limit" to agentIntProp("条数，默认 8，上限 $MAX_RESULTS。"),
                    required = listOf("query"),
                ),
            ),
        ),
    ).toString()

    override fun rateLimitKeyOf(toolName: String): String? = "wechat"

    override suspend fun execute(name: String, argsJson: String): String {
        if (name != "wechat_search") return ToolReply.notFound("tool", name)
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return search(args.argStr("query").orEmpty().trim(), args.argInt("limit") ?: 8)
    }

    /** 匿名 cookie 只在内存里，不和学校站点的登录态混在一起。 */
    private val client by lazy {
        AgentWeb.applyPublicNetworkPolicy(HttpClients.base.newBuilder())
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .cookieJar(com.xjtu.toolbox.network.InMemoryCookieJar())
            .addInterceptor { chain ->
                val request = chain.request()
                AgentWeb.requirePublicHttpUrl(request.url.toString())
                val b = request.newBuilder()
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                if (request.header("Referer").isNullOrBlank()) b.header("Referer", "https://weixin.sogou.com/")
                val response = chain.proceed(b.build())
                AgentWeb.requirePublicHttpUrl(response.request.url.toString())
                response
            }
            .build()
    }

    private class Page(val finalUrl: String, val html: String)

    private fun get(url: String, userAgent: String): Page? = runCatching {
        client.newCall(
            okhttp3.Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get().build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            if (AgentWeb.isBinaryContentType(resp.header("Content-Type"))) return@use null
            val bytes = resp.body.source().let { src ->
                src.request(AgentWeb.HTML_READ_BYTES)
                src.buffer.let { buf -> buf.readByteArray(minOf(buf.size, AgentWeb.HTML_READ_BYTES)) }
            }
            if (bytes.isEmpty()) null else Page(resp.request.url.toString(), String(bytes, Charsets.UTF_8))
        }
    }.getOrNull()

    private suspend fun search(query: String, limit: Int): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ToolReply.missing("query")
        val want = limit.coerceIn(1, MAX_RESULTS)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        try {
            // 先进首页拿一份 cookie，再搜；PC 版没结果时退手机版
            get("https://weixin.sogou.com/", SEARCH_UA)
            val rows = listOf(
                "https://weixin.sogou.com/weixin?type=2&ie=utf8&s_from=input&query=$encoded",
                "https://weixin.sogou.com/weixinwap?type=2&query=$encoded",
            ).firstNotNullOfOrNull { url ->
                get(url, SEARCH_UA)
                    ?.takeUnless { AgentWeb.looksLikeCaptcha(it.html) }
                    ?.let { parseResults(it.html, want, it.finalUrl) }
                    ?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            if (rows.isEmpty()) return@withContext ToolReply.empty("query: $query")
            buildString {
                append("「$query」｜搜狗微信｜${rows.size} 条\n")
                rows.forEachIndexed { i, (t, l, s) ->
                    append("[${i + 1}] $t｜$l")
                    if (s.isNotBlank()) append("｜${s.take(240)}")
                    append("\n")
                }
                append(ToolReply.EXTERNAL_DATA)
            }
        } catch (e: Exception) {
            ToolReply.failed("wechat_search", e.message)
        }
    }

    /** 微信结果必须选到 `li`，不能选外层 `.news-box`（全页只有一个）。 */
    private fun parseResults(body: String, limit: Int, baseUrl: String): List<Triple<String, String, String>> {
        val doc = org.jsoup.Jsoup.parse(body, baseUrl)
        return doc.select("ul.news-list > li, li[id^=sogou_vr_]").asSequence()
            .mapNotNull { el ->
                // 逐个选择器按优先级试：selectFirst("a, b") 按文档顺序返回，会先命中缩略图那个 a
                val a = TITLE_SELECTORS.firstNotNullOfOrNull { el.selectFirst(it) } ?: return@mapNotNull null
                val title = a.text().ifBlank { return@mapNotNull null }
                val link = a.absUrl("href").ifBlank { a.attr("href") }.trim()
                if (!link.startsWith("http")) return@mapNotNull null
                val snippet = el.selectFirst(".txt-info, .s-p")?.text().orEmpty()
                Triple(title, link, snippet)
            }
            .distinctBy { it.second }
            .take(limit)
            .toList()
    }

    /** 搜狗跳转链与公众号文章：用微信 UA 读，返回工具结果；别的地址返回 null 交给 DeepSeek。 */
    suspend fun readArticle(url: String): String? {
        if (!AgentWeb.isSogouJumpUrl(url) && !AgentWeb.isWeChatUrl(url)) return null
        return withContext(Dispatchers.IO) {
            try {
                AgentWeb.requirePublicHttpUrl(url)
                var page = get(AgentWeb.withSogouClickParams(url), WECHAT_UA)
                if ((page == null || isBlocked(page)) && AgentWeb.isSogouJumpUrl(url)) {
                    page = get(AgentWeb.withSogouClickParams(url, extraOffset = 28), WECHAT_UA) ?: get(url, APP_UA)
                }
                if (page == null || isBlocked(page)) return@withContext ToolReply.failed("web_fetch", "wechat_blocked")
                val doc = org.jsoup.Jsoup.parse(page.html, page.finalUrl)
                buildString {
                    append("标题：${doc.title().ifBlank { page.finalUrl }}\n")
                    append("URL：${page.finalUrl}\n\n正文：\n")
                    append(AgentWeb.truncateMarkdown(AgentWeb.htmlToMarkdown(page.html, page.finalUrl)).ifBlank { "页面无可提取正文。" })
                    append("\n\n" + ToolReply.EXTERNAL_DATA)
                }
            } catch (e: Exception) {
                ToolReply.failed("web_fetch", e.message)
            }
        }
    }

    private fun isBlocked(page: Page) = AgentWeb.looksLikeCaptcha(page.html) || AgentWeb.looksLikeWeChatBlock(page.html)

    private companion object {
        const val MAX_RESULTS = 20
        val TITLE_SELECTORS = listOf(".txt-box h3 a", "h3 a", "a[target=_blank]")
        const val SEARCH_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val WECHAT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50.2701(0x2800323B) NetType/WIFI Language/zh_CN"
    }
}
