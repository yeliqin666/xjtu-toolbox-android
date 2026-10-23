package com.xjtu.toolbox.server

import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.platform.wipeSecureStore
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 教材全文库（`jiaocai1`）在 serve 侧的契约测试（`docs/api-contract.md` §5.14）。 */
class ServeJiaocai1Test {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    /** 起一台只挂 jiaocai1 那一族的 serve（与其余契约测试同一套脚手架）。 */
    private fun serve(session: ServeSession, token: String): EndpointHarness {
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jiaocai1Routes(session)
        }
        started = server
        return http
    }

    /**
     * 每个用例开头清一遍**进程级**的东西：装配那份（按账号记忆）、书架那份店。
     * 不这样的话同一个 JVM 里先跑的用例会把书架带进这一轮（`SecureStore` 的 memo 只按名字记，
     * 见那个文件的「已知耦合」）。
     */
    private fun resetProcessState() {
        ServeJiaocai1Runtime.reset()
        wipeSecureStore(ServeJiaocai1ShelfStore.storeName(LibraryFakeUpstream.USERNAME))
    }

    // ── 分类树 + 检索 ──────────────────────────────────────────────────

    @Test
    fun `classify 与 search 未登录 401，登录后逐字段（分类树两级、检索八格）`() = withFakeProxy { fake ->
        resetProcessState()
        val token = AccessToken.newToken()
        val session = ServeSession()
        val http = serve(session, token)

        for (path in listOf(CLASSIFY_PATH, "$SEARCH_PATH?keyword=x")) {
            val anonymous = http.get(path)
            assertEquals(401, anonymous.statusCode(), path)
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
            assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」：$path")
        }
        assertEquals(0, fake.jiaocai1.searchCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── 分类树：节点就是 `:core` 的 `Jiaocai1Category`（零转换），两级 ──
        val classify = http.get(CLASSIFY_PATH)
        assertEquals(200, classify.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(classify))
        assertNoIdentity(classify.body(), "GET $CLASSIFY_PATH", IDENTITY)
        val classifyData = envelopeData(classify)
        assertEquals(setOf("count", "nodes"), classifyData.keys)
        assertEquals(1, classifyData.optInt("count"), "夹具给一个一级类")
        val root = classifyData.arrOf("nodes")[0] as JsonObject
        assertEquals(
            setOf("id", "name", "level", "parentId", "nodeId", "children"),
            root.keys,
            "`:core` 的 Jiaocai1Category 原样端出（含 children）",
        )
        assertEquals(Jiaocai1FakeUpstream.ROOT_CATEGORY_ID, root.optString("id"), "id 就是检索参数 cls")
        assertEquals(Jiaocai1FakeUpstream.ROOT_CATEGORY_NAME, root.optString("name"))
        assertEquals(1, root.optInt("level"))
        val children = root.arrOf("children")
        assertEquals(1, children.size, "`buildTree` 按 pid 挂上一级")
        val child = children[0] as JsonObject
        assertEquals(Jiaocai1FakeUpstream.CHILD_CATEGORY_ID, child.optString("id"))
        assertEquals(Jiaocai1FakeUpstream.CHILD_CATEGORY_NAME, child.optString("name"))
        assertEquals(2, child.optInt("level"))
        assertTrue(child.arrOf("children").isEmpty(), "叶子没有下一级")

        // ── 检索：一本书八格逐字段 ──
        val keyword = "电路"
        val found = http.get("$SEARCH_PATH?keyword=${encode(keyword)}")
        assertEquals(200, found.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(found))
        assertNoIdentity(found.body(), "GET $SEARCH_PATH", IDENTITY)
        assertEquals(1, fake.jiaocai1.searchCalls.get(), "真打了一次检索")
        assertTrue(
            fake.jiaocai1.lastSearchBody.orEmpty().contains("searchField=bookName"),
            "省略 field ⇒ 打给上游的是默认那个（bookName），实际：${fake.jiaocai1.lastSearchBody}",
        )

        val data = envelopeData(found)
        assertEquals(
            setOf("keyword", "field", "cls", "page", "totalRows", "currentPage", "totalPages", "count", "books"),
            data.keys,
        )
        assertEquals(keyword, data.optString("keyword"), "检索词原样回")
        assertEquals("bookName", data.optString("field"), "省略 field ⇒ 回默认值（不是空串）")
        assertEquals("", data.optString("cls"), "没给 cls ⇒ 空串")
        assertEquals(31, data.optInt("totalRows"))
        assertEquals(1, data.optInt("currentPage"))
        assertEquals(2, data.optInt("totalPages"), "夹具那一页写着 sum-page=2（屏上的「加载更多」靠它）")
        assertEquals(1, data.optInt("count"))

        val book = data.arrOf("books")[0] as JsonObject
        assertEquals(
            setOf("ssno", "title", "author", "publishDate", "themeWord", "callNo", "classifyPath", "coverUrl"),
            book.keys,
            "`:core` 的 Jiaocai1Book 那八格",
        )
        assertEquals(Jiaocai1FakeUpstream.SSNO, book.optString("ssno"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_TITLE, book.optString("title"), "title 已剥掉标签")
        assertEquals(Jiaocai1FakeUpstream.BOOK_AUTHOR, book.optString("author"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_PUBLISH_DATE, book.optString("publishDate"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_THEME_WORD, book.optString("themeWord"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_CALL_NO, book.optString("callNo"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_CLASSIFY_PATH, book.optString("classifyPath"))
        assertEquals(
            "${Jiaocai1FakeUpstream.BASE}/cover/${Jiaocai1FakeUpstream.SSNO}.jpg",
            book.optString("coverUrl"),
            "封面地址原样给出来（要字节就打 cover 那一条）",
        )

        // ── 第二页（屏上的「加载更多」）──
        val page2 = http.get("$SEARCH_PATH?keyword=${encode(keyword)}&page=2")
        assertEquals(200, page2.statusCode())
        val page2Data = envelopeData(page2)
        assertEquals(2, page2Data.optInt("page"))
        assertEquals(Jiaocai1FakeUpstream.SSNO_PAGE2, (page2Data.arrOf("books")[0] as JsonObject).optString("ssno"))

        // ── 空结果那一档：count 0 + 空数组（不是错误）──
        val miss = http.get("$SEARCH_PATH?keyword=${encode(Jiaocai1FakeUpstream.MISS_KEYWORD)}")
        assertEquals(200, miss.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(miss))
        assertEquals(0, envelopeData(miss).optInt("count"))
        assertTrue(envelopeData(miss).arrOf("books").isEmpty())
    }

    @Test
    fun `search 参数形状：keyword 与 cls 都空 400，field 认不出 400，page 不是正整数 400`() = withFakeProxy { fake ->
        resetProcessState()
        val token = AccessToken.newToken()
        val session = ServeSession()
        val http = serve(session, token)
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 两个查询条件都不给 ⇒ 400（`:data` 两种用法都支持，但都空就是白问一次上游）
        val bare = http.get(SEARCH_PATH)
        assertEquals(400, bare.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bare))
        assertTrue(envelopeMessage(bare).orEmpty().contains("keyword"), "400 要说出缺什么")

        // 只给 cls（按分类浏览）是**合法**的
        val byCls = http.get("$SEARCH_PATH?cls=${Jiaocai1FakeUpstream.CHILD_CATEGORY_ID}")
        assertEquals(200, byCls.statusCode())
        assertEquals(Jiaocai1FakeUpstream.CHILD_CATEGORY_ID, envelopeData(byCls).optString("cls"))
        assertEquals("", envelopeData(byCls).optString("keyword"))
        assertTrue(
            fake.jiaocai1.lastSearchBody.orEmpty().contains("cls=${Jiaocai1FakeUpstream.CHILD_CATEGORY_ID}"),
            "cls 原样打给上游，实际：${fake.jiaocai1.lastSearchBody}",
        )

        val callsAfterCls = fake.jiaocai1.searchCalls.get()

        // field 只认那五个 key
        val badField = http.get("$SEARCH_PATH?keyword=x&field=bookTitle")
        assertEquals(400, badField.statusCode())
        assertTrue(envelopeMessage(badField).orEmpty().contains("field"), "400 要说出是哪个参数")

        // page 不是正整数
        for (bad in listOf("0", "-1", "abc")) {
            val response = http.get("$SEARCH_PATH?keyword=x&page=$bad")
            assertEquals(400, response.statusCode(), bad)
            assertTrue(envelopeMessage(response).orEmpty().contains("page"), "400 要说出是哪个参数：$bad")
        }
        assertEquals(callsAfterCls, fake.jiaocai1.searchCalls.get(), "400 那几枪都没到取数层")

        // 显式给 field 就走那一个
        val byAuthor = http.get("$SEARCH_PATH?keyword=%E7%A4%BA%E4%BE%8B&field=bookAuthor")
        assertEquals(200, byAuthor.statusCode())
        assertEquals("bookAuthor", envelopeData(byAuthor).optString("field"))
        assertTrue(
            fake.jiaocai1.lastSearchBody.orEmpty().contains("searchField=bookAuthor"),
            "显式 field 原样打给上游，实际：${fake.jiaocai1.lastSearchBody}",
        )
    }

    // ── 打开一本书 ─────────────────────────────────────────────────────

    @Test
    fun `book 只回页表那一半：八组页型与总页数，jpgPath 不投影`() = withFakeProxy { fake ->
        resetProcessState()
        val token = AccessToken.newToken()
        val session = ServeSession()
        val http = serve(session, token)

        val anonymous = http.get("$BOOK_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}")
        assertEquals(401, anonymous.statusCode())
        assertEquals(0, fake.jiaocai1.pageImageCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val noSsno = http.get(BOOK_PATH)
        assertEquals(400, noSsno.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(noSsno))
        assertTrue(envelopeMessage(noSsno).orEmpty().contains("ssno"), "400 要说出缺什么")
        assertEquals(400, http.get("$BOOK_PATH?ssno=%20").statusCode(), "空白串也是缺")

        val response = http.get("$BOOK_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $BOOK_PATH", IDENTITY)
        assertEquals(Jiaocai1FakeUpstream.SSNO, fake.jiaocai1.lastReaderSsno, "打给上游的 ssno 就是请求里那个")

        val data = envelopeData(response)
        assertEquals(setOf("ssno", "book"), data.keys)
        assertEquals(Jiaocai1FakeUpstream.SSNO, data.optString("ssno"))

        val book = assertNotNull(data.optObject("book"), "这本书应当打得开")
        assertEquals(setOf("title", "pageRanges", "bookPages", "pageCount"), book.keys)
        assertEquals(Jiaocai1FakeUpstream.BOOK_TITLE, book.optString("title"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_PAGES, book.optInt("bookPages"), "正文页数原样")

        // 八组 [起, 止] 与 `:core` 的 `Jiaocai1Paging.TYPE_NAMES` 逐位对应
        val ranges = book.getValue("pageRanges").jsonArray
        assertEquals(8, ranges.size, "页型是 8 种（Jiaocai1Paging.TYPE_COUNT）")
        assertEquals(
            Jiaocai1FakeUpstream.PAGE_RANGES.map { listOf(it.first, it.last) },
            ranges.map { pair -> pair.jsonArray.map { it.toString().toInt() } },
        )
        // 摊平之后是 34 页（2+1+1+1+3+24+1+1）—— 与桌面那份测试钉的是同一个数
        assertEquals(34, book.optInt("pageCount"))

        // ⚠️ `jpgPath` 是会话令牌路径：它一个字符都不该出现在响应里（夹具里明明有值）
        assertFalse(response.body().contains(Jiaocai1FakeUpstream.JPG_PATH), "jpgPath 不投影")
        assertFalse(response.body().contains("jpgPath"), "连键名都不该有")
    }

    // ── 页图与封面（二进制那两条）──────────────────────────────────────

    @Test
    fun `page 真回 JPEG 字节，页序越界与缺参 400`() = withFakeProxy { fake ->
        resetProcessState()
        val token = AccessToken.newToken()
        val session = ServeSession()
        val http = serve(session, token)

        val anonymous = http.getBytes("$PAGE_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}&index=0")
        assertEquals(401, anonymous.statusCode(), "未登录那一枪仍是信封的 401")
        // 字节那一条的**失败**仍是信封（§4 的例外只放宽成功那一半）
        assertEquals(
            ApiErrors.UNAUTHORIZED,
            anonymous.body().toString(Charsets.UTF_8).parseJsonObject().getValue("code").toString().toInt(),
        )

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val noIndex = http.get("$PAGE_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}")
        assertEquals(400, noIndex.statusCode())
        assertTrue(envelopeMessage(noIndex).orEmpty().contains("index"), "400 要说出缺什么")
        assertEquals(400, http.get("$PAGE_PATH?index=0").statusCode(), "ssno 也必填")
        assertEquals(400, http.get("$PAGE_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}&index=-1").statusCode())

        // 第一页：真字节（JPEG 魔数 + Content-Type）
        val first = http.getBytes("$PAGE_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}&index=0")
        assertEquals(200, first.statusCode())
        assertTrue(
            first.headers().firstValue("Content-Type").orElse("").contains("image/jpeg"),
            "成功那一半回的是图片，实际：${first.headers().firstValue("Content-Type").orElse("")}",
        )
        val bytes = first.body()
        assertTrue(bytes.size > 8, "页图不该是空体（长度 ${bytes.size}）")
        assertEquals(0xFF.toByte(), bytes[0], "JPEG 魔数第一字节")
        assertEquals(0xD8.toByte(), bytes[1], "JPEG 魔数第二字节")
        assertTrue(fake.jiaocai1.pageImageCalls.get() > 0, "真打了一次取图")

        // 第二页是另一种页型（cov002）—— 页表顺序与 `:core` 的 flatten 一致
        val second = http.getBytes("$PAGE_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}&index=1")
        assertEquals(200, second.statusCode())
        assertTrue(second.body().size > 8)

        // 页序越界是**参数**错：这本书共 34 页（从 0 起）
        val beyond = http.get("$PAGE_PATH?ssno=${Jiaocai1FakeUpstream.SSNO}&index=34")
        assertEquals(400, beyond.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(beyond))
        assertTrue(envelopeMessage(beyond).orEmpty().contains("34"), "400 要说出这本书有多少页")
    }

    @Test
    fun `cover 只认全文库自己的地址，别的域名一律 400 且不取数`() = withFakeProxy {
        resetProcessState()
        val token = AccessToken.newToken()
        val session = ServeSession()
        val http = serve(session, token)
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val noUrl = http.get(COVER_PATH)
        assertEquals(400, noUrl.statusCode())
        assertTrue(envelopeMessage(noUrl).orEmpty().contains("url"), "400 要说出缺什么")

        // 这一条不是通用取图代理：别的域名 / 别的协议一律拒（挡 SSRF 与「拿 serve 当代理」）
        for (bad in listOf(
            "https://example.com/a.jpg",
            "https://jiaocai1.lib.xjtu.edu.cn.evil.com/a.jpg",
            "https://evil.com/?x=jiaocai1.lib.xjtu.edu.cn",
            "file:///etc/passwd",
            "http://127.0.0.1:8123/api/status",
        )) {
            val response = http.get("$COVER_PATH?url=${encode(bad)}")
            assertEquals(400, response.statusCode(), bad)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(response), bad)
            assertTrue(envelopeMessage(response).orEmpty().contains(JIAOCAI1_HOST), "400 要说清只认哪个域名：$bad")
        }
        // ⚠️ 封面与页图一样，要先有会话：`Jiaocai1PageLoader.coverBytes` 走的是取字节那条路
        // （不像 `classify` / `search` / `openBook` 会自己跟一遍 `portalLogin` 跳转链）。
        // 真实屏上总是「先检索、再画封面」，所以这一条按同一次序来。
        assertEquals(200, http.get("$SEARCH_PATH?keyword=%E7%A4%BA%E4%BE%8B").statusCode())

        // 正主：全文库自己的封面 → 真字节
        val coverUrl = "${Jiaocai1FakeUpstream.BASE}/cover/${Jiaocai1FakeUpstream.SSNO}.jpg"
        val cover = http.getBytes("$COVER_PATH?url=${encode(coverUrl)}")
        assertEquals(200, cover.statusCode())
        assertTrue(
            cover.headers().firstValue("Content-Type").orElse("").contains("image/jpeg"),
            "封面也回图片，实际：${cover.headers().firstValue("Content-Type").orElse("")}",
        )
        assertEquals(0xFF.toByte(), cover.body()[0], "JPEG 魔数")
        assertEquals(0xD8.toByte(), cover.body()[1], "JPEG 魔数")
    }

    // ── 书架（serve 侧按账号落盘）──────────────────────────────────────

    @Test
    fun `书架：空表到加一本到改进度到移出，每一步都回整份`() = withFakeProxy { fake ->
        resetProcessState()
        val token = AccessToken.newToken()
        val session = ServeSession()
        val http = serve(session, token)

        val anonymous = http.get(SHELF_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── 空表（不是 404）──
        val blank = http.get(SHELF_PATH)
        assertEquals(200, blank.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(blank))
        assertEquals(0, envelopeData(blank).optInt("count"))
        assertTrue(envelopeData(blank).arrOf("rows").isEmpty())

        // 请求体形状不对 ⇒ 400，且什么都没动
        assertEquals(400, http.postJson(SHELF_PATH, "{}").statusCode(), "ssno 是必填")
        assertEquals(400, http.postJson(SHELF_PATH, "not json").statusCode())
        assertEquals(
            400,
            http.postJson(SHELF_PATH, """{"ssno":"${Jiaocai1FakeUpstream.SSNO}","lastReadIndex":-1}""").statusCode(),
        )

        // ── 加一本（阅读器打开时那一发就是整行 upsert）──
        val added = http.postJson(
            SHELF_PATH,
            """
            {"ssno":"${Jiaocai1FakeUpstream.SSNO}","title":"${Jiaocai1FakeUpstream.BOOK_TITLE}",
             "author":"${Jiaocai1FakeUpstream.BOOK_AUTHOR}",
             "coverUrl":"${Jiaocai1FakeUpstream.BASE}/cover/${Jiaocai1FakeUpstream.SSNO}.jpg",
             "totalPages":34,"lastReadIndex":3}
            """.trimIndent(),
        )
        assertEquals(200, added.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(added))
        assertNoIdentity(added.body(), "POST $SHELF_PATH", IDENTITY)
        val addedData = envelopeData(added)
        assertEquals(setOf("count", "rows"), addedData.keys)
        assertEquals(1, addedData.optInt("count"))
        val row = addedData.arrOf("rows")[0] as JsonObject
        assertEquals(
            setOf("ssno", "title", "author", "coverUrl", "totalPages", "lastReadIndex", "lastReadAt", "addedAt"),
            row.keys,
            "一行就是 `:core` 的 Jiaocai1ShelfRecord 那八格",
        )
        assertEquals(Jiaocai1FakeUpstream.SSNO, row.optString("ssno"))
        assertEquals(Jiaocai1FakeUpstream.BOOK_TITLE, row.optString("title"))
        assertEquals(34, row.optInt("totalPages"))
        assertEquals(3, row.optInt("lastReadIndex"))
        // 两格时间省略 = 此刻（epoch 毫秒，不是 ISO —— 契约 §5.14 记着这一条）
        val lastReadAt = assertNotNull(row.optLong("lastReadAt"), "省略 lastReadAt ⇒ 此刻")
        assertTrue(lastReadAt > 1_600_000_000_000L, "看起来得像一个 epoch 毫秒：$lastReadAt")
        assertEquals(lastReadAt, row.optLong("addedAt"), "同一发里两格都是「此刻」")

        // ── 只改进度三列 ──
        val progressed = http.postJson(
            "$SHELF_PATH/$SHELF_PROGRESS",
            """{"ssno":"${Jiaocai1FakeUpstream.SSNO}","lastReadIndex":7,"totalPages":34}""",
        )
        assertEquals(200, progressed.statusCode())
        val progressedRow = envelopeData(progressed).arrOf("rows")[0] as JsonObject
        assertEquals(7, progressedRow.optInt("lastReadIndex"))
        assertEquals(1, envelopeData(progressed).optInt("count"), "改的是同一行，不是新增")
        assertEquals(Jiaocai1FakeUpstream.BOOK_TITLE, progressedRow.optString("title"), "只动进度三列，其余整行保留")

        // 不在书架里的 ssno：**空操作**（与 `:core` 的 updateProgress 同义），不是错误
        val orphan = http.postJson(
            "$SHELF_PATH/$SHELF_PROGRESS",
            """{"ssno":"999999999","lastReadIndex":1,"totalPages":2}""",
        )
        assertEquals(200, orphan.statusCode())
        assertEquals(1, envelopeData(orphan).optInt("count"), "没在书架里就不该凭空长出一行")

        // ── 移出书架 ──
        val removed = http.delete("$SHELF_PATH/${Jiaocai1FakeUpstream.SSNO}")
        assertEquals(200, removed.statusCode())
        assertEquals(0, envelopeData(removed).optInt("count"))
        assertTrue(envelopeData(removed).arrOf("rows").isEmpty())

        // 删一个不在书架里的：同样是空操作（幂等）
        val again = http.delete("$SHELF_PATH/${Jiaocai1FakeUpstream.SSNO}")
        assertEquals(200, again.statusCode())
        assertEquals(0, envelopeData(again).optInt("count"))

        // 书架全程不碰上游（它是 serve 自己那份落盘）
        assertEquals(0, fake.jiaocai1.searchCalls.get(), "书架读写一枪都不该打上游")
        assertEquals(0, fake.jiaocai1.pageImageCalls.get())
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val CLASSIFY_PATH = "/api/jiaocai1/classify"
        const val SEARCH_PATH = "/api/jiaocai1/search"
        const val BOOK_PATH = "/api/jiaocai1/book"
        const val PAGE_PATH = "/api/jiaocai1/page"
        const val COVER_PATH = "/api/jiaocai1/cover"
        const val SHELF_PATH = "/api/jiaocai1/shelf"
        const val SHELF_PROGRESS = "progress"

        /** §5.2 的红线钉子组（全是夹具里的编造值，不是真人）。 */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            LibraryFakeUpstream.PASSWORD,
        )

        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}

/** `data.someBlock`：是对象就给出来，`null`（或者压根没这一格）就给 `null`。 */
private fun JsonObject.optObject(key: String): JsonObject? = this[key]?.takeIf { it is JsonObject }?.jsonObject
