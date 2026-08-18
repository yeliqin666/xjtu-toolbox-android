package com.xjtu.toolbox.jiaocai

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.PortalRedirect
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeInt
import okhttp3.Request
import kotlinx.coroutines.runBlocking

private const val TAG = "JiaocaiApi"

// ── 数据模型 ─────────────────────────────────────────────────────────

data class JiaocaiBook(
    val id: String = "",          // general_55258665
    val appId: Int = 0,
    val engineInstanceId: Int = 0,
    val title: String = "",
    val author: String = "",
    val summary: String = "",     // 包含课程名/获取方式等描述
    /** 全文库（jiaocai1.lib）书目号。「本地全文」链接直接带着它，非空即可在线阅读。 */
    val ssno: String? = null,
    /** 从描述拆出的键值对：课程名称、开课学院、教材名称、出版社、ISBN号等 */
    val fields: Map<String, String> = emptyMap(),
) {
    /** 列表接口只给"获取方式一：本地全文"这段文字，ssno 得进详情才拿得到 */
    val hasFullText: Boolean
        get() = ssno != null || fields.values.any { it.contains("本地全文") }
}

// ── API 类 ───────────────────────────────────────────────────────────

class JiaocaiApi(private val site: SiteSession) {

    private val BASE get() = "https://jiaocai.lib.xjtu.edu.cn"
    private val FID get() = "17071"
    private val PAGE_ID get() = "13858"
    private val SEARCH_ID get() = "10700"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Referer", "$BASE/")
            .header("X-Requested-With", "XMLHttpRequest")
            .get().build()
        var body = exec(req)
        var round = 0
        // 详情接口同样受 JS 跳转登录链保护，和全文库共用一套，见 PortalRedirect
        while (PortalRedirect.needsLogin(body) && round < PortalRedirect.MAX_ROUNDS) {
            round++
            val ok = PortalRedirect.follow(body, "jiaocai-r$round") { hop ->
                exec(Request.Builder().url(hop).get().build())
            }
            if (!ok) break
            body = exec(req)
        }
        return body
    }

    private fun exec(request: Request): String =
        runBlocking { site.executeWithReAuth(request) }.use { it.body?.string() ?: "" }

    /** 搜索教材，返回书目列表 */
    fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<JiaocaiBook> {
        return try {
            val url = "$BASE/engine2/search/search-list" +
                    "?wfwfid=$FID" +
                    "&keyWord=${java.net.URLEncoder.encode(keyword, "UTF-8")}" +
                    "&pageIndex=$page" +
                    "&pageSize=$pageSize" +
                    "&pageId=$PAGE_ID" +
                    "&searchStrategy=0" +
                    "&searchId=$SEARCH_ID"
            val body = get(url)
            Log.d(TAG, "search[$keyword]: ${body.take(200)}")
            val json = body.safeParseJsonObject()
            val list = json.getAsJsonObject("data")?.getAsJsonArray("dataList") ?: return emptyList()
            var loggedSample = false
            list.mapNotNull { elem ->
                try {
                    val obj = elem.asJsonObject
                    val raw = obj.get("content")?.safeString() ?: ""
                    if (!loggedSample) {
                        loggedSample = true
                        Log.w(TAG, "content 样本: ${raw.replace(Regex("""\s+"""), " ").take(1200)}")
                        Log.w(TAG, "ssno=${SSNO_RE.find(raw)?.groupValues?.get(1)}")
                    }
                    JiaocaiBook(
                        id = obj.get("id")?.safeString() ?: return@mapNotNull null,
                        appId = obj.get("appId")?.safeInt() ?: 0,
                        engineInstanceId = obj.get("engineInstanceId")?.safeInt() ?: 0,
                        title = (obj.get("title")?.safeString() ?: "").replace(TAG_RE, ""),
                        author = obj.get("author")?.safeString() ?: "",
                        summary = plainText(raw),
                        ssno = SSNO_RE.find(raw)?.groupValues?.get(1),
                        fields = parseFields(raw),
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            emptyList()
        }
    }

    /**
     * 取一本教材的 ssno。
     *
     * search-list 返回的 `content` 已经被服务端剥掉了 `<a>`，只剩"获取方式一：本地全文"
     * 这段文字，链接和 ssno 都不在里面；详情接口才带完整富文本。所以进详情页时补一次。
     */
    fun fetchSsno(book: JiaocaiBook): String? {
        val numericId = book.id.substringAfterLast('_').takeIf { it.isNotBlank() } ?: return null
        return try {
            val html = get(
                "$BASE/engine2/d/$numericId/${book.engineInstanceId}/0/${book.appId}" +
                    "?pageId=$PAGE_ID&engineInstanceId=${book.engineInstanceId}"
            )
            SSNO_RE.find(html)?.groupValues?.get(1).also {
                Log.w(
                    TAG,
                    "fetchSsno(${book.id}) numericId=$numericId eid=${book.engineInstanceId} " +
                        "appId=${book.appId} -> $it (len=${html.length})"
                )
                if (it == null) {
                    Log.w(TAG, "detail body=${html.replace(Regex("""\s+"""), " ").take(1500)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSsno failed", e)
            null
        }
    }

    companion object {
        /**
         * 「本地全文」链接形如
         * `http://jiaocai1.lib.xjtu.edu.cn:9088/guajie/common?ssno=15065267&cpage=1&channel=100`。
         * 端口和路径都可能变（443 上另有 /front/reader/goRead），只锚定域名和 ssno。
         */
        private val SSNO_RE = Regex("""jiaocai1\.lib\.xjtu\.edu\.cn[^"'<>\s]*?[?&]ssno=(\d+)""")

        private val TAG_RE = Regex("""<[^>]+>""")

        /** content 是 `<br>` 分行的富文本，去标签前先留住换行 */
        private fun plainText(html: String): String =
            html.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(TAG_RE, "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .lines().map(String::trim).filter { it.isNotBlank() }
                .joinToString("\n")

        /**
         * 把 `键：值` 拆成 map。分隔符有两层：`<br>` 换行，以及
         * "教材名称：…；编者：…；出版社：…" 这种一行内的全角分号。取不出键的片段丢弃。
         */
        private fun parseFields(html: String): Map<String, String> =
            plainText(html).split('\n', '；', ';').mapNotNull { seg ->
                val part = seg.trim()
                val idx = part.indexOf('：').takeIf { it > 0 } ?: return@mapNotNull null
                part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }.filter { it.second.isNotBlank() }.toMap()
    }
}
