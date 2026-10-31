package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.jiaocai1.AppJiaocai1Source
import com.xjtu.toolbox.jiaocai1.Jiaocai1Book
import com.xjtu.toolbox.jiaocai1.Jiaocai1BookHandle
import com.xjtu.toolbox.jiaocai1.Jiaocai1Category
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfStore
import com.xjtu.toolbox.jiaocai1.Jiaocai1Source
import com.xjtu.toolbox.platform.dataRootDir
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * `/api/jiaocai1` —— 教材全文库（`jiaocai1.lib.xjtu.edu.cn`）在浏览器上的那一族
 * （`docs/api-contract.md` §5.14）。
 *
 * 与其余二十多族同一条做法：**`:data` 里早有取数实现**（`Jiaocai1Api` / `Jiaocai1PageLoader`，
 * Android 与桌面直连在用），本层只做投影。会话借的是**教材中心**那个站点（`jiaocai`）——
 * 全文库的会话由 `jiaocai.lib` 那一侧的 `portalLogin` 桥接过来（`:data` 的 `PortalRedirect`），
 * 与桌面 `DesktopSiteGate(JIAOCAI_SITE_KEY)` 走的是同一条，**一个新站点都没注册**。
 *
 * | 端点 | 方法 | 干什么 |
 * |---|---|---|
 * | [JIAOCAI1_CLASSIFY] | `GET` | 四级中图法分类树 |
 * | [JIAOCAI1_SEARCH] | `GET` | 检索（关键词 / 分类浏览二选一或并用） |
 * | [JIAOCAI1_BOOK] | `GET` | 用 `ssno` 换阅读句柄（页表那一半） |
 * | [JIAOCAI1_PAGE] | `GET` | 一页的字节（`image/jpeg`，见下面「二进制那两条」） |
 * | [JIAOCAI1_COVER] | `GET` | 封面小图的字节（同上） |
 * | [JIAOCAI1_SHELF] | `GET` | 书架（按账号，落在 serve 侧） |
 * | [JIAOCAI1_SHELF] | `POST` | 加一本书 / 整行覆盖（阅读器打开时那一发） |
 * | `[JIAOCAI1_SHELF]/[JIAOCAI1_SHELF_PROGRESS]` | `POST` | 只改进度三列（翻页时那一发） |
 * | `[JIAOCAI1_SHELF]/{ssno}` | `DELETE` | 移出书架 |
 *
 * ## 二进制那两条（`page` / `cover`）
 *
 * 这两条**成功时回字节**（`image/jpeg`）、**失败时仍回信封** —— 即契约 §4 的第四条例外
 * （前三条：`/api/status` 裸对象、`venue/pay` 302、成绩单的 `application/pdf`）。
 * 为什么不塞 data URI（`venue/captcha` 那一条先例）：扫描页一张几百 KB，
 * base64 是体积乘 1.33 且整张进内存；而屏上就是一张位图，`serve_token` 是 `Path=/` 的 cookie
 * ⇒ 直接当图片取即可。
 *
 * ## 如实边界
 *
 * - **`jpgPath` 不投影**：那是服务端按会话下发的令牌路径（`:core` 的 `Jiaocai1BookHandle` 自己也
 *   写着「不要持久化」）。客户端要的是**页表**（每页的页型与编号），取字节走 [JIAOCAI1_PAGE]。
 * - **封面只认全文库自己的地址**（[isJiaocai1Url]）：`?url=` 是客户端从检索结果里带回来的，
 *   不校验就等于把 serve 变成一台谁都能用的取图代理。
 * - **封面要在会话建立之后才拿得到**：`:data` 的 `Jiaocai1PageLoader.coverBytes` 走的是取字节那条路，
 *   **不会**像 `classify` / `search` / `openBook` 那样自己跟一遍 `portalLogin` 跳转链 ⇒ 全新会话里
 *   先取封面会拿回跳转页（按「取不到」回 502）。真实屏上的次序就是「先检索、再画封面」，所以这不算缺路；
 *   页图那一侧因为要先 `book` 拿到句柄，天然满足。
 * - **`prefetchWindow` 在这一端是空的**：预取是「一台设备上本地磁盘 + 内存 LRU」的优化
 *   （`:data` 的 `Jiaocai1PageLoader`），浏览器这一侧一页一发请求即可，本层不为它多开端点。
 * - **书架与桌面不共享**（各用自己的店名，理由见 [ServeJiaocai1ShelfStore] 的 KDoc）。
 */

/** 这一族的段名。 */
internal const val JIAOCAI1_SEGMENT = "jiaocai1"

/** 这一族的根路径。 */
internal const val JIAOCAI1_PATH = "$API_PREFIX/$JIAOCAI1_SEGMENT"

internal const val JIAOCAI1_CLASSIFY = "classify"
internal const val JIAOCAI1_SEARCH = "search"
internal const val JIAOCAI1_BOOK = "book"
internal const val JIAOCAI1_PAGE = "page"
internal const val JIAOCAI1_COVER = "cover"
internal const val JIAOCAI1_SHELF = "shelf"

/** 只改进度三列的那一条（挂在 [JIAOCAI1_SHELF] 之下）。 */
internal const val JIAOCAI1_SHELF_PROGRESS = "progress"

/**
 * serve 侧的 jiaocai1 装配：**按账号一份**（取数源 + 阅读句柄缓存 + 书架店）。
 *
 * 与 [ServeDownloads] 那种进程级单例的区别：那一份把账号**写死在了构造那一刻**（店名与实例都跟着
 * 第一个账号走，见 `docs/parity-ledger.md` §6.4 记的那条账）。这一份不那样做 ——
 * 账号一换（[AccountContext.switchEpoch] 变）就整份重建，因为：
 *
 * - 阅读句柄里的 `jpgPath` 是**会话令牌**，跨账号一定失效；
 * - 页缓存与书架店都是按账号分的落点。
 */
internal class ServeJiaocai1(
    val source: Jiaocai1Source,
    val shelf: Jiaocai1ShelfStore,
) {

    private val handles = ConcurrentHashMap<String, Jiaocai1BookHandle>()

    /**
     * 拿阅读句柄。[refresh] 为真时**丢掉缓存重开一次**（页图取不到时用一次：
     * 上游会话轮换之后旧 `jpgPath` 会一直给空体）。
     */
    suspend fun handle(ssno: String, refresh: Boolean = false): Jiaocai1BookHandle? {
        if (!refresh) handles[ssno]?.let { return it }
        val opened = source.openBook(ssno) ?: return null
        handles[ssno] = opened
        return opened
    }

    /** 丢掉一本书的句柄（重开之后、移出书架时）。 */
    fun forget(ssno: String) {
        handles.remove(ssno)
    }

    /** 账号换了 / 进程收尾：把页加载器那个预取作用域收掉。 */
    fun close() {
        handles.clear()
        runCatching { source.closePages() }
    }
}

/** 进程级的 jiaocai1 装配（按账号一份，见 [ServeJiaocai1] 的 KDoc）。 */
internal object ServeJiaocai1Runtime {

    private val lock = Any()
    private var accountId: String? = null
    private var epoch: Long = -1L
    private var current: ServeJiaocai1? = null

    fun of(session: ServeSession): ServeJiaocai1 = synchronized(lock) {
        val id = AccountContext.activeAccountId
        val at = AccountContext.switchEpoch
        val cached = current
        if (cached != null && accountId == id && epoch == at) return cached
        cached?.close()
        val fresh = ServeJiaocai1(
            source = AppJiaocai1Source(session.jiaocaiSite(), jiaocai1CacheRoot()),
            shelf = ServeJiaocai1ShelfStore(),
        )
        current = fresh
        accountId = id
        epoch = at
        fresh
    }

    /** 只给测试用：把这一份丢掉（下一次取会重新装配）。 */
    internal fun reset() = synchronized(lock) {
        current?.close()
        current = null
        accountId = null
        epoch = -1L
    }
}

/** 教材页图的缓存根目录（与桌面 `jiaocai1CacheRoot()` 同一条：数据根下面的 `cache`）。 */
internal fun jiaocai1CacheRoot(): File = File(dataRootDir(), "cache")

internal fun Route.jiaocai1Routes(session: ServeSession) {
    route(JIAOCAI1_SEGMENT) {

        // ── 分类树 ────────────────────────────────────────────────────
        get(JIAOCAI1_CLASSIFY) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val runtime = ServeJiaocai1Runtime.of(session)
            runCatching { runtime.source.classifyTree() }
                .onSuccess { roots -> call.respond(ApiEnvelope.ok(Jiaocai1ClassifyData(roots))) }
                .onFailure { call.respondUpstreamFailure(it, "取教材分类树") }
        }

        // ── 检索（关键词 / 分类浏览）────────────────────────────────────
        get(JIAOCAI1_SEARCH) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val keyword = params[JIAOCAI1_PARAM_KEYWORD]?.trim().orEmpty()
            val cls = params[JIAOCAI1_PARAM_CLS]?.trim().orEmpty()
            // 关键词与分类都空 ⇒ 400（`:data` 的 `search` 两种用法都支持，但都空就是白问一次上游）
            if (keyword.isEmpty() && cls.isEmpty()) {
                return@get call.respondBadRequestMessage(JIAOCAI1_QUERY_MESSAGE)
            }
            val rawField = params[JIAOCAI1_PARAM_FIELD]?.trim()
            val field = if (rawField.isNullOrEmpty()) {
                Jiaocai1SearchField.BOOK_NAME
            } else {
                Jiaocai1SearchField.entries.firstOrNull { it.key == rawField }
                    ?: return@get call.respondBadRequestMessage(JIAOCAI1_FIELD_MESSAGE)
            }
            val page = params.positiveInt(JIAOCAI1_PARAM_PAGE, 1)
                ?: return@get call.respondBadRequestMessage("page 需要是正整数（第几页，从 1 起）")

            val runtime = ServeJiaocai1Runtime.of(session)
            runCatching { runtime.source.search(keyword = keyword, field = field, cls = cls, page = page) }
                .onSuccess { result ->
                    call.respond(
                        ApiEnvelope.ok(
                            Jiaocai1SearchData(
                                keyword = keyword,
                                field = field.key,
                                cls = cls,
                                page = result.currentPage,
                                totalRows = result.totalRows,
                                currentPage = result.currentPage,
                                totalPages = result.totalPages,
                                count = result.books.size,
                                books = result.books.map { it.toDto() },
                            ),
                        ),
                    )
                }
                .onFailure { call.respondUpstreamFailure(it, "检索教材全文库") }
        }

        // ── 打开一本书：只回页表那一半 ─────────────────────────────────
        get(JIAOCAI1_BOOK) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val ssno = call.request.queryParameters[JIAOCAI1_PARAM_SSNO]?.trim().orEmpty()
            if (ssno.isEmpty()) return@get call.respondBadRequestMessage(JIAOCAI1_SSNO_MESSAGE)
            val runtime = ServeJiaocai1Runtime.of(session)
            runCatching { runtime.handle(ssno) }
                .onSuccess { handle ->
                    // 打不开（这本书没有开放全文 / 会话过期那一枪没补上）⇒ 200 + book:null，
                    // 与 `/api/lms/course-match` 的 `matched:false` 同一条：「没有」不是错误。
                    call.respond(ApiEnvelope.ok(Jiaocai1BookData(ssno = ssno, book = handle?.toDto())))
                }
                .onFailure { call.respondUpstreamFailure(it, "打开这本书") }
        }

        // ── 页图 / 封面（成功回字节，失败回信封）──────────────────────
        get(JIAOCAI1_PAGE) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val ssno = call.request.queryParameters[JIAOCAI1_PARAM_SSNO]?.trim().orEmpty()
            if (ssno.isEmpty()) return@get call.respondBadRequestMessage(JIAOCAI1_SSNO_MESSAGE)
            val index = call.requireIntParam(
                JIAOCAI1_PARAM_INDEX,
                "阅读器页序，从 0 起（取自 /api/jiaocai1/book 的 pageCount 之内）",
                min = 0,
            ) ?: return@get
            val runtime = ServeJiaocai1Runtime.of(session)
            runCatching {
                val handle = runtime.handle(ssno)
                    ?: return@runCatching call.respondImageUnavailable("这本书打不开")
                val pageCount = handle.pages.size
                // 页序越界是**参数**错（不是上游故障）：这本书有多少页 serve 这一层知道
                if (index >= pageCount) {
                    return@runCatching call.respondBadRequestMessage(
                        "index 超出这本书的页数（这一本共 $pageCount 页，从 0 起）",
                    )
                }
                val bytes = runtime.pageBytes(handle, index)
                if (bytes == null) call.respondImageUnavailable("第 $index 页")
                else call.respondBytes(bytes, ContentType.Image.JPEG)
            }.onFailure { call.respondUpstreamFailure(it, "取教材页图") }
        }

        get(JIAOCAI1_COVER) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val url = call.request.queryParameters[JIAOCAI1_PARAM_URL]?.trim().orEmpty()
            if (url.isEmpty()) return@get call.respondBadRequestMessage(JIAOCAI1_COVER_MESSAGE)
            if (!isJiaocai1Url(url)) return@get call.respondBadRequestMessage(JIAOCAI1_COVER_HOST_MESSAGE)
            val runtime = ServeJiaocai1Runtime.of(session)
            runCatching { runtime.source.coverBytes(url) }
                .onSuccess { bytes ->
                    if (bytes == null) call.respondImageUnavailable("这张封面")
                    else call.respondBytes(bytes, ContentType.Image.JPEG)
                }
                .onFailure { call.respondUpstreamFailure(it, "取教材封面") }
        }

        // ── 书架（serve 侧按账号落盘）──────────────────────────────────
        route(JIAOCAI1_SHELF) {
            get {
                if (!session.authenticated) return@get call.respondLoginRequired()
                val runtime = ServeJiaocai1Runtime.of(session)
                runCatching { runtime.shelf.snapshot() }
                    .onSuccess { call.respond(ApiEnvelope.ok(Jiaocai1ShelfData(it))) }
                    .onFailure { call.respondUpstreamFailure(it, "读书架") }
            }

            post {
                if (!session.authenticated) return@post call.respondLoginRequired()
                val body = call.receiveOrNull<Jiaocai1ShelfUpsertBody>()
                    ?: return@post call.respondBadRequestMessage(JIAOCAI1_SHELF_BODY_MESSAGE)
                val ssno = body.ssno.trim()
                if (ssno.isEmpty()) return@post call.respondBadRequestMessage(JIAOCAI1_SSNO_MESSAGE)
                if (body.totalPages < 0 || body.lastReadIndex < 0) {
                    return@post call.respondBadRequestMessage("totalPages 与 lastReadIndex 都不能是负数")
                }
                val now = System.currentTimeMillis()
                val runtime = ServeJiaocai1Runtime.of(session)
                val row = Jiaocai1ShelfRecord(
                    ssno = ssno,
                    title = body.title.trim(),
                    author = body.author.trim(),
                    coverUrl = body.coverUrl.trim(),
                    totalPages = body.totalPages,
                    lastReadIndex = body.lastReadIndex,
                    // 两格省略（或给 0）就是「此刻」：调用方多半是刚打开这本书
                    lastReadAt = body.lastReadAt.takeIf { it > 0L } ?: now,
                    addedAt = body.addedAt.takeIf { it > 0L } ?: now,
                )
                runCatching {
                    runtime.shelf.upsert(row)
                    runtime.shelf.snapshot()
                }
                    .onSuccess { call.respond(ApiEnvelope.ok(Jiaocai1ShelfData(it))) }
                    .onFailure { call.respondUpstreamFailure(it, "写书架") }
            }

            post(JIAOCAI1_SHELF_PROGRESS) {
                if (!session.authenticated) return@post call.respondLoginRequired()
                val body = call.receiveOrNull<Jiaocai1ShelfProgressBody>()
                    ?: return@post call.respondBadRequestMessage(JIAOCAI1_SHELF_PROGRESS_BODY_MESSAGE)
                val ssno = body.ssno.trim()
                if (ssno.isEmpty()) return@post call.respondBadRequestMessage(JIAOCAI1_SSNO_MESSAGE)
                if (body.lastReadIndex < 0 || body.totalPages < 0) {
                    return@post call.respondBadRequestMessage("lastReadIndex 与 totalPages 都不能是负数")
                }
                val runtime = ServeJiaocai1Runtime.of(session)
                runCatching {
                    // 这一条**只动已有的行**（与 `:core` 的 `updateProgress` 同义）：不在书架里就是空操作
                    runtime.shelf.updateProgress(
                        ssno = ssno,
                        index = body.lastReadIndex,
                        at = body.at.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        totalPages = body.totalPages,
                    )
                    runtime.shelf.snapshot()
                }
                    .onSuccess { call.respond(ApiEnvelope.ok(Jiaocai1ShelfData(it))) }
                    .onFailure { call.respondUpstreamFailure(it, "写阅读进度") }
            }

            delete("{ssno}") {
                if (!session.authenticated) return@delete call.respondLoginRequired()
                val ssno = call.parameters["ssno"]?.trim().orEmpty()
                if (ssno.isEmpty()) return@delete call.respondBadRequestMessage(JIAOCAI1_SSNO_MESSAGE)
                val runtime = ServeJiaocai1Runtime.of(session)
                runCatching {
                    runtime.shelf.delete(ssno)
                    // 移出书架 = 这一本书的本地页缓存也没用了（与 `:core` 的 `removeFromShelf` 同一步）
                    runtime.source.evictBook(ssno)
                    runtime.forget(ssno)
                    runtime.shelf.snapshot()
                }
                    .onSuccess { call.respond(ApiEnvelope.ok(Jiaocai1ShelfData(it))) }
                    .onFailure { call.respondUpstreamFailure(it, "移出书架") }
            }
        }
    }
}

// ── 装配上的两个动作（取字节、读整份书架）────────────────────────────

/**
 * 取一页的字节（[handle] 由调用方先拿好：页数越界那个 400 要靠它判）。
 *
 * **取不到就重开一次**：`jpgPath` 是会话令牌，上游轮换之后旧路径会一直给空体
 * （`:data` 的 `Jiaocai1PageLoader` 靠图片魔数判成败），重开一次就能拿回有效路径。
 */
private suspend fun ServeJiaocai1.pageBytes(handle: Jiaocai1BookHandle, index: Int): ByteArray? {
    val page = handle.pages.getOrNull(index) ?: return null
    source.pageBytes(handle, page)?.let { return it }
    val reopened = this.handle(handle.ssno, refresh = true) ?: return null
    val retryPage = reopened.pages.getOrNull(index) ?: return null
    return source.pageBytes(reopened, retryPage)
}

/** 整份书架（按最近阅读倒序 —— `:core` 的 `observeAll` 就是这一条序）。 */
private suspend fun Jiaocai1ShelfStore.snapshot(): List<Jiaocai1ShelfRecord> =
    observeAll().first().sortedByDescending { it.lastReadAt }
/** 取不到字节：502 + 中文短句（这一条的**失败**仍是信封，见文件头）。 */
private suspend fun ApplicationCall.respondImageUnavailable(what: String) = respond(
    HttpStatusCode.BadGateway,
    ApiEnvelope<Unit>(
        ApiErrors.UPSTREAM_FAILED,
        message = "$what 取不到：全文库那边可能换了会话（重新打开这本书再试一次）",
    ),
)

/** `?url=` 只认全文库自己的地址（见文件头「如实边界」）。 */
internal fun isJiaocai1Url(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val host = trimmed.substringAfter("://").substringBefore('/').substringBefore('?')
        .substringAfter('@') // 挡掉 user@host 那种写法
        .substringBefore(':')
        .lowercase()
    return host == JIAOCAI1_HOST
}

/** 全文库的域名（`:data` 的 `Jiaocai1Api.BASE` 上那一个）。 */
internal const val JIAOCAI1_HOST = "jiaocai1.lib.xjtu.edu.cn"

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/jiaocai1/classify` 的 `data`（节点就是 `:core` 的 `Jiaocai1Category`，零转换）。 */
@Serializable
internal data class Jiaocai1ClassifyData(val count: Int, val nodes: List<Jiaocai1Category>) {
    constructor(nodes: List<Jiaocai1Category>) : this(nodes.size, nodes)
}

/** `GET /api/jiaocai1/search` 的 `data`。 */
@Serializable
internal data class Jiaocai1SearchData(
    /** 请求里那个检索词，原样回（空串 = 按分类浏览）。 */
    val keyword: String,
    /** 请求里那个检索字段的 key（省略时是 `bookName`）。 */
    val field: String,
    /** 请求里那个分类号，原样回（空串 = 按关键词检索）。 */
    val cls: String,
    /** 请求里要的那一页。 */
    val page: Int,
    val totalRows: Int,
    val currentPage: Int,
    val totalPages: Int,
    val count: Int,
    val books: List<Jiaocai1BookDto>,
)

/** 一本检索命中的书（`:core` 的 `Jiaocai1Book` 那八格）。 */
@Serializable
internal data class Jiaocai1BookDto(
    val ssno: String,
    val title: String,
    val author: String,
    val publishDate: String,
    val themeWord: String,
    val callNo: String,
    val classifyPath: String,
    /** 全文库上的封面地址；要字节就打 `GET /api/jiaocai1/cover?url=`（本层只认这一个域名）。 */
    val coverUrl: String,
)

/** `GET /api/jiaocai1/book` 的 `data`。[book] 为 `null` = 这本书打不开（没有开放全文 / 会话过期）。 */
@Serializable
internal data class Jiaocai1BookData(val ssno: String, val book: Jiaocai1BookHandleDto?)

/**
 * 阅读句柄的**投影**：只给页表，不给 `jpgPath`。
 *
 * [pageRanges] 是 8 种页型各自的 `[起, 止]`（`起 > 止` = 这本书没有这类页），
 * 顺序与 `:core` 的 `Jiaocai1Paging.TYPE_NAMES` 逐位对应；[pageCount] 是把它们摊平之后的总页数
 * （客户端据此夹住 [JIAOCAI1_PARAM_INDEX]）。
 */
@Serializable
internal data class Jiaocai1BookHandleDto(
    val title: String,
    val pageRanges: List<List<Int>>,
    val bookPages: Int,
    val pageCount: Int,
)

/** 书架那一族每个端点都回整份（与 `/api/accounts` 的写端点回快照同一条做法）。 */
@Serializable
internal data class Jiaocai1ShelfData(val count: Int, val rows: List<Jiaocai1ShelfRowDto>) {
    constructor(rows: List<Jiaocai1ShelfRecord>) : this(rows.size, rows.map { it.toDto() })
}

/**
 * 书架里的一行。
 *
 * ⚠️ **两个时间是 epoch 毫秒，不是 ISO-8601**（§4 的通则在这一点上让位，与 `/api/agent/sessions`
 * 的 `createdAt` 同一条理由）：这两格是 `:core` 模型 `Jiaocai1ShelfRecord` 的 `Long` 字段，
 * 浏览器要把它们原样放回**同一个模型**（屏上自己算「3 天前」）。逐条记在契约 §5.14。
 */
@Serializable
internal data class Jiaocai1ShelfRowDto(
    val ssno: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val totalPages: Int,
    val lastReadIndex: Int,
    val lastReadAt: Long,
    val addedAt: Long,
)

/** `POST /api/jiaocai1/shelf` 的请求体（整行；两格时间省略 = 此刻）。 */
@Serializable
internal data class Jiaocai1ShelfUpsertBody(
    val ssno: String = "",
    val title: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val totalPages: Int = 0,
    val lastReadIndex: Int = 0,
    val lastReadAt: Long = 0L,
    val addedAt: Long = 0L,
)

/** `POST /api/jiaocai1/shelf/progress` 的请求体（只动进度三列）。 */
@Serializable
internal data class Jiaocai1ShelfProgressBody(
    val ssno: String = "",
    val lastReadIndex: Int = 0,
    val totalPages: Int = 0,
    val at: Long = 0L,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun Jiaocai1Book.toDto() = Jiaocai1BookDto(
    ssno = ssno,
    title = title,
    author = author,
    publishDate = publishDate,
    themeWord = themeWord,
    callNo = callNo,
    classifyPath = classifyPath,
    coverUrl = coverUrl,
)

private fun Jiaocai1BookHandle.toDto() = Jiaocai1BookHandleDto(
    title = title,
    pageRanges = pageRanges.map { listOf(it.first, it.last) },
    bookPages = bookPages,
    pageCount = pages.size,
)

private fun Jiaocai1ShelfRecord.toDto() = Jiaocai1ShelfRowDto(
    ssno = ssno,
    title = title,
    author = author,
    coverUrl = coverUrl,
    totalPages = totalPages,
    lastReadIndex = lastReadIndex,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
)

// ── 参数名与文案 ─────────────────────────────────────────────────────

internal const val JIAOCAI1_PARAM_KEYWORD = "keyword"
internal const val JIAOCAI1_PARAM_FIELD = "field"
internal const val JIAOCAI1_PARAM_CLS = "cls"
internal const val JIAOCAI1_PARAM_PAGE = "page"
internal const val JIAOCAI1_PARAM_SSNO = "ssno"
internal const val JIAOCAI1_PARAM_INDEX = "index"
internal const val JIAOCAI1_PARAM_URL = "url"

private const val JIAOCAI1_QUERY_MESSAGE =
    "keyword 与 cls 至少给一个（keyword = 按词检索，cls = 按中图法分类浏览，两个都给就是在这个分类里搜这个词）"

private const val JIAOCAI1_FIELD_MESSAGE =
    "field 只认 bookName / bookAuthor / themeWord / bookCallno / bookISBN 五个（省略 = bookName）"

private const val JIAOCAI1_SSNO_MESSAGE = "ssno 必填（全文库书目号，取自 /api/jiaocai1/search 的那一行）"

private const val JIAOCAI1_COVER_MESSAGE = "url 必填（封面地址，取自 /api/jiaocai1/search 的 coverUrl）"

private const val JIAOCAI1_COVER_HOST_MESSAGE =
    "url 只认全文库自己的地址（$JIAOCAI1_HOST）：这一条不是通用取图代理"

private const val JIAOCAI1_SHELF_BODY_MESSAGE =
    "请求体需要 JSON：{\"ssno\":\"…\",\"title\":\"…\",\"author\":\"…\",\"coverUrl\":\"…\"," +
        "\"totalPages\":0,\"lastReadIndex\":0}（可选 \"lastReadAt\" / \"addedAt\"，省略 = 此刻）"

private const val JIAOCAI1_SHELF_PROGRESS_BODY_MESSAGE =
    "请求体需要 JSON：{\"ssno\":\"…\",\"lastReadIndex\":0,\"totalPages\":0}（可选 \"at\"，省略 = 此刻）"
