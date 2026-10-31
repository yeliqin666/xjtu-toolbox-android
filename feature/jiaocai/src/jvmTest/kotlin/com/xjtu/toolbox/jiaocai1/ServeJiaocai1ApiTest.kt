package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.createToolboxClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * serve 契约 §5.14 的客户端那一侧（[ServeJiaocai1Api]）：形状、参数、页表那一档。
 *
 * 夹具一律编造（示例书目号 / 示例书名 / 示例字节），不打真实站点。
 */
class ServeJiaocai1ApiTest {

    /** 一条发出去的请求：路径 + 方法 + 完整 URL + 请求体（判参数 / 方法有没有对靠它）。 */
    private class Call(val path: String, val method: HttpMethod, val url: String, val body: String?)

    private fun engineFor(
        vararg bodies: Pair<String, String>,
        status: HttpStatusCode = HttpStatusCode.OK,
        calls: MutableList<Call>? = null,
    ): MockEngine = MockEngine { request ->
        val body = (request.body as? TextContent)?.text.orEmpty()
        calls?.add(Call(request.url.encodedPath, request.method, request.url.toString(), body.ifBlank { null }))
        val payload = bodies.toMap().entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
            ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
        respond(
            content = payload,
            status = status,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
    }

    private fun api(engine: MockEngine, mode: ApiMode = ApiMode.SERVE) = ServeJiaocai1Api(
        client = createToolboxClient(engine = engine),
        baseUrl = "",
        mode = mode,
    )

    // ── 夹具（全部编造）────────────────────────────────────────────────

    private val classifyBody = """
        {"code":0,"data":{"count":2,"nodes":[
          {"id":"0O109101","name":"文学","level":1,"parentId":0,"nodeId":101,"children":[
            {"id":"0O10910101","name":"中国文学","level":2,"parentId":101,"nodeId":10101,"children":[]}]},
          {"id":"0T","name":"工业技术","level":1,"parentId":0,"nodeId":102,"children":[]}]}}
    """.trimIndent()

    private val searchBody = """
        {"code":0,"data":{"keyword":"示例教材","field":"bookName","cls":"","page":2,
         "totalRows":34,"currentPage":2,"totalPages":3,"count":1,"books":[
          {"ssno":"990001","title":"示例教材（第二版）","author":"示例作者","publishDate":"2021-08-01",
           "themeWord":"示例主题词","callNo":"O1/123","classifyPath":"数理科学和化学 > 数学",
           "coverUrl":"https://jiaocai1.lib.xjtu.edu.cn/jpath/cover/990001.jpg"}]}}
    """.trimIndent()

    private val bookBody = """
        {"code":0,"data":{"ssno":"990001","book":{"title":"示例教材（第二版）",
         "pageRanges":[[1,1],[1,1],[1,2],[1,3],[1,5],[1,20],[1,1],[2,2]],
         "bookPages":20,"pageCount":34}}}
    """.trimIndent()

    private val shelfBody = """
        {"code":0,"data":{"count":1,"rows":[
          {"ssno":"990001","title":"示例教材（第二版）","author":"示例作者",
           "coverUrl":"https://jiaocai1.lib.xjtu.edu.cn/jpath/cover/990001.jpg",
           "totalPages":34,"lastReadIndex":12,"lastReadAt":1792548000000,"addedAt":1792450000000}]}}
    """.trimIndent()

    // ── 分类树 ────────────────────────────────────────────────────────

    @Test
    fun `classify 逐字段：两级节点与 children 递归、参数不额外发`() = runTest {
        val calls = mutableListOf<Call>()
        val roots = api(engineFor("/api/jiaocai1/classify" to classifyBody, calls = calls)).classify()

        assertEquals(2, roots.size)
        assertEquals("0O109101", roots[0].id, "id 就是检索参数 cls")
        assertEquals("文学", roots[0].name)
        assertEquals(1, roots[0].level)
        assertEquals(0, roots[0].parentId)
        assertEquals(101, roots[0].nodeId)
        assertEquals(listOf("中国文学"), roots[0].children.map { it.name }, "children 是真递归解出来的")
        assertEquals("0O10910101", roots[0].children[0].id)
        assertTrue(roots[1].children.isEmpty())

        assertEquals(HttpMethod.Get, calls.single().method)
        assertEquals("/api/jiaocai1/classify", calls.single().path)
        assertNull(calls.single().body)
    }

    @Test
    fun `classify 的 nodes 不是数组就报错（空列表与形状不对不是一件事）`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            api(engineFor("/api/jiaocai1/classify" to """{"code":0,"data":{"count":1,"nodes":{}}}""")).classify()
        }
        assertTrue(error.message.orEmpty().contains("nodes"), "错误要说清是哪一格：${error.message}")
    }

    // ── 检索 ──────────────────────────────────────────────────────────

    @Test
    fun `search 逐字段：八格书目、分页四格、四个参数都进 query`() = runTest {
        val calls = mutableListOf<Call>()
        val result = api(engineFor("/api/jiaocai1/search" to searchBody, calls = calls)).search(
            keyword = "示例教材",
            field = Jiaocai1SearchField.BOOK_NAME,
            cls = "",
            page = 2,
        )

        val book = result.books.single()
        assertEquals("990001", book.ssno)
        assertEquals("示例教材（第二版）", book.title)
        assertEquals("示例作者", book.author)
        assertEquals("2021-08-01", book.publishDate)
        assertEquals("示例主题词", book.themeWord)
        assertEquals("O1/123", book.callNo)
        assertEquals("数理科学和化学 > 数学", book.classifyPath)
        assertEquals("https://jiaocai1.lib.xjtu.edu.cn/jpath/cover/990001.jpg", book.coverUrl)

        assertEquals(34, result.totalRows)
        assertEquals(2, result.currentPage)
        assertEquals(3, result.totalPages)
        assertTrue(result.hasMore, "第 2 页 / 共 3 页 ⇒ 还有下一页")

        val url = calls.single().url
        assertEquals("/api/jiaocai1/search", calls.single().path)
        for (needle in listOf("keyword=", "field=bookName", "page=2")) {
            assertTrue(url.contains(needle), "URL 里该有 $needle，实际：$url")
        }
        // cls 是空串 ⇒ 不发（serve 那一侧空串与缺省同义）；keyword 里的中文按百分号编码
        assertTrue(!url.contains("cls="), "空 cls 不该发出去：$url")
        assertTrue(url.contains("keyword=%E7%A4%BA%E4%BE%8B%E6%95%99%E6%9D%90"), "中文关键词要编码：$url")
    }

    @Test
    fun `search 空结果那一档是空列表，不是错误`() = runTest {
        val result = api(
            engineFor(
                "/api/jiaocai1/search" to
                    """{"code":0,"data":{"keyword":"没有这本","field":"bookName","cls":"","page":1,
                       "totalRows":0,"currentPage":1,"totalPages":1,"count":0,"books":[]}}""",
            ),
        ).search(keyword = "没有这本", field = Jiaocai1SearchField.BOOK_NAME, cls = "", page = 1)

        assertTrue(result.books.isEmpty())
        assertEquals(0, result.totalRows)
        assertTrue(!result.hasMore)
    }

    @Test
    fun `search 的两格分页缺了就当第一页与共一页（不编出还有下一页）`() = runTest {
        val result = api(
            engineFor(
                "/api/jiaocai1/search" to
                    """{"code":0,"data":{"keyword":"示例","field":"bookName","cls":"","page":1,"count":0,"books":[]}}""",
            ),
        ).search(keyword = "示例", field = Jiaocai1SearchField.BOOK_NAME, cls = "", page = 1)

        assertEquals(1, result.currentPage)
        assertEquals(1, result.totalPages)
        assertTrue(!result.hasMore)
    }

    // ── 打开一本书 ────────────────────────────────────────────────────

    @Test
    fun `book 的页表八组逐组读对、总页数 34，且 jpgPath 不投影`() = runTest {
        val calls = mutableListOf<Call>()
        val handle = api(engineFor("/api/jiaocai1/book" to bookBody, calls = calls)).book("990001")!!

        assertEquals("990001", handle.ssno)
        assertEquals("示例教材（第二版）", handle.title)
        assertEquals(Jiaocai1Paging.TYPE_COUNT, handle.pageRanges.size, "页型必须正好八组")
        assertEquals(1..1, handle.pageRanges[0])
        assertEquals(1..20, handle.pageRanges[5], "正文那一组是 [1,20]")
        assertEquals(2..2, handle.pageRanges[7], "封底复用 cov 前缀，编号从 2 起")
        assertEquals(20, handle.bookPages)
        assertEquals(34, handle.pages.size, "摊平之后 34 页（`pageCount` 就是这一格）")
        assertEquals("第 1 页", handle.pages.first { it.typeIndex == Jiaocai1Paging.TYPE_CONTENT }.label)

        // ⚠️ 契约刻意不投影 `jpgPath`（那是服务端按会话下发的令牌路径）⇒ 这一格是空串
        assertEquals("", handle.jpgPath)

        assertEquals("/api/jiaocai1/book", calls.single().path)
        assertTrue(calls.single().url.endsWith("ssno=990001"), "实际 URL：${calls.single().url}")
    }

    @Test
    fun `book 回 null（这本书打不开）与页表不是八组要分清`() = runTest {
        val missing = api(
            engineFor("/api/jiaocai1/book" to """{"code":0,"data":{"ssno":"990002","book":null}}"""),
        ).book("990002")
        assertNull(missing, "没有开放全文 ⇒ 200 + book:null ⇒ 这里也是 null（不是异常）")

        val error = assertFailsWith<IllegalStateException> {
            api(
                engineFor(
                    "/api/jiaocai1/book" to
                        """{"code":0,"data":{"ssno":"990001","book":{"title":"示例","pageRanges":[[1,1],[1,1]],"bookPages":1,"pageCount":2}}}""",
                ),
            ).book("990001")
        }
        assertTrue(error.message.orEmpty().contains("8"), "错误要说清页表不是八组：${error.message}")
    }

    // ── 书架 ──────────────────────────────────────────────────────────

    @Test
    fun `shelf 一行八格逐字段，两个时间是毫秒原样读`() = runTest {
        val row = api(engineFor("/api/jiaocai1/shelf" to shelfBody)).shelf().single()

        assertEquals("990001", row.ssno)
        assertEquals("示例教材（第二版）", row.title)
        assertEquals("示例作者", row.author)
        assertEquals("https://jiaocai1.lib.xjtu.edu.cn/jpath/cover/990001.jpg", row.coverUrl)
        assertEquals(34, row.totalPages)
        assertEquals(12, row.lastReadIndex)
        // 契约 §5.14：这一族的两格是 epoch 毫秒（不是 ISO-8601）⇒ 原样读，不许转
        assertEquals(1_792_548_000_000L, row.lastReadAt)
        assertEquals(1_792_450_000_000L, row.addedAt)
    }

    @Test
    fun `upsert 发的请求体就是契约那几格，回的是整份书架`() = runTest {
        val calls = mutableListOf<Call>()
        val rows = api(
            engineFor("/api/jiaocai1/shelf" to shelfBody, calls = calls),
        ).upsert(
            Jiaocai1ShelfRecord(
                ssno = "990001",
                title = "示例教材（第二版）",
                author = "示例作者",
                coverUrl = "https://jiaocai1.lib.xjtu.edu.cn/jpath/cover/990001.jpg",
                totalPages = 34,
                lastReadIndex = 0,
                lastReadAt = 1_792_548_000_000L,
                addedAt = 1_792_450_000_000L,
            ),
        )

        assertEquals(1, rows.size)
        assertEquals("990001", rows.single().ssno)
        assertEquals(HttpMethod.Post, calls.single().method)
        assertEquals("/api/jiaocai1/shelf", calls.single().path)
        val body = calls.single().body.orEmpty()
        for (needle in listOf(
            "\"ssno\":\"990001\"",
            "\"title\":\"示例教材（第二版）\"",
            "\"author\":\"示例作者\"",
            "\"totalPages\":34",
            "\"lastReadIndex\":0",
            "\"lastReadAt\":1792548000000",
            "\"addedAt\":1792450000000",
        )) {
            assertTrue(body.contains(needle), "请求体里该有 $needle，实际：$body")
        }
    }

    @Test
    fun `updateProgress 只发三列加一个时间，走 shelf-progress 子路径`() = runTest {
        val calls = mutableListOf<Call>()
        val rows = api(
            engineFor("/api/jiaocai1/shelf/progress" to shelfBody, calls = calls),
        ).updateProgress(ssno = "990001", index = 12, at = 1_792_548_000_000L, totalPages = 34)

        assertEquals(1, rows.size)
        assertEquals(HttpMethod.Post, calls.single().method)
        assertEquals("/api/jiaocai1/shelf/progress", calls.single().path)
        val body = calls.single().body.orEmpty()
        assertTrue(body.contains("\"lastReadIndex\":12"), "实际：$body")
        assertTrue(body.contains("\"totalPages\":34"), "实际：$body")
        assertTrue(body.contains("\"at\":1792548000000"), "实际：$body")
        assertTrue(!body.contains("\"title\""), "只改进度三列，不该带书名那一格：$body")
    }

    @Test
    fun `delete 走 DELETE 到 shelf-ssno，回整份书架`() = runTest {
        val calls = mutableListOf<Call>()
        val rows = api(
            engineFor("/api/jiaocai1/shelf/990001" to """{"code":0,"data":{"count":0,"rows":[]}}""", calls = calls),
        ).delete("990001")

        assertTrue(rows.isEmpty())
        assertEquals(HttpMethod.Delete, calls.single().method)
        assertEquals("/api/jiaocai1/shelf/990001", calls.single().path)
        assertNull(calls.single().body, "DELETE 不带请求体")
    }

    @Test
    fun `书架那一族的业务拒绝（code 不是 0）是异常，文案原样带上`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            api(engineFor("/api/jiaocai1/shelf" to """{"code":502,"message":"上游取不到书架"}""")).shelf()
        }
        assertTrue(error.message.orEmpty().contains("上游取不到书架"), "实际：${error.message}")
    }

    // ── 两条二进制端点的路径 ──────────────────────────────────────────

    @Test
    fun `pagePath 与 coverPath 把参数编码好（字节由宿主那一端取）`() {
        val api = api(engineFor())

        assertEquals("/api/jiaocai1/page?ssno=990001&index=0", api.pagePath("990001", 0))
        assertEquals("/api/jiaocai1/page?ssno=990001&index=33", api.pagePath("990001", 33))
        assertEquals(
            "/api/jiaocai1/cover?url=https%3A%2F%2Fjiaocai1.lib.xjtu.edu.cn%2Fjpath%2Fcover%2F990001.jpg",
            api.coverPath("https://jiaocai1.lib.xjtu.edu.cn/jpath/cover/990001.jpg"),
        )
    }

    // ── 形态闸门 ──────────────────────────────────────────────────────

    @Test
    fun `campus-api 形态一律 serveOnly（这一族只在 serve 契约里）`() = runTest {
        val api = api(engineFor(), mode = ApiMode.CAMPUS_API)
        for (block in listOf<suspend () -> Unit>(
            { api.classify() },
            { api.search("示例", Jiaocai1SearchField.BOOK_NAME, "", 1) },
            { api.book("990001") },
            { api.shelf() },
            { api.delete("990001") },
        )) {
            val error = assertFailsWith<UnsupportedOperationException> { block() }
            assertTrue(
                error.message.orEmpty().contains("serve"),
                "要说清「只在 serve 模式下有」：${error.message}",
            )
        }
    }
}
