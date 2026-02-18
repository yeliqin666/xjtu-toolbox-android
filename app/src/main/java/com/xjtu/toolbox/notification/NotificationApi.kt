package com.xjtu.toolbox.notification

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import okhttp3.brotli.BrotliInterceptor
import java.net.URI
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ==================== 数据类 ====================

data class Notification(
    val title: String,
    val link: String,
    val source: NotificationSource,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val date: LocalDate = LocalDate.now(),
    val isRead: Boolean = false
)

// ==================== 来源分类 ====================

enum class SourceCategory(val displayName: String) {
    TEACHING("教学管理"),
    ENGINEERING("工学"),
    SCIENCE("理学"),
    INFO("信息"),
    HUMANITIES("人文经管"),
    OTHER("综合");
}

// ==================== 通知来源 ====================

enum class NotificationSource(
    val displayName: String,
    val baseUrl: String,
    val category: SourceCategory
) {
    // ── 教学管理 ──
    JWC("教务处", "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm", SourceCategory.TEACHING),
    GS("研究生院", "https://gs.xjtu.edu.cn/tzgg.htm", SourceCategory.TEACHING),

    // ── 工学 ──
    ME("机械学院", "https://mec.xjtu.edu.cn/index/tzgg/bks.htm", SourceCategory.ENGINEERING),
    EE("电气学院", "https://ee.xjtu.edu.cn/jzxx/bks.htm", SourceCategory.ENGINEERING),
    EPE("能动学院", "https://epe.xjtu.edu.cn/index/tzgg.htm", SourceCategory.ENGINEERING),
    AERO("航天学院", "https://sae.xjtu.edu.cn/index/tzgg.htm", SourceCategory.ENGINEERING),
    MSE("材料学院", "https://mse.xjtu.edu.cn/xwgg/tzgg1.htm", SourceCategory.ENGINEERING),
    CLET("化工学院", "https://clet.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.ENGINEERING),
    HSCE("人居学院", "https://hsce.xjtu.edu.cn/xwgg/tzgg1.htm", SourceCategory.ENGINEERING),

    // ── 理学 ──
    MATH("数学学院", "https://math.xjtu.edu.cn/index/jxjw1.htm", SourceCategory.SCIENCE),
    PHY("物理学院", "https://phy.xjtu.edu.cn/glfw/tzgg.htm", SourceCategory.SCIENCE),
    CHEM("化学学院", "https://chem.xjtu.edu.cn/tzgg.htm", SourceCategory.SCIENCE),

    // ── 信息 ──
    SE("软件学院", "https://se.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.INFO),

    // ── 人文经管 ──
    SOM("管理学院", "https://som.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.HUMANITIES),
    RWXY("人文学院", "https://rwxy.xjtu.edu.cn/index/tzgg.htm", SourceCategory.HUMANITIES),
    SFS("外国语学院", "https://sfs.xjtu.edu.cn/glfw/jxjw.htm", SourceCategory.HUMANITIES),
    LAW("法学院", "https://fxy.xjtu.edu.cn/index/tzgg.htm", SourceCategory.HUMANITIES),
    SEF("经金学院", "https://sef.xjtu.edu.cn/rcpy/bks/jxtz1.htm", SourceCategory.HUMANITIES),
    SPPA("公管学院", "https://sppa.xjtu.edu.cn/xwxx/bksjw.htm", SourceCategory.HUMANITIES),
    MARX("马克思主义学院", "https://marx.xjtu.edu.cn/xwgg1/tzgg.htm", SourceCategory.HUMANITIES),
    XMTXY("新媒体学院", "https://xmtxy.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.HUMANITIES),

    // ── 综合 ──
    SLST("生命学院", "https://slst.xjtu.edu.cn/ggl/tzgg.htm", SourceCategory.OTHER),
    QXS("钱学森书院", "https://bjb.xjtu.edu.cn/xydt/tzgg.htm", SourceCategory.OTHER),
    FTI("未来技术学院", "https://wljsxy.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.OTHER),
    XSC("学生处", "https://xsc.xjtu.edu.cn/xgdt/tzgg.htm", SourceCategory.OTHER);

    companion object {
        fun fromDisplayName(name: String): NotificationSource? =
            entries.find { it.displayName == name }

        fun byCategory(cat: SourceCategory): List<NotificationSource> =
            entries.filter { it.category == cat }
    }
}

// ==================== 反爬虫处理 ====================

private const val TAG = "NotificationApi"

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val domainCookies = mutableMapOf<String, String>()

/** 域名级别失败缓存：记录 DNS/连接超时失败的域名及失败时间戳，避免重复尝试 */
private val failedDomains = ConcurrentHashMap<String, Long>()
private const val DOMAIN_FAILURE_TTL_MS = 5 * 60 * 1000L // 5 分钟内不重试失败域名

private fun isDomainFailed(domain: String): Boolean {
    val failedAt = failedDomains[domain] ?: return false
    if (System.currentTimeMillis() - failedAt > DOMAIN_FAILURE_TTL_MS) {
        failedDomains.remove(domain)
        return false
    }
    return true
}

private fun markDomainFailed(domain: String) {
    failedDomains[domain] = System.currentTimeMillis()
}

private fun fetchDocumentWithChallenge(client: OkHttpClient, url: String): Document {
    val domain = URI(url).host

    // 检查域名是否在失败缓存中
    if (isDomainFailed(domain)) {
        throw java.net.UnknownHostException("Domain $domain is cached as failed")
    }

    val reqBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
    domainCookies[domain]?.let { reqBuilder.header("Cookie", it) }

    val response = try {
        client.newCall(reqBuilder.build()).execute()
    } catch (e: Exception) {
        // DNS 失败或连接超时，标记域名为失败
        if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException) {
            markDomainFailed(domain)
        }
        throw e
    }
    // 非 challenge 的 4xx/5xx 直接报错
    if (response.code == 404 || response.code >= 500) {
        response.close()
        throw java.io.IOException("HTTP ${response.code} for $url")
    }
    val html = response.body?.string() ?: ""

    if (!html.contains("dynamic_challenge")) {
        return Jsoup.parse(html, url)
    }

    val challengeId = Regex("""var\s+challengeId\s*=\s*"([^"]+)"""").find(html)
        ?.groupValues?.get(1) ?: return Jsoup.parse(html, url)
    val answer = Regex("""var\s+answer\s*=\s*(\d+)""").find(html)
        ?.groupValues?.get(1) ?: return Jsoup.parse(html, url)

    val baseUri = URI(url).let { "${it.scheme}://${it.host}" }
    val challengeJson = """{"challenge_id":"$challengeId","answer":$answer,"browser_info":{"userAgent":"$USER_AGENT","language":"zh-CN","platform":"Win32","cookieEnabled":true,"hardwareConcurrency":4,"deviceMemory":8,"timezone":"Asia/Shanghai"}}"""

    val challengeReq = Request.Builder()
        .url("$baseUri/dynamic_challenge")
        .post(challengeJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("User-Agent", USER_AGENT)
        .build()

    val challengeResp = client.newCall(challengeReq).execute()
    val challengeBody = challengeResp.body?.string() ?: return Jsoup.parse(html, url)

    val result = try {
        JsonParser.parseString(challengeBody).asJsonObject
    } catch (_: Exception) {
        return Jsoup.parse(html, url)
    }

    if (result.get("success")?.asBoolean != true) {
        return Jsoup.parse(html, url)
    }

    val clientId = result.get("client_id")?.asString ?: return Jsoup.parse(html, url)
    domainCookies[domain] = "client_id=$clientId"

    val retryReq = Request.Builder()
        .url(url)
        .header("Cookie", "client_id=$clientId")
        .header("User-Agent", USER_AGENT)
        .build()
    val retryResp = client.newCall(retryReq).execute()
    // 检查 HTTP 状态码：404/5xx 等直接抛出，避免解析错误页面
    if (retryResp.code == 404 || retryResp.code >= 500) {
        retryResp.close()
        throw java.io.IOException("HTTP ${retryResp.code} for $url")
    }
    val retryHtml = retryResp.body?.string() ?: ""
    return Jsoup.parse(retryHtml, url)
}

// ==================== 爬虫接口 ====================

private interface NotificationCrawler {
    fun fetch(page: Int): List<Notification>
}

// ==================== 教务处爬虫 ====================

private class JwcCrawler(private val client: OkHttpClient) : NotificationCrawler {

    override fun fetch(page: Int): List<Notification> {
        val allNotifications = mutableListOf<Notification>()
        var url = NotificationSource.JWC.baseUrl

        for (i in 0 until page) {
            val doc = fetchDocumentWithChallenge(client, url)
            val list = doc.select("#ny-main ul.list > li")
            for (li in list) {
                val aTag = li.selectFirst("a") ?: continue
                val title = aTag.text()
                val href = aTag.attr("href")
                val link = resolveUrl(url, href)
                val dateStr = li.selectFirst("span")?.text() ?: ""
                val date = parseDateSafe(dateStr)
                val tagText = aTag.selectFirst("i")?.text()?.trim('[', ']') ?: ""
                val tags = if (tagText.isNotEmpty()) listOf(tagText) else emptyList()
                allNotifications.add(
                    Notification(title = title, link = link, source = NotificationSource.JWC, date = date, tags = tags)
                )
            }
            val nextHref = doc.selectFirst("span.p_next a")?.attr("href") ?: break
            if (nextHref.isEmpty()) break
            url = resolveUrl(url, nextHref)
        }
        return allNotifications.distinctBy { Triple(it.title, it.link, it.source) }
    }
}

// ==================== 研究生院爬虫 ====================

private class GsCrawler(private val client: OkHttpClient) : NotificationCrawler {

    private data class SubColumn(val url: String, val tag: String)

    private val subColumns = listOf(
        SubColumn("https://gs.xjtu.edu.cn/tzgg/zsgz.htm", "招生工作"),
        SubColumn("https://gs.xjtu.edu.cn/tzgg/pygz.htm", "培养工作"),
        SubColumn("https://gs.xjtu.edu.cn/tzgg/gjjl.htm", "国际交流"),
        SubColumn("https://gs.xjtu.edu.cn/tzgg/xwgz.htm", "学位工作"),
        SubColumn("https://gs.xjtu.edu.cn/tzgg/yggz.htm", "研工工作"),
        SubColumn("https://gs.xjtu.edu.cn/tzgg/zhgz.htm", "综合工作")
    )

    override fun fetch(page: Int): List<Notification> {
        val allNotifications = mutableListOf<Notification>()
        for (col in subColumns) {
            allNotifications.addAll(fetchSubColumn(col.url, col.tag, page))
        }
        return allNotifications.distinctBy { Triple(it.title, it.link, it.source) }
    }

    private fun fetchSubColumn(startUrl: String, tagName: String, pages: Int): List<Notification> {
        val notifications = mutableListOf<Notification>()
        var url = startUrl

        for (i in 0 until pages) {
            val doc = fetchDocumentWithChallenge(client, url)
            val list = doc.select("div.list_right_con > ul > li")
            for (li in list) {
                val aTag = li.selectFirst("a") ?: continue
                val title = aTag.text()
                val href = aTag.attr("href")
                val link = resolveUrl(url, href)
                val dateStr = li.selectFirst("span.time")?.text() ?: ""
                val date = parseDateSafe(dateStr)
                notifications.add(
                    Notification(title = title, link = link, source = NotificationSource.GS, date = date, tags = listOf(tagName))
                )
            }
            val nextHref = doc.selectFirst("a:containsOwn(下页)")?.attr("href") ?: break
            if (nextHref.isEmpty()) break
            url = resolveUrl(url, nextHref)
        }
        return notifications
    }
}

// ==================== 通用 XJTU 学院爬虫 ====================

private class GenericXjtuCrawler(
    private val client: OkHttpClient,
    private val source: NotificationSource
) : NotificationCrawler {

    companion object {
        val LIST_SELECTORS = listOf(
            // 常见 XJTU 模板
            "div.list_rnr > ul > li",
            "#ny-main ul.list > li",
            "div.list_right_con > ul > li",
            "main ul.news_list > li",
            "ul.news_list > li",
            ".main_conRCR ul > li",
            "div.list_con > ul > li",
            ".news-list ul > li",
            "div.content ul.list > li",
            "div.article-list ul > li",
            "ul.clearfix > li",
            "ul.wp_article_list > li",
            "div.right-list ul > li",
            ".list_box ul > li",
            // WisdPower CMS / 博达 / ZZNode 系列
            "div.list ul > li",
            "div.list > ul > li",
            ".news_list ul > li",
            "div.newslist ul > li",
            "div.right_con ul > li",
            "#container ul > li",
            "div.content_area ul > li",
            "div.list_main > ul > li",

        )

        val NEXT_SELECTORS = listOf(
            "span.p_next a",
            "a:containsOwn(下一页)",
            "a:containsOwn(下页)",
            "a.next",
            ".pagination a.next",
            "a:containsOwn(>)",
            "a:containsOwn(Next)",
        )

        /** 主 URL 不可用时，尝试的备选路径（覆盖 XJTU 各学院 CMS 常见变体） */
        val FALLBACK_PATHS = listOf(
            // 标准路径
            "/xwgg/tzgg.htm",
            "/xwgg/tzgg1.htm",
            // 首页下级路径
            "/index/tzgg.htm",
            "/index/tzgg1.htm",
            "/index/tzgg/bks.htm",
            "/index/jxjw1.htm",
            // 新闻/通知类路径
            "/xwzx/tzgg.htm",
            "/xwxx/tzgg.htm",
            "/xwxx/bksjw.htm",
            "/xwgg1/tzgg.htm",
            "/dzxxxb/tzgg.htm",
            // 教学事务类路径
            "/jzxx/bks.htm",
            "/glfw/jxjw.htm",
            "/rcpy/bks/jxtz1.htm",
            // 公告/学工类路径
            "/ggl/tzgg.htm",
            "/xgdt/tzgg.htm",
            "/xydt/tzgg.htm",
            "/glfw/tzgg.htm",
            // 传统路径
            "/tzgg.htm",
            "/xyxw/tzgg.htm",
            "/xwgg/xytz.htm",
            "/xytz.htm",
            "/xwgg.htm",
            "/notice.htm",
            "/jxxx/jxtz2.htm",
        )
    }

    override fun fetch(page: Int): List<Notification> {
        val allNotifications = mutableListOf<Notification>()
        var url = source.baseUrl

        // 检查域名是否已知失败（DNS/超时），如是则直接跳过
        val domain = try { URI(url).host } catch (_: Exception) { null }
        if (domain != null && isDomainFailed(domain)) {
            Log.d(TAG, "GenericCrawler[${source.displayName}] skipping - domain $domain cached as failed")
            return emptyList()
        }

        // 尝试主 URL
        var doc = tryFetchDoc(url)

        // 主 URL 无内容，尝试备选路径（仅在域名可达时）
        if (doc == null || extractItems(doc).isEmpty()) {
            // 如果域名本身失败（DNS/超时），不再尝试备选路径
            if (domain != null && isDomainFailed(domain)) {
                return emptyList()
            }
            val baseHost = try { val u = URI(url); "${u.scheme}://${u.host}" } catch (_: Exception) { null }
            if (baseHost != null) {
                for (path in FALLBACK_PATHS) {
                    val fallbackUrl = "$baseHost$path"
                    if (fallbackUrl == url) continue
                    val fallbackDoc = tryFetchDoc(fallbackUrl)
                    if (fallbackDoc != null && extractItems(fallbackDoc).isNotEmpty()) {
                        doc = fallbackDoc
                        url = fallbackUrl
                        Log.d(TAG, "GenericCrawler[${source.displayName}] fallback hit: $url")
                        break
                    }
                    // 域名失败后立即停止所有备选路径
                    if (domain != null && isDomainFailed(domain)) break
                }
            }
        }

        if (doc == null) return emptyList()

        for (i in 0 until page) {
            if (i > 0) {
                doc = tryFetchDoc(url)
                if (doc == null) break
            }

            val items = extractItems(doc!!)
            if (items.isEmpty()) {
                Log.w(TAG, "GenericCrawler[${source.displayName}] no items at $url (page $i)")
                // 第一页就没有内容，尝试暴力搜索
                if (i == 0) {
                    val bruteItems = bruteForceExtract(doc!!, url)
                    Log.d(TAG, "GenericCrawler[${source.displayName}] brute force yielded ${bruteItems.size} items")
                    allNotifications.addAll(bruteItems)
                }
                break
            }

            Log.d(TAG, "GenericCrawler[${source.displayName}] page $i: ${items.size} items from $url")
            for (el in items) {
                val notification = parseListItem(el, url) ?: continue
                allNotifications.add(notification)
            }

            // 翻页
            var nextHref: String? = null
            for (selector in NEXT_SELECTORS) {
                nextHref = doc!!.selectFirst(selector)?.attr("href")
                if (!nextHref.isNullOrBlank()) break
            }
            if (nextHref.isNullOrBlank()) break
            url = resolveUrl(url, nextHref)
        }

        return allNotifications.distinctBy { Triple(it.title, it.link, it.source) }
    }

    private fun tryFetchDoc(url: String): Document? {
        return try {
            val doc = fetchDocumentWithChallenge(client, url)
            val bodyLen = doc.body()?.text()?.length ?: 0
            if (bodyLen < 50) null else doc
        } catch (e: Exception) {
            Log.w(TAG, "GenericCrawler[${source.displayName}] fetch error at $url: ${e.message}")
            null
        }
    }

    /** 判断一个 li 元素是否包含 XJTU CMS 通知链接（/info/XXXX/YYYYY.htm 模式） */
    private fun hasInfoLink(el: org.jsoup.nodes.Element): Boolean {
        val a = el.selectFirst("a[href]") ?: return false
        val href = a.attr("href")
        // XJTU CMS 通知详情链接固定为: /info/栏目ID/文章ID.htm
        // 导航链接永远不使用 /info/ 路径
        return href.contains("/info/") || href.contains("content.jsp")
    }

    private fun extractItems(doc: Document): List<org.jsoup.nodes.Element> {
        // ── 策略一：找含 /info/ 链接最多的 <ul>/<ol> ──
        // XJTU CMS 通知详情链接固定为 /info/栏目ID/文章ID.htm
        // 导航链接永远不匹配此模式，所以这是 100% 可靠的结构性区分
        data class CandidateList(
            val items: List<org.jsoup.nodes.Element>,   // 仅含 /info/ 链接的条目
            val infoCount: Int
        )

        val candidates = mutableListOf<CandidateList>()

        for (selector in LIST_SELECTORS) {
            val allItems = doc.select(selector)
            if (allItems.size < 3) continue
            // 精确过滤：只保留含 /info/ 或 content.jsp 链接的条目
            val infoItems = allItems.filter { hasInfoLink(it) }
            if (infoItems.size >= 3) {
                candidates.add(CandidateList(infoItems, infoItems.size))
            }
        }

        // 选含 /info/ 链接最多的列表（已过滤，不含导航/菜单条目）
        val best = candidates.maxByOrNull { it.infoCount }
        if (best != null) {
            Log.d(TAG, "GenericCrawler[${source.displayName}] extractItems: /info/ strategy matched ${best.infoCount} items")
            return best.items
        }

        // ── 策略二（兜底）：日期密度 ──
        // 针对极少数不使用标准 /info/ 路径的页面
        for (selector in LIST_SELECTORS) {
            val items = doc.select(selector)
            if (items.size < 3) continue
            val dateCount = items.count { Regex("""\d{4}[-./]\d{1,2}[-./]\d{1,2}""").containsMatchIn(it.text()) }
            if (dateCount.toDouble() / items.size >= 0.5 && dateCount >= 3) {
                Log.d(TAG, "GenericCrawler[${source.displayName}] extractItems: date density fallback matched ${items.size} items")
                return items
            }
        }

        Log.w(TAG, "GenericCrawler[${source.displayName}] extractItems: no items found")
        return emptyList()
    }

    /** 暴力模式：找页面中含 /info/ 链接最多的列表 */
    private fun bruteForceExtract(doc: Document, baseUrl: String): List<Notification> {
        val candidates = doc.select("ul, ol").mapNotNull { ul ->
            val lis = ul.select("> li").filter { li -> hasInfoLink(li) }
            if (lis.size >= 3) lis else null
        }.maxByOrNull { it.size } ?: return emptyList()

        Log.d(TAG, "GenericCrawler[${source.displayName}] brute force found ${candidates.size} items")
        return candidates.mapNotNull { parseListItem(it, baseUrl) }
    }

    private fun parseListItem(el: org.jsoup.nodes.Element, baseUrl: String): Notification? {
        val aTag = el.selectFirst("a[href]") ?: return null
        val href = aTag.attr("href")
        if (href.isBlank() || href == "#" || href.startsWith("javascript")) return null

        val title = aTag.attr("title").ifBlank {
            aTag.selectFirst("p:nth-child(2)")?.text()
                ?: aTag.selectFirst("p")?.text()
                ?: aTag.ownText().ifBlank { aTag.text() }
        }.trim()
        if (title.isBlank() || title.length < 4) return null

        val link = resolveUrl(baseUrl, href)
        val dateStr = el.selectFirst("span.time")?.text()
            ?: el.selectFirst("span.date")?.text()
            ?: el.selectFirst("span:last-child")?.text()
            ?: aTag.selectFirst("p:first-child span")?.text()
            ?: el.selectFirst("em")?.text()
            ?: el.selectFirst("i:not(:has(*))")?.text()
            ?: ""
        val date = parseDateSafe(dateStr)

        val tagText = aTag.selectFirst("i")?.text()?.trim('[', ']', '【', '】') ?: ""
        val tags = if (tagText.isNotEmpty()) listOf(tagText) else emptyList()

        return Notification(title = title, link = link, source = source, date = date, tags = tags)
    }
}

// ==================== 工具函数 ====================

private fun resolveUrl(baseUrl: String, relative: String): String {
    return try {
        URL(URL(baseUrl), relative).toString()
    } catch (_: Exception) {
        relative
    }
}

private fun parseDateSafe(dateStr: String): LocalDate {
    return try {
        val cleaned = dateStr.trim().replace('/', '-').replace('.', '-')
        val match = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(cleaned)
        if (match != null) {
            val (y, m, d) = match.destructured
            LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        } else {
            LocalDate.now()
        }
    } catch (_: Exception) {
        LocalDate.now()
    }
}

// ==================== API 类 ====================

class NotificationApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val crawlers: Map<NotificationSource, NotificationCrawler> = buildMap {
        put(NotificationSource.JWC, JwcCrawler(client))
        put(NotificationSource.GS, GsCrawler(client))
        NotificationSource.entries.forEach { source ->
            if (source !in this) {
                put(source, GenericXjtuCrawler(client, source))
            }
        }
    }

    fun getNotifications(source: NotificationSource, page: Int = 1): List<Notification> {
        val crawler = crawlers[source]
            ?: throw IllegalArgumentException("不支持的通知来源: $source")
        return crawler.fetch(page)
    }

    fun getMergedNotifications(sources: List<NotificationSource>, page: Int = 1): List<Notification> {
        return runBlocking {
            coroutineScope {
                sources.map { source ->
                    async(Dispatchers.IO) {
                        runCatching { getNotifications(source, page) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }.sortedByDescending { it.date }
    }

    fun getAllNotifications(page: Int = 1): List<Notification> {
        return getMergedNotifications(NotificationSource.entries, page)
    }

    /** 清除域名失败缓存（例如切换网络后调用） */
    fun clearFailedDomainCache() {
        failedDomains.clear()
    }
}
