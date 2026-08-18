package com.xjtu.toolbox.jiaocai1

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.PortalRedirect
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.Request
import java.nio.charset.Charset

private const val TAG = "Jiaocai1Api"

// ── 数据模型 ─────────────────────────────────────────────────────────

/** 中图法分类节点。[id] 即检索参数 `cls`，如 `0O109101`。 */
data class Jiaocai1Category(
    val id: String,
    val name: String,
    val level: Int,
    val parentId: Int,
    val nodeId: Int,
    val children: List<Jiaocai1Category> = emptyList(),
)

/** [ssno] 是全文库主键，jiaocai.lib 的「本地全文」链接里带的就是它。 */
data class Jiaocai1Book(
    val ssno: String,
    val title: String,
    val author: String = "",
    val publishDate: String = "",
    val themeWord: String = "",
    val callNo: String = "",
    val classifyPath: String = "",
    val coverUrl: String = "",
)

data class Jiaocai1SearchResult(
    val books: List<Jiaocai1Book> = emptyList(),
    val totalRows: Int = 0,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
) {
    val hasMore: Boolean get() = currentPage < totalPages
}

/** 检索字段，只能单选。组合检索需走 stype=4 的高级检索表单。 */
enum class Jiaocai1SearchField(val key: String, val label: String) {
    BOOK_NAME("bookName", "书名"),
    AUTHOR("bookAuthor", "作者"),
    THEME("themeWord", "主题词"),
    CALL_NO("bookCallno", "索书号"),
    ISBN("bookISBN", "ISBN"),
}

/**
 * [jpgPath] 是服务端按会话下发的令牌路径，不要持久化：换会话即失效，
 * 表现为取图失败，需重新 [Jiaocai1Api.openBook]。
 */
data class Jiaocai1BookHandle(
    val ssno: String,
    val title: String,
    val jpgPath: String,
    /** 8 种页型各自的 [起, 止]；`起 > 止` 表示该书没有这类页 */
    val pageRanges: List<IntRange>,
    /** 正文页数 */
    val bookPages: Int,
) {
    /** 线性页表，下标即阅读器页序 */
    val pages: List<Jiaocai1Page> by lazy { Jiaocai1Paging.flatten(pageRanges) }
}

// ── API ──────────────────────────────────────────────────────────────

/**
 * 教材全文库 jiaocai1.lib.xjtu.edu.cn。
 *
 * 会话由 /front/user/portalLogin 建立，从 jiaocai.lib 的身份桥接过来；未建立时接口
 * 不返数据而是一张跳转页，见 [fetch]。[SiteSession] 提供 OkHttp 客户端（校外走 WebVPN
 * 拦截器改写域名）、cookie jar 和 executeWithReAuth 的重认证。
 *
 * 编码随访问路径变化（经 WebVPN 是 UTF-8，直连是 GBK），见 [decodeSmart]。
 */
class Jiaocai1Api(private val site: SiteSession) {

    private fun fetch(request: Request): String {
        var body = exec(request, "biz")
        var round = 0
        while (PortalRedirect.needsLogin(body) && round < PortalRedirect.MAX_ROUNDS) {
            round++
            val ok = PortalRedirect.follow(body, "jiaocai1-r$round") { url ->
                exec(Request.Builder().url(url).get().build(), "r$round-hop")
            }
            if (!ok) break
            body = exec(request, "biz-retry$round")
        }
        if (PortalRedirect.needsLogin(body)) Log.e(TAG, "跟完登录链仍未建立会话")
        return body
    }

    private fun exec(request: Request, @Suppress("UNUSED_PARAMETER") tag: String): String =
        runBlocking { site.executeWithReAuth(request) }.use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            bytes.decodeSmart()
        }

    private fun get(url: String, referer: String = "$BASE/front/"): String =
        fetch(
            Request.Builder().url(url)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .get().build()
        )

    // ── 分类树 ───────────────────────────────────────────────────────

    /** 四级中图法分类树，返回顶层节点。 */
    fun classifyTree(): List<Jiaocai1Category> {
        var body = ""
        return try {
            body = get("$BASE/front/classify/info?channeltype=$CHANNEL")
            val arr = body.safeParseJsonObject().getAsJsonArray("classifyList") ?: return emptyList()
            val flat = arr.mapNotNull { el ->
                try {
                    val o = el.asJsonObject
                    Jiaocai1Category(
                        id = o.get("classifyid")?.asString ?: return@mapNotNull null,
                        name = o.get("classifyname")?.asString ?: "",
                        level = o.get("clevel")?.asInt ?: 1,
                        parentId = o.get("pid")?.asInt ?: 0,
                        nodeId = o.get("id")?.asInt ?: 0,
                    )
                } catch (_: Exception) {
                    null
                }
            }
            buildTree(flat)
        } catch (e: Exception) {
            Log.e(TAG, "classifyTree failed: ${e.message}")
            emptyList()
        }
    }

    private fun buildTree(flat: List<Jiaocai1Category>): List<Jiaocai1Category> {
        val byParent = flat.groupBy { it.parentId }
        fun expand(node: Jiaocai1Category): Jiaocai1Category =
            node.copy(children = byParent[node.nodeId].orEmpty().map(::expand))
        return byParent[0].orEmpty().map(::expand)
    }

    // ── 检索 ─────────────────────────────────────────────────────────

    /**
     * [keyword] 留空 + [cls] 非空即为按分类浏览。
     *
     * 请求体按 UTF-8 编码，服务端 URIEncoding 就是 UTF-8。
     */
    fun search(
        keyword: String = "",
        field: Jiaocai1SearchField = Jiaocai1SearchField.BOOK_NAME,
        cls: String = "",
        page: Int = 1,
        orderField: Int = 0,
    ): Jiaocai1SearchResult {
        return try {
            val form = FormBody.Builder(Charsets.UTF_8)
                .add("cpage", page.toString())
                .add("stype", "1")
                .add("orderField", orderField.toString())
                .add("sw", keyword)
                .add("searchField", field.key)
                .apply { if (cls.isNotBlank()) add("cls", cls) }
                .build()
            val html = fetch(
                Request.Builder().url("$BASE/front/book/search/index")
                    .header("Referer", "$BASE/front/book/search/page")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form).build()
            )
            parseSearchHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            Jiaocai1SearchResult()
        }
    }

    private fun parseSearchHtml(html: String): Jiaocai1SearchResult {
        val books = LI_RE.findAll(html).mapNotNull { m ->
            val li = m.groupValues[1]
            val ssno = SSNO_RE.find(li)?.groupValues?.get(1) ?: return@mapNotNull null
            val fields = DD_RE.findAll(li)
                .map { stripTags(it.groupValues[1]) }
                .mapNotNull { text ->
                    val idx = text.indexOf('：').takeIf { it > 0 } ?: return@mapNotNull null
                    text.substring(0, idx).trim() to text.substring(idx + 1).trim()
                }
                .toMap()
            Jiaocai1Book(
                ssno = ssno,
                title = TITLE_RE.find(li)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty(),
                author = fields["作者"].orEmpty(),
                publishDate = fields["出版日期"].orEmpty(),
                themeWord = fields["主题词"].orEmpty(),
                callNo = fields["索书号"].orEmpty(),
                classifyPath = fields["分类"].orEmpty(),
                coverUrl = COVER_RE.find(li)?.groupValues?.get(1)?.let(::unescape).orEmpty(),
            )
        }.toList()

        return Jiaocai1SearchResult(
            books = books,
            totalRows = TOTAL_ROW_RE.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: books.size,
            currentPage = CPAGE_RE.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1,
            totalPages = SUM_PAGE_RE.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1,
        )
    }

    // ── 打开一本书 ───────────────────────────────────────────────────

    /**
     * 用 [ssno] 换阅读句柄。同一本书并发 open 会让先拿到的令牌失效，所以按 ssno 单飞。
     */
    fun openBook(ssno: String): Jiaocai1BookHandle? = runBlocking {
        val (wait, mine) = openMutex.withLock {
            opening[ssno]?.let { it to false } ?: run {
                val deferred = CompletableDeferred<Jiaocai1BookHandle?>()
                opening[ssno] = deferred
                deferred to true
            }
        }
        if (!mine) return@runBlocking wait.await()
        try {
            val result = openBookOnce(ssno)
            wait.complete(result)
            result
        } catch (e: Throwable) {
            wait.completeExceptionally(e)
            throw e
        } finally {
            openMutex.withLock { opening.remove(ssno) }
        }
    }

    private fun openBookOnce(ssno: String): Jiaocai1BookHandle? {
        parseReader(ssno, get("$BASE/front/reader/goRead?ssno=$ssno&channel=$CHANNEL&jpgread=1"))
            ?.let { return it }
        return try {
            parseReader(ssno, get("$GUAJIE_BASE/guajie/common?ssno=$ssno&cpage=1&channel=$CHANNEL"))
        } catch (e: Exception) {
            Log.e(TAG, "openBook($ssno) fallback failed", e)
            null
        }
    }

    private fun parseReader(ssno: String, html: String): Jiaocai1BookHandle? {
        if (html.isBlank()) return null
        val jpgPath = JPG_PATH_RE.find(html)?.groupValues?.get(1) ?: return null
        val rangesRaw = PAGES_RE.find(html)?.groupValues?.get(1) ?: return null
        val ranges = PAIR_RE.findAll(rangesRaw)
            .map { IntRange(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            .toList()
        if (ranges.size != Jiaocai1Paging.TYPE_COUNT) return null
        return Jiaocai1BookHandle(
            ssno = ssno,
            title = HEAD_TITLE_RE.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty(),
            jpgPath = jpgPath,
            pageRanges = ranges,
            bookPages = BOOK_PAGES_RE.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }

    companion object {
        private val openMutex = Mutex()
        private val opening = mutableMapOf<String, CompletableDeferred<Jiaocai1BookHandle?>>()

        const val BASE = "https://jiaocai1.lib.xjtu.edu.cn"

        /** jiaocai.lib 详情页给出的挂接入口，非标准端口，仅作兜底 */
        private const val GUAJIE_BASE = "http://jiaocai1.lib.xjtu.edu.cn:9088"

        /** 图书频道 */
        const val CHANNEL = "100"

        /**
         * `zoom=0` 即最高画质。reader.js 注明 dll 侧缩放只改展示尺寸，
         * 拿不到更高分辨率的原图。
         */
        fun pageUrl(handle: Jiaocai1BookHandle, page: Jiaocai1Page): String =
            "$BASE/jpath/${handle.jpgPath}${page.fileName}.jpg?zoom=0"

        // 搜索结果 HTML
        private val LI_RE = Regex("""<li>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        private val SSNO_RE = Regex("""goRead\?ssno=(\d+)""")
        private val TITLE_RE = Regex("""<a[^>]*title="([^"]*)"""")
        private val COVER_RE = Regex("""<img[^>]*\ssrc="([^"]+)"""")
        private val DD_RE = Regex("""<dd>(.*?)</dd>""", RegexOption.DOT_MATCHES_ALL)
        private val CPAGE_RE = Regex("""data-cpage="(\d+)"""")
        private val SUM_PAGE_RE = Regex("""data-sum-page="(\d+)"""")
        private val TOTAL_ROW_RE = Regex("""data-totalrow="(\d+)"""")

        // reader.shtml 内联脚本
        private val JPG_PATH_RE = Regex("""jpgPath:\s*"([^"]+)"""")
        private val PAGES_RE = Regex("""var\s+pages\s*=\s*(\[\[.*?]]);""")
        private val PAIR_RE = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]""")
        private val BOOK_PAGES_RE = Regex("""var\s+bookPages\s*=\s*(\d+)""")
        private val HEAD_TITLE_RE = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)

        private val TAG_RE = Regex("""<[^>]+>""")

        private fun stripTags(s: String): String = unescape(TAG_RE.replace(s, "")).trim()

        private fun unescape(s: String): String = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

        /**
         * 站点原始响应是 GBK，但 WebVPN 网关代理时会转码成 UTF-8，所以经代理拿到的是
         * UTF-8；校内直连时才是 GBK。先按 UTF-8 宽松解，出现替换字符 U+FFFD 说明字节
         * 不是 UTF-8，再按 GB18030 解一遍。
         */
        fun ByteArray.decodeSmart(): String {
            val utf8 = String(this, Charsets.UTF_8)
            return if (utf8.contains('�')) String(this, GBK) else utf8
        }

        private val GBK: Charset = Charset.forName("GB18030")
    }
}
