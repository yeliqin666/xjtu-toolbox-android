package com.xjtu.toolbox.notification

import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import okhttp3.brotli.BrotliInterceptor
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

/** 某一页的抓取结果。[hasMore] 表示站点分页里还有下一页，不是「这一页是不是空的」。 */
data class NotificationPage(
    val items: List<Notification>,
    val hasMore: Boolean,
)

data class MergedNotificationPage(
    val items: List<Notification>,
    val skipped: Set<NotificationSource>,
    val hasMore: Boolean,
)

// ==================== 来源分类 ====================

enum class SourceCategory(val displayName: String) {
    GENERAL("综合"),
    ENGINEERING("工学"),
    SCIENCE("理学"),
    HUMANITIES("人文经管");
}

// ==================== 通知来源 ====================

enum class NotificationSource(
    val displayName: String,
    val baseUrl: String,
    val category: SourceCategory
) {
    // ── 综合（校级部门） ──
    JWC("教务处", "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm", SourceCategory.GENERAL),
    GS("研究生院", "https://gs.xjtu.edu.cn/tzgg.htm", SourceCategory.GENERAL),
    QXS("钱学森书院", "https://bjb.xjtu.edu.cn/xydt/tzgg.htm", SourceCategory.GENERAL),
    CY("仲英书院", "https://cy.xjtu.edu.cn/xwdt/tzgg.htm", SourceCategory.GENERAL),
    PEC("实践教学中心", "https://pec.xjtu.edu.cn/xxgg/tzgg.htm", SourceCategory.GENERAL),
    FTI("未来技术学院", "https://wljsxy.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.GENERAL),
        XSC("学生处", "https://xsc.xjtu.edu.cn/xgdt/tzgg.htm", SourceCategory.GENERAL),
    OA("OA 通知", "https://oa.xjtu.edu.cn/zxgg_index.jsp", SourceCategory.GENERAL),

    // ── 工学 ──
    // 电信学部「更多」指向的 tzgg.htm 是停更栏目；首页通知条才是仍在更新的 1005 栏。
    EIEUG("电信学部", "https://eieug.xjtu.edu.cn/", SourceCategory.ENGINEERING),
    ME("机械学院", "https://mec.xjtu.edu.cn/index/tzgg/bks.htm", SourceCategory.ENGINEERING),
    EE("电气学院", "https://ee.xjtu.edu.cn/jzxx/bks.htm", SourceCategory.ENGINEERING),
    EPE("能动学院", "https://epe.xjtu.edu.cn/index/tzgg.htm", SourceCategory.ENGINEERING),
    AERO("航天学院", "https://sae.xjtu.edu.cn/index/tzgg.htm", SourceCategory.ENGINEERING),
    MSE("材料学院", "https://mse.xjtu.edu.cn/xwgg/tzgg1.htm", SourceCategory.ENGINEERING),
    CLET("化工学院", "https://clet.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.ENGINEERING),
    HSCE("人居学院", "https://hsce.xjtu.edu.cn/xwgg/tzgg1.htm", SourceCategory.ENGINEERING),
    SE("软件学院", "https://se.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.ENGINEERING),

    // ── 理学 ──
    MATH("数学学院", "https://math.xjtu.edu.cn/index/jxjw1.htm", SourceCategory.SCIENCE),
    PHY("物理学院", "https://phy.xjtu.edu.cn/glfw/tzgg.htm", SourceCategory.SCIENCE),
    CHEM("化学学院", "https://chem.xjtu.edu.cn/tzgg.htm", SourceCategory.SCIENCE),
    SLST("生命学院", "https://slst.xjtu.edu.cn/ggl/tzgg.htm", SourceCategory.SCIENCE),

    // ── 人文经管 ──
    SOM("管理学院", "https://som.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.HUMANITIES),
    RWXY("人文学院", "https://rwxy.xjtu.edu.cn/index/tzgg.htm", SourceCategory.HUMANITIES),
    SFS("外国语学院", "https://sfs.xjtu.edu.cn/glfw/jxjw.htm", SourceCategory.HUMANITIES),
    LAW("法学院", "https://fxy.xjtu.edu.cn/index/tzgg.htm", SourceCategory.HUMANITIES),
    SEF("经金学院", "https://sef.xjtu.edu.cn/rcpy/bks/jxtz1.htm", SourceCategory.HUMANITIES),
    SPPA("公管学院", "https://sppa.xjtu.edu.cn/xwxx/bksjw.htm", SourceCategory.HUMANITIES),
    MARX("马克思主义学院", "https://marx.xjtu.edu.cn/xwgg1/tzgg.htm", SourceCategory.HUMANITIES),
    XMTXY("新媒体学院", "https://xmtxy.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.HUMANITIES);

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

/** client_id 缓存：域名 -> (client_id, 取得时刻)。服务端 Max-Age=86400，这里同样按 1 天过期。 */
private val domainCookies = ConcurrentHashMap<String, Pair<String, Long>>()
private const val CLIENT_ID_TTL_MS = 24 * 60 * 60 * 1000L

private fun cachedClientId(domain: String): String? {
    val (id, at) = domainCookies[domain] ?: return null
    if (System.currentTimeMillis() - at > CLIENT_ID_TTL_MS) {
        domainCookies.remove(domain)
        return null
    }
    return id
}

/**
 * 挑战页解析结果。
 * [requiresHash] 为 true 表示新版页面（给 a/b/operator，需自算 answer 并附 hash）；
 * false 表示旧版（页面直接给 answer）。
 */
private data class WebsiteChallenge(
    val challengeId: String,
    val answer: Int?,
    val requiresHash: Boolean
)

private val CHALLENGE_ID_RE = Regex("""\b(?:var|let|const)?\s*challengeId\s*=\s*['"]([^'"]+)['"]""")
private val CHALLENGE_ANSWER_RE = Regex("""\b(?:var|let|const)?\s*answer\s*=\s*(-?\d+)\b""")
private val CHALLENGE_A_RE = Regex("""\b(?:var|let|const)?\s*a\s*=\s*(-?\d+)\b""")
private val CHALLENGE_B_RE = Regex("""\b(?:var|let|const)?\s*b\s*=\s*(-?\d+)\b""")
private val CHALLENGE_OP_RE = Regex("""\b(?:var|let|const)?\s*operator\s*=\s*['"]([+\-*])['"]""")

/**
 * 复现挑战页里的 simpleHash：`hash = ((hash << 5) - hash) + charCode`，即 hash*31+c，
 * 每轮按 JS 位运算截断为 32 位有符号，最后取绝对值。
 * Kotlin 的 Int 本身就是 32 位有符号，溢出行为与 JS 的 `hash & hash` 一致，直接算即可。
 * Kotlin String 按 UTF-16 code unit 遍历，与 JS charCodeAt 语义相同。
 */
internal fun javascriptSimpleHash(value: String): Long {
    var hash = 0
    for (c in value) hash = hash * 31 + c.code
    return kotlin.math.abs(hash.toLong())
}

/** 从挑战页 HTML 中解析挑战参数；不是挑战页则返回 null。 */
private fun extractChallenge(html: String): WebsiteChallenge? {
    if (html.isEmpty()) return null
    val scripts = Jsoup.parse(html).select("script").map { it.data() }.filter { it.isNotBlank() }
    for (script in scripts) {
        val id = CHALLENGE_ID_RE.find(script)?.groupValues?.get(1) ?: continue

        // 新版：页面给算式，自己算。只允许 + - * 三种，不执行站点返回的 JS。
        val a = CHALLENGE_A_RE.find(script)?.groupValues?.get(1)?.toIntOrNull()
        val b = CHALLENGE_B_RE.find(script)?.groupValues?.get(1)?.toIntOrNull()
        val op = CHALLENGE_OP_RE.find(script)?.groupValues?.get(1)
        if (a != null && b != null && op != null) {
            val answer = when (op) {
                "+" -> a + b
                "-" -> a - b
                else -> a * b
            }
            return WebsiteChallenge(id, answer, requiresHash = true)
        }

        // 旧版：answer 可能在另一个 script 标签里，所以要扫全部脚本。
        for (candidate in scripts) {
            val ans = CHALLENGE_ANSWER_RE.find(candidate)?.groupValues?.get(1)?.toIntOrNull()
            if (ans != null) return WebsiteChallenge(id, ans, requiresHash = false)
        }
        return WebsiteChallenge(id, null, requiresHash = false)
    }
    return null
}

private fun buildChallengeBody(challenge: WebsiteChallenge): String {
    val answer = challenge.answer
    return if (challenge.requiresHash) {
        val hash = javascriptSimpleHash("${challenge.challengeId}$answer${USER_AGENT.take(10)}")
        """{"challenge_id":"${challenge.challengeId}","answer":$answer,""" +
            """"browser_info":{"userAgent":"$USER_AGENT","language":"zh-CN","platform":"Win32",""" +
            """"screen":{"width":1920,"height":1080,"colorDepth":24},""" +
            """"timezoneOffset":-480,"hasTouchEvents":false},"hash":$hash}"""
    } else {
        """{"challenge_id":"${challenge.challengeId}","answer":$answer,""" +
            """"browser_info":{"cookieEnabled":true,"deviceMemory":8,"hardwareConcurrency":4,""" +
            """"language":"zh-CN","platform":"Win32","timezone":"Asia/Shanghai","userAgent":"$USER_AGENT"}}"""
    }
}

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

/** GET 一次目标页，带上已缓存的 client_id。404/5xx 抛异常，DNS/超时标记域名失败。 */
private fun getPage(client: OkHttpClient, url: String, domain: String): String {
    val reqBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
    cachedClientId(domain)?.let { reqBuilder.header("Cookie", "client_id=$it") }

    val response = try {
        client.newCall(reqBuilder.build()).execute()
    } catch (e: Exception) {
        if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException) {
            markDomainFailed(domain)
        }
        throw e
    }
    response.use {
        if (it.code == 404 || it.code >= 500) {
            throw java.io.IOException("HTTP ${it.code} for $url")
        }
        return it.body?.string() ?: ""
    }
}

/**
 * 提交一次动态挑战，成功返回 client_id。
 * 服务端偶发返回 `{"success":false,"message":"Invalid challenge"}`（浏览器里也会遇到，刷新即可），
 * 所以调用方需要重试。
 */
private fun solveChallenge(client: OkHttpClient, url: String, challenge: WebsiteChallenge): String? {
    val baseUri = URI(url).let { "${it.scheme}://${it.host}" }
    val req = Request.Builder()
        .url("$baseUri/dynamic_challenge")
        .post(buildChallengeBody(challenge).toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("User-Agent", USER_AGENT)
        .header("Referer", url)
        .header("X-Requested-With", "XMLHttpRequest")
        .build()

    val body = client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            Log.w(TAG, "challenge HTTP ${resp.code} for $url")
            return null
        }
        resp.body?.string() ?: return null
    }

    val json = try {
        body.safeParseJsonObject()
    } catch (_: Exception) {
        Log.w(TAG, "challenge response not JSON for $url")
        return null
    }
    if (json.get("success")?.asBoolean != true) {
        Log.w(TAG, "challenge rejected for $url: ${json.get("message")?.asString}")
        return null
    }
    return json.get("client_id")?.asString?.takeIf { it.isNotBlank() }
}

/**
 * 取通知列表页，必要时通过站点的人机验证。
 *
 * 兼容两版挑战页：旧版直接给 `answer`；新版给 `a`/`b`/`operator` 要自己算，并附 `hash`
 * 与新结构的 `browser_info`。通过后 `client_id` 按域名缓存 1 天，后续请求直接带上。
 */
private fun fetchDocumentWithChallenge(client: OkHttpClient, url: String): Document {
    val domain = URI(url).host

    if (isDomainFailed(domain)) {
        throw java.net.UnknownHostException("Domain $domain is cached as failed")
    }

    var html = getPage(client, url, domain)
    if (!html.contains("dynamic_challenge")) {
        return Jsoup.parse(html, url)
    }

    // 缓存的 client_id 已失效（否则不会再收到挑战页），清掉避免后续复用
    domainCookies.remove(domain)

    // 服务端偶发 Invalid challenge，重试一轮；每轮都要重新取页面，challengeId 是一次性的
    repeat(2) { attempt ->
        if (attempt > 0) {
            html = getPage(client, url, domain)
            if (!html.contains("dynamic_challenge")) return Jsoup.parse(html, url)
        }

        val challenge = extractChallenge(html)
        if (challenge == null) {
            Log.w(TAG, "challenge page not recognized for $url")
            return Jsoup.parse(html, url)
        }
        if (challenge.answer == null) {
            Log.w(TAG, "challenge has no answer for $url, page format may have changed")
            return Jsoup.parse(html, url)
        }

        val clientId = solveChallenge(client, url, challenge)
        if (clientId != null) {
            domainCookies[domain] = clientId to System.currentTimeMillis()
            val retryHtml = getPage(client, url, domain)
            return Jsoup.parse(retryHtml, url)
        }
    }

    Log.w(TAG, "challenge failed after retry for $url")
    return Jsoup.parse(html, url)
}

// ==================== 爬虫接口 ====================

private interface NotificationCrawler {
    fun fetchPage(page: Int): NotificationPage
}

// ==================== 教务处爬虫 ====================

// ==================== 通用 XJTU 学院爬虫 ====================

private class GenericXjtuCrawler(
    private val client: OkHttpClient,
    private val source: NotificationSource
) : NotificationCrawler {

    @Volatile private var lastPage = 0
    @Volatile private var nextAbsUrl: String? = null
    @Volatile private var resolvedListUrl: String? = null

    companion object {
        val LIST_SELECTORS = listOf(
            // 常见 XJTU 模板
            "div.list_rnr > ul > li",
            "div.list_rlb > ul > li",       // XSC / CLET / MARX 模板
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
            "div.tzgg > ul > li",           // SOM / SAE 模板
            "ul.txtList > li",              // MEC 模板
            "div.nyrCon ul > li",           // MEC 备选
            // WisdPower CMS / 博达 / ZZNode 系列
            "div.list ul > li",
            "div.list > ul > li",
            ".news_list ul > li",
            "div.newslist ul > li",
            "div.right_con ul > li",
            "#container ul > li",
            "div.content_area ul > li",
            "div.list_main > ul > li",
            "ul.listg > li",                 // PEC 实践教学中心
            "div.tzgg_lr ul > li",           // 电信学部首页通知条
            "li[id^=line_u]",                // 仲英书院 VSB 静态翻页列表

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
            "/xxgg/tzgg.htm",
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

        // ── 预编译正则（避免每次调用重新编译） ──
        private val FULL_DATE_RE = Regex("""\d{4}[-./]\d{1,2}[-./]\d{1,2}""")
        private val YEAR_MONTH_RE = Regex("""(\d{4})[-./](\d{1,2})""")
        private val MONTH_DAY_RE = Regex("""(\d{1,2})[-./](\d{1,2})""")
        private val YEAR_ONLY_RE = Regex("""\b(\d{4})\b""")
        private val SMALL_NUM_RE = Regex("""\b(\d{1,2})\b""")
        private val DIGITS_RE = Regex("""\d+""")
        /** CMS 详情、相对路径 info/栏/文.htm，以及书院站点外链的微信稿。 */
        private val ARTICLE_HREF_RE = Regex(
            """(?:^|/)info/\d+/|content\.jsp|mp\.weixin\.qq\.com/s/""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun fetchPage(page: Int): NotificationPage {
        if (page < 1) return NotificationPage(emptyList(), false)

        val domain = try { URI(source.baseUrl).host } catch (_: Exception) { null }
        if (domain != null && isDomainFailed(domain)) {
            Log.d(TAG, "GenericCrawler[${source.displayName}] skipping - domain $domain cached as failed")
            return NotificationPage(emptyList(), false)
        }

        if (page == 1) {
            lastPage = 0
            nextAbsUrl = null
            resolvedListUrl = null
        }

        // 连续翻页：只跟「下页」，不要从首页再走一遍。
        if (page > 1 && page == lastPage + 1) {
            val url = nextAbsUrl ?: return NotificationPage(emptyList(), false)
            val doc = tryFetchDoc(url) ?: return NotificationPage(emptyList(), false)
            return finishPage(doc, url, page)
        }

        var url = resolvedListUrl ?: source.baseUrl
        var doc = tryFetchDoc(url)

        if (page == 1 && (doc == null || extractItems(doc).isEmpty())) {
            if (domain != null && isDomainFailed(domain)) {
                return NotificationPage(emptyList(), false)
            }
            val baseHost = try {
                val u = URI(source.baseUrl); "${u.scheme}://${u.host}"
            } catch (_: Exception) { null }
            if (baseHost != null) {
                for (path in FALLBACK_PATHS) {
                    val fallbackUrl = "$baseHost$path"
                    if (fallbackUrl == source.baseUrl) continue
                    val fallbackDoc = tryFetchDoc(fallbackUrl)
                    if (fallbackDoc != null && extractItems(fallbackDoc).isNotEmpty()) {
                        doc = fallbackDoc
                        url = fallbackUrl
                        Log.d(TAG, "GenericCrawler[${source.displayName}] fallback hit: $url")
                        break
                    }
                    if (domain != null && isDomainFailed(domain)) break
                }
            }
        }

        if (doc == null) return NotificationPage(emptyList(), false)
        resolvedListUrl = url

        // 非连续跳页（少见）：从列表首页顺着「下页」走到目标页。
        repeat(page - 1) {
            val next = findNextAbsUrl(doc!!, url) ?: return NotificationPage(emptyList(), false)
            url = next
            doc = tryFetchDoc(url) ?: return NotificationPage(emptyList(), false)
        }
        return finishPage(doc!!, url, page)
    }

    private fun finishPage(doc: Document, url: String, page: Int): NotificationPage {
        val items = extractItems(doc)
        val notifications = if (items.isEmpty()) {
            Log.w(TAG, "GenericCrawler[${source.displayName}] no items at $url (page $page)")
            if (page == 1) bruteForceExtract(doc, url) else emptyList()
        } else {
            Log.d(TAG, "GenericCrawler[${source.displayName}] page $page: ${items.size} items from $url")
            items.mapNotNull { parseListItem(it, url) }
        }.distinctBy { Triple(it.title, it.link, it.source) }

        lastPage = page
        nextAbsUrl = findNextAbsUrl(doc, url)
        resolvedListUrl = resolvedListUrl ?: url
        return NotificationPage(notifications, hasMore = nextAbsUrl != null)
    }

    /**
     * 西交 CMS 两种分页：
     * - 新模板 `span.p_next a`，href 常是倒序编号（第 2 页 = tzgg/28.htm，不是 tzgg/2.htm）
     * - 旧模板 `a.Next` 文本「下页」
     * 不要用 `a:containsOwn(>)`，会误伤面包屑。
     */
    private fun findNextAbsUrl(doc: Document, currentUrl: String): String? {
        val raw = doc.selectFirst("span.p_next a[href]")?.attr("href")
            ?: doc.select("a").firstOrNull { el ->
                val text = el.ownText().trim()
                text == "下页" || text == "下一页"
            }?.attr("href")
        if (raw.isNullOrBlank() || raw == "#" || raw.startsWith("javascript", ignoreCase = true)) {
            return null
        }
        val abs = resolveUrl(currentUrl, raw)
        return abs.takeUnless { it == currentUrl }
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

    /** 判断一个 li 是否指向通知正文（CMS /info/、相对 info/、或书院微信稿）。 */
    private fun hasInfoLink(el: org.jsoup.nodes.Element): Boolean {
        val href = el.selectFirst("a[href]")?.attr("href") ?: return false
        return ARTICLE_HREF_RE.containsMatchIn(href)
    }

    private fun extractItems(doc: Document): List<org.jsoup.nodes.Element> {
        // ── 策略一：找正文链接最多的列表 ──
        // CMS 用 /info/栏/文.htm（也有相对路径 info/…）；仲英列表外链微信稿。
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
            val dateCount = items.count { FULL_DATE_RE.containsMatchIn(it.text()) }
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

    /**
     * 从 li 元素中提取日期，支持 XJTU CMS 各学院模板：
     * - 完整日期：`<span>2025-11-28</span>`（EE / MARX 等）
     * - 拆分容器：`div.date`（SOM: span=DD + p=YYYY-MM）
     *             `time.times`（MEC: span=DD + ownText=YYYY.MM）
     *             `div.tz-date`（SAE: span=YYYY + b=MM-DD）
     *             CLET: `<span><b>MM/DD</b>YYYY</span>`
     *             XSC: `<span><b>DD</b><i>YYYY/MM</i></span>`
     */

    /** 从包含拆分日期片段的文本中重建完整日期 */
    private fun parseSplitDate(text: String): LocalDate? {
        if (text.isEmpty()) return null

        // 完整日期
        FULL_DATE_RE.find(text)?.let {
            val d = parseDateSafe(it.value); if (d != LocalDate.now()) return d
        }

        // YYYY-MM + DD（SOM/MEC/XSC 模式）
        val ymMatch = YEAR_MONTH_RE.find(text)
        if (ymMatch != null) {
            val y = ymMatch.groupValues[1].toIntOrNull() ?: return null
            val m = ymMatch.groupValues[2].toIntOrNull() ?: return null
            val rest = text.removeRange(ymMatch.range).trim()
            val d = SMALL_NUM_RE.find(rest)?.groupValues?.get(1)?.toIntOrNull()
            if (d != null && y in 2000..2099 && m in 1..12 && d in 1..31)
                return try { LocalDate.of(y, m, d) } catch (_: Exception) { null }
        }

        // MM/DD + YYYY（CLET 模式）；只有 MM-DD 时按「不会是未来日期」回推年份
        val mdMatch = MONTH_DAY_RE.find(text)
        if (mdMatch != null && ymMatch == null) {
            val a = mdMatch.groupValues[1].toIntOrNull() ?: return null
            val b = mdMatch.groupValues[2].toIntOrNull() ?: return null
            val rest = text.removeRange(mdMatch.range).trim()
            val yStr = YEAR_ONLY_RE.find(rest)?.groupValues?.get(1)
            val y = yStr?.toIntOrNull()
            if (y != null && y in 2000..2099 && a in 1..12 && b in 1..31)
                return try { LocalDate.of(y, a, b) } catch (_: Exception) { null }
            if (y == null && a in 1..12 && b in 1..31) return inferMonthDay(a, b)
        }

        // YYYY + MM-DD（SAE 模式）
        val yOnly = YEAR_ONLY_RE.find(text)
        if (yOnly != null && ymMatch == null && mdMatch == null) {
            val y = yOnly.groupValues[1].toIntOrNull() ?: return null
            val mdAfter = MONTH_DAY_RE.find(text.removeRange(yOnly.range).trim())
            if (mdAfter != null) {
                val m = mdAfter.groupValues[1].toIntOrNull() ?: return null
                val d = mdAfter.groupValues[2].toIntOrNull() ?: return null
                if (y in 2000..2099 && m in 1..12 && d in 1..31)
                    return try { LocalDate.of(y, m, d) } catch (_: Exception) { null }
            }
        }

        return null
    }

    /** 页面只给 MM-DD 时，把「比今天晚一周以上」的日期算到去年。 */
    private fun inferMonthDay(month: Int, day: Int): LocalDate? {
        return try {
            val today = LocalDate.now()
            val candidate = LocalDate.of(today.year, month, day)
            if (candidate.isAfter(today.plusDays(7))) candidate.minusYears(1) else candidate
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Jsoup .text() 不在内联子元素间插空格，导致 <b>24</b><i>2025/12</i> 变 "242025/12"
     * 本函数把所有子节点（Element + TextNode）用空格拼接，保证数字片段可分离
     */
    private fun textWithSpaces(el: org.jsoup.nodes.Element): String {
        if (el.childNodeSize() <= 1) return el.text()
        return el.childNodes().joinToString(" ") { node ->
            when (node) {
                is org.jsoup.nodes.TextNode -> node.text().trim()
                is org.jsoup.nodes.Element -> node.text()
                else -> ""
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractDateFromLi(el: org.jsoup.nodes.Element): LocalDate {
        // ── 1. 特定 CSS 选择器（含完整日期的元素） ──
        for (sel in listOf("span.time", "span.date", "em")) {
            val t = el.selectFirst(sel)?.text() ?: continue
            if (FULL_DATE_RE.containsMatchIn(t)) return parseDateSafe(t)
        }

        // ── 2. 任意 span：先找完整日期，再尝试拆分拼接 ──
        for (span in el.select("span")) {
            val t = textWithSpaces(span)
            if (FULL_DATE_RE.containsMatchIn(t)) return parseDateSafe(t)
            // 仅含 ≥3 个数字片段时尝试拆分（CLET/XSC 模板）
            if (DIGITS_RE.findAll(t).count() >= 3) {
                parseSplitDate(t)?.let { return it }
            }
        }

        // ── 3. 日期容器拆分拼接（class 含 date/time 或 <time> 标签） ──
        for (container in el.select("[class*=date], [class*=time], time")) {
            parseSplitDate(textWithSpaces(container))?.let { return it }
        }

        // ── 4. 兜底：li 全文正则 ──
        FULL_DATE_RE.find(el.text())?.let { return parseDateSafe(it.value) }
        return LocalDate.now()
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
        val date = extractDateFromLi(el)

        val tagText = aTag.selectFirst("i")?.text()?.trim('[', ']', '【', '】') ?: ""
        val tags = if (tagText.isNotEmpty()) listOf(tagText) else emptyList()

        return Notification(title = title, link = link, source = source, date = date, tags = tags)
    }
}

/**
 * 办公自动化公开通知。列表是表格行，正文靠 `gotodetail(id)` 打开，
 * 不是学院 CMS 的 `/info/` 路径。
 */
private class OaNoticeCrawler(
    private val client: OkHttpClient,
    private val source: NotificationSource,
) : NotificationCrawler {

    override fun fetchPage(page: Int): NotificationPage {
        val pageNo = page.coerceAtLeast(1)
        val url = if (pageNo == 1) INDEX_URL else "$INDEX_URL?strPageNo=$pageNo"
        val domain = URI(INDEX_URL).host
        if (isDomainFailed(domain)) {
            Log.d(TAG, "OaCrawler skipping - domain $domain cached as failed")
            return NotificationPage(emptyList(), false)
        }
        val doc = try {
            fetchDocumentWithChallenge(client, url)
        } catch (e: Exception) {
            Log.w(TAG, "OaCrawler fetch error at $url: ${e.message}")
            return NotificationPage(emptyList(), false)
        }
        val items = doc.select("a.noa_list").mapNotNull { parseRow(it) }.distinctBy { it.link }
        val hasMore = PAGE_META_RE.find(doc.body()?.text().orEmpty())?.let { match ->
            val current = match.groupValues[1].toIntOrNull() ?: pageNo
            val total = match.groupValues[2].toIntOrNull() ?: current
            current < total
        } ?: (items.size >= PAGE_SIZE)
        Log.d(TAG, "OaCrawler page $pageNo: ${items.size} items hasMore=$hasMore")
        return NotificationPage(items, hasMore)
    }

    private fun parseRow(a: org.jsoup.nodes.Element): Notification? {
        val title = a.attr("title").ifBlank { a.text() }.trim()
        if (title.length < 4) return null
        val id = DETAIL_ID_RE.find(a.attr("onclick"))?.groupValues?.get(1) ?: return null
        val link = "$DETAIL_URL?processInsId=${URLEncoder.encode(id, StandardCharsets.UTF_8)}"
        val meta = a.closest("tr")
            ?.selectFirst("td.timedate1")
            ?.text()
            .orEmpty()
            .replace('\u00a0', ' ')
            .trim()
        val match = META_RE.find(meta)
        val dept = match?.groupValues?.get(1)?.trim().orEmpty()
        val date = match?.groupValues?.get(2)?.let { parseDateSafe(it) } ?: LocalDate.now()
        val tags = if (dept.isNotEmpty()) listOf(dept) else emptyList()
        return Notification(title = title, link = link, source = source, date = date, tags = tags)
    }

    companion object {
        private const val INDEX_URL = "https://oa.xjtu.edu.cn/zxgg_index.jsp"
        private const val DETAIL_URL = "https://oa.xjtu.edu.cn/zxgg_infonew.jsp"
        private const val PAGE_SIZE = 25
        private val DETAIL_ID_RE = Regex("""gotodetail\('([^']+)'\)""")
        private val META_RE = Regex("""^(.*?)[（(](\d{4}-\d{1,2}-\d{1,2})[）)]""")
        private val PAGE_META_RE = Regex("""页次:\s*(\d+)\s*/\s*(\d+)\s*页""")
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

private val DATE_YMD_RE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

private fun parseDateSafe(dateStr: String): LocalDate {
    return try {
        val cleaned = dateStr.trim().replace('/', '-').replace('.', '-')
        val match = DATE_YMD_RE.find(cleaned)
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
        NotificationSource.entries.forEach { source ->
            put(
                source,
                if (source == NotificationSource.OA) OaNoticeCrawler(client, source)
                else GenericXjtuCrawler(client, source),
            )
        }
    }

    fun getNotificationPage(source: NotificationSource, page: Int = 1): NotificationPage {
        val crawler = crawlers[source]
            ?: throw IllegalArgumentException("不支持的通知来源: $source")
        return crawler.fetchPage(page)
    }

    fun getNotifications(source: NotificationSource, page: Int = 1): List<Notification> {
        return getNotificationPage(source, page).items
    }

    /**
     * 取多个来源的通知，并返回本次被「静默跳过」的通知源集合。
     * 静默跳过 = 域名级 DNS/连接失败（[failedDomains]），或来源整体抛异常。
     * 客户端应在 UI 上提示「以下来源暂不可用：…」——否则用户以为没通知。
     */
    suspend fun getMergedNotificationsWithSkipped(
        sources: List<NotificationSource>,
        page: Int = 1,
    ): MergedNotificationPage = coroutineScope {
        sources.map { source ->
            async(Dispatchers.IO) { source to runCatching { getNotificationPage(source, page) } }
        }.awaitAll().let { results ->
            val skipped = mutableSetOf<NotificationSource>()
            val merged = mutableListOf<Notification>()
            var hasMore = false
            for ((source, result) in results) {
                result.fold(
                    onSuccess = { pageResult ->
                        val srcDomain = try { java.net.URI(source.baseUrl).host } catch (_: Exception) { null }
                        if (pageResult.items.isEmpty() && srcDomain != null && isDomainFailed(srcDomain)) {
                            skipped.add(source)
                        } else {
                            merged.addAll(pageResult.items)
                            if (pageResult.hasMore) hasMore = true
                        }
                    },
                    onFailure = { skipped.add(source) },
                )
            }
            MergedNotificationPage(
                items = merged.sortedByDescending { it.date },
                skipped = skipped,
                hasMore = hasMore,
            )
        }
    }

    suspend fun getMergedNotifications(sources: List<NotificationSource>, page: Int = 1): List<Notification> {
        return getMergedNotificationsWithSkipped(sources, page).items
    }

    suspend fun getAllNotifications(page: Int = 1): List<Notification> {
        return getMergedNotifications(NotificationSource.entries, page)
    }

    /** 清除域名失败缓存（例如切换网络后调用） */
    fun clearFailedDomainCache() {
        failedDomains.clear()
    }
}
