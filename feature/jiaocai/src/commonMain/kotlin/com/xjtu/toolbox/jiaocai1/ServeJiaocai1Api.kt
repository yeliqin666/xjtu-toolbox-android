package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.serveData
import com.xjtu.toolbox.core.net.serveOnly
import com.xjtu.toolbox.core.net.serveWrite
import com.xjtu.toolbox.core.net.toolboxJson
import com.xjtu.toolbox.util.encodeUrlComponent
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * 教材全文库那一族的取数（`docs/api-contract.md` §5.14）—— **只在 serve 形态下存在**。
 *
 * 为什么它住在 `:feature:jiaocai` 而不是像其余 `Campus*Api` 那样住 `:shared:network`：
 * 书架那一格（[Jiaocai1ShelfRecord]）与两屏的模型都在本模块里，而 `:shared:network` 看不见
 * `:feature:*`（依赖方向是 feature → shared）。为此把模型搬去 `:shared:model` 是更大的动作，
 * 不值当 —— 这一份 API 跟着它的模型走（与下载那一族的 `ServeDownloadsApi` 同一条）。
 *
 * campus-api 形态没有这一族（那边全文库的取数在 `:data` 里直连站点）⇒ 一律 [serveOnly]。
 *
 * ## 页图与封面不走这里
 *
 * `page` / `cover` 两条**成功时回 `image/jpeg` 字节**（契约 §4 第四条例外），失败时才是信封。
 * 这一份只给**路径构造**（[pagePath] / [coverPath]），真取字节由宿主那一端做：浏览器那侧是
 * 同源 `fetch`（`serve_token` 是 `Path=/` 的 cookie，自动带上），Android / 桌面上这两条端点
 * 根本用不到（它们直连站点）。
 */
class ServeJiaocai1Api(
    private val client: HttpClient,
    private val baseUrl: String = "",
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /** `GET /api/jiaocai1/classify`：四级中图法分类树（顶层节点；节点就是 [Jiaocai1Category]）。 */
    suspend fun classify(): List<Jiaocai1Category> {
        requireServe()
        val data = client.serveData("取教材分类树", baseUrl, CLASSIFY_PATH)
        // `nodes` 不是数组 = 形状漂了（空分类树是 `[]`）：不给个空列表蒙混过去 —— 那会在屏上显示成
        // 「分类目录暂时取不到」而不是真因
        val nodes = data["nodes"] as? JsonArray
            ?: error("取教材分类树：服务端没有给 nodes 数组（契约 §5.14 的形状变了）")
        return runCatching {
            toolboxJson.decodeFromJsonElement<List<Jiaocai1Category>>(nodes)
        }.getOrElse { error ->
            // 不是「分类树是空的」，是形状不对 —— 静默给空列表会让屏上显示「取不到分类目录」而不是真因
            error("取教材分类树：nodes 不是分类节点数组（${error.message}）")
        }
    }

    /**
     * `GET /api/jiaocai1/search`：检索 / 按分类浏览。
     *
     * ⚠️ `keyword` 与 `cls` **至少给一个**（serve 那一侧两个都空就回 `400`，屏上不会走到那一步：
     * 它要么有关键词、要么选了分类）。
     */
    suspend fun search(
        keyword: String,
        field: Jiaocai1SearchField,
        cls: String,
        page: Int,
    ): Jiaocai1SearchResult {
        requireServe()
        val data = client.serveData(
            "检索教材全文库",
            baseUrl,
            SEARCH_PATH,
            listOf(
                "keyword" to keyword,
                "field" to field.key,
                "cls" to cls,
                "page" to page.toString(),
            ),
        )
        return Jiaocai1SearchResult(
            books = (data["books"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.toBook() },
            totalRows = data["totalRows"].safeInt(),
            // 两格缺了就当第 1 页 / 共 1 页：`hasMore` 于是为 false（不编出「还有下一页」）
            currentPage = data["currentPage"].safeInt(1).coerceAtLeast(1),
            totalPages = data["totalPages"].safeInt(1).coerceAtLeast(1),
        )
    }

    /**
     * `GET /api/jiaocai1/book`：用 [ssno] 换阅读句柄的**页表那一半**。
     *
     * 打不开（没有开放全文 / 会话过期那一枪没补上）⇒ `200` + `book:null` ⇒ 这里回 `null`。
     * 页表不是 8 组就是契约漂了：那会让 [Jiaocai1Paging] 的页型与页码整体错位，宁可当场炸，
     * 也不给屏一个「页码看着对、内容全错位」的书。
     */
    suspend fun book(ssno: String): Jiaocai1BookHandle? {
        requireServe()
        val data = client.serveData("打开这本书", baseUrl, BOOK_PATH, listOf("ssno" to ssno))
        val book = data["book"] as? JsonObject ?: return null
        val ranges = (book["pageRanges"] as? JsonArray).orEmpty().mapNotNull { row ->
            val pair = row as? JsonArray ?: return@mapNotNull null
            val from = pair.getOrNull(0)?.safeString()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val to = pair.getOrNull(1)?.safeString()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            from..to
        }
        if (ranges.size != Jiaocai1Paging.TYPE_COUNT) {
            error("打开这本书：页表不是 ${Jiaocai1Paging.TYPE_COUNT} 组（拿到 ${ranges.size} 组）—— 契约 §5.14 的 pageRanges 变了")
        }
        return Jiaocai1BookHandle(
            ssno = ssno,
            title = book["title"].safeString(),
            // 契约**不投影** `jpgPath`（那是服务端按会话下发的令牌路径，模型自己也写着「不要持久化」）：
            // 这一端取字节走 [pagePath]，所以这一格恒为空串，不是「漏读了」
            jpgPath = "",
            pageRanges = ranges,
            bookPages = book["bookPages"].safeInt(),
        )
    }

    /** `GET /api/jiaocai1/shelf`：整份书架（serve 侧按账号落盘，按最近阅读倒序）。 */
    suspend fun shelf(): List<Jiaocai1ShelfRecord> {
        requireServe()
        return client.serveData("读书架", baseUrl, SHELF_PATH).toShelfRows()
    }

    /** `POST /api/jiaocai1/shelf`：整行插入 / 覆盖（阅读器打开一本书时那一发）。回整份书架。 */
    suspend fun upsert(row: Jiaocai1ShelfRecord): List<Jiaocai1ShelfRecord> {
        val body = buildJsonObject {
            put("ssno", row.ssno)
            put("title", row.title)
            put("author", row.author)
            put("coverUrl", row.coverUrl)
            put("totalPages", row.totalPages)
            put("lastReadIndex", row.lastReadIndex)
            put("lastReadAt", row.lastReadAt)
            put("addedAt", row.addedAt)
        }.toString()
        return write("写书架", SHELF_PATH, body)
    }

    /** `POST /api/jiaocai1/shelf/progress`：只改进度三列（翻页那一发）。回整份书架。 */
    suspend fun updateProgress(
        ssno: String,
        index: Int,
        at: Long,
        totalPages: Int,
    ): List<Jiaocai1ShelfRecord> {
        val body = buildJsonObject {
            put("ssno", ssno)
            put("lastReadIndex", index)
            put("totalPages", totalPages)
            put("at", at)
        }.toString()
        return write("写阅读进度", "$SHELF_PATH/$PROGRESS_SEGMENT", body)
    }

    /** `DELETE /api/jiaocai1/shelf/{ssno}`：移出书架（幂等）。回整份书架。 */
    suspend fun delete(ssno: String): List<Jiaocai1ShelfRecord> {
        requireServe()
        val outcome = client.serveWrite(
            baseUrl,
            "$SHELF_PATH/${encodeUrlComponent(ssno)}",
            HttpMethod.Delete,
        )
        if (!outcome.ok) error("移出书架：${outcome.message}")
        return outcome.data?.toShelfRows().orEmpty()
    }

    /** `GET /api/jiaocai1/page` 的路径（阅读器页序 [index] 从 0 起）。 */
    fun pagePath(ssno: String, index: Int): String =
        "$PAGE_PATH?ssno=${encodeUrlComponent(ssno)}&index=$index"

    /** `GET /api/jiaocai1/cover` 的路径（[url] 取自检索结果那一行的 `coverUrl`）。 */
    fun coverPath(url: String): String = "$COVER_PATH?url=${encodeUrlComponent(url)}"

    private suspend fun write(what: String, path: String, body: String): List<Jiaocai1ShelfRecord> {
        requireServe()
        val outcome = client.serveWrite(baseUrl, path, HttpMethod.Post, body)
        if (!outcome.ok) error("$what：${outcome.message}")
        return outcome.data?.toShelfRows().orEmpty()
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) {
            serveOnly("教材全文库", "GET /api/jiaocai1/{classify,search,book,page,cover,shelf} 与 POST/DELETE /api/jiaocai1/shelf{,/progress}")
        }
    }

    private companion object {
        const val CLASSIFY_PATH = "/api/jiaocai1/classify"
        const val SEARCH_PATH = "/api/jiaocai1/search"
        const val BOOK_PATH = "/api/jiaocai1/book"
        const val PAGE_PATH = "/api/jiaocai1/page"
        const val COVER_PATH = "/api/jiaocai1/cover"
        const val SHELF_PATH = "/api/jiaocai1/shelf"
        const val PROGRESS_SEGMENT = "progress"
    }
}

// ── 线上形状 → `:core` 模型（字段名与契约 §5.14 逐条对应）────────────────

/** 一本检索命中的书：八格逐字段（`ssno` 读不出来就丢掉这一行 —— 它是打开全文的句柄）。 */
private fun JsonObject.toBook(): Jiaocai1Book? {
    val ssno = this["ssno"].safeString().trim()
    if (ssno.isEmpty()) return null
    return Jiaocai1Book(
        ssno = ssno,
        title = this["title"].safeString(),
        author = this["author"].safeString(),
        publishDate = this["publishDate"].safeString(),
        themeWord = this["themeWord"].safeString(),
        callNo = this["callNo"].safeString(),
        classifyPath = this["classifyPath"].safeString(),
        coverUrl = this["coverUrl"].safeString(),
    )
}

/**
 * 书架那一族每个端点回的都是整份（`{count,rows}`）⇒ 四个方法共用这一处解析。
 *
 * ⚠️ 两个时间是 **epoch 毫秒**（契约 §5.14 里点明了这一族不走 ISO-8601），原样读 ——
 * 屏上要自己算「3 天前」，换个单位就会整体飘。
 */
private fun JsonObject.toShelfRows(): List<Jiaocai1ShelfRecord> =
    (this["rows"] as? JsonArray).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val ssno = row["ssno"].safeString().trim()
        if (ssno.isEmpty()) return@mapNotNull null
        Jiaocai1ShelfRecord(
            ssno = ssno,
            title = row["title"].safeString(),
            author = row["author"].safeString(),
            coverUrl = row["coverUrl"].safeString(),
            totalPages = row["totalPages"].safeInt(),
            lastReadIndex = row["lastReadIndex"].safeInt(),
            lastReadAt = row["lastReadAt"].safeString().trim().toLongOrNull() ?: 0L,
            addedAt = row["addedAt"].safeString().trim().toLongOrNull() ?: 0L,
        )
    }
