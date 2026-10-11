package com.xjtu.toolbox.jiaocai1

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * 教材阅读器**编排**（[Jiaocai1ReaderViewModel]）在 JVM 上的口径。
 *
 * ## 它钉的是什么
 *
 * 阅读器搬进 `:core` 时，取数从 `Jiaocai1Api(site)` 换成了 [Jiaocai1Source] 端口、书架从
 * `AppDatabase…jiaocai1ShelfDao()` 换成了 [Jiaocai1ShelfStore] 端口、`onCleared` 里那一发
 * 「页面作用域已取消之后仍要打完的进度」换成了 [Jiaocai1ShelfStore.saveProgressOnLeave]
 * —— **编排一行未改**。这里用两个记账用的假实现把那几处契约钉住：
 *
 * - 打开一本书：新建/对齐书架那一行的**字段口径**（标题 `ifBlank` 链、`totalPages` 取线性页表长度）；
 * - 已有书架行：**从存的页码接着读**，越界夹回页表范围；
 * - 翻页：预取窗口跟着当前页走；进度**防抖 400ms**、连翻只写最后一页；
 * - 令牌过期：`evictBook` + 重开，且**不再读书架**（不把进度拉回旧值）；
 * - 打不开 / 会话失效：分别落在 `error` 与 `authExpired` 两条流上。
 *
 * ## 为什么在 jvmTest
 *
 * `viewModelScope` 要一个 Main 调度器，`Dispatchers.setMain` 只在 JVM 侧稳定（与
 * `TranscriptViewModelTest` / `AttendanceViewModelTest` 同一条理由）。防抖那两条要能推进虚拟时间，
 * 所以这里用与 `runTest` 同一个 `testScheduler` 的 [StandardTestDispatcher]。
 *
 * 全仓库只有这一处要推虚拟时间（`advanceUntilIdle` 仍是实验 API）⇒ 就地开 opt-in，不往
 * `build.gradle.kts` 里塞一条全局开关。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Jiaocai1ReaderViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `打开一本书：服务端标题与线性页表落进新建的书架行`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        val handle = assertNotNull(vm.handle)
        assertEquals(8, handle.pages.size, "8 组页型展开成 8 页（见 Jiaocai1PagingTest 的同一组区间）")
        assertTrue(!vm.loading, "打开完应当退出 loading")
        assertNull(vm.error)
        assertEquals(0, vm.pageIndex)
        assertEquals(listOf(SSNO), source.opened)

        val row = assertNotNull(shelf.rows[SSNO])
        assertEquals("示例教材（假）", row.title, "有服务端标题就用服务端标题")
        assertEquals(8, row.totalPages)
        assertEquals(0, row.lastReadIndex)
        assertEquals(listOf(SSNO), shelf.upserts, "打开即把这一行写进书架")
    }

    @Test
    fun `打开一本书：服务端标题为空时退到 fallbackTitle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source().apply { title = "" }
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "从路由带进来的标题")
        advanceUntilIdle()

        assertEquals("从路由带进来的标题", assertNotNull(shelf.rows[SSNO]).title)
    }

    @Test
    fun `已有书架行：从存的页码接着读，越界夹回页表范围`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore(
            Jiaocai1ShelfRecord(
                ssno = SSNO,
                title = "上次存的书名",
                author = "上次存的作者",
                coverUrl = "https://example.invalid/cover.jpg",
                totalPages = 99,
                lastReadIndex = 3,
                lastReadAt = 1L,
                addedAt = 1L,
            ),
        )
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        assertEquals(3, vm.pageIndex, "从存的第 3 页接着读")
        val row = assertNotNull(shelf.rows[SSNO])
        assertEquals(8, row.totalPages, "页数以这次打开的线性页表为准")
        assertEquals(3, row.lastReadIndex)
        assertEquals("上次存的作者", row.author, "只改标题/页数/进度三处，作者与封面不动")
    }

    @Test
    fun `已有书架行：存下来的页码越界时夹到最后一页`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore(
            Jiaocai1ShelfRecord(
                ssno = SSNO,
                title = "上次存的书名",
                author = "",
                coverUrl = "",
                totalPages = 99,
                lastReadIndex = 99,
                lastReadAt = 1L,
                addedAt = 1L,
            ),
        )
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        assertEquals(7, vm.pageIndex)
        assertEquals(7, assertNotNull(shelf.rows[SSNO]).lastReadIndex)
    }

    @Test
    fun `翻页：预取窗口跟着当前页走，进度防抖到点才写、连翻只写最后一页`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()
        assertEquals(listOf(SSNO to 0), source.prefetchWindows, "打开后按起始页起预取窗口")
        assertTrue(shelf.progressWrites.isEmpty(), "光是打开不写进度")

        vm.setPage(2)
        assertEquals(listOf(SSNO to 0, SSNO to 2), source.prefetchWindows)
        assertTrue(shelf.progressWrites.isEmpty(), "400ms 防抖未到点，先不写")

        vm.setPage(4)
        advanceUntilIdle()
        assertEquals(listOf(SSNO to 0, SSNO to 2, SSNO to 4), source.prefetchWindows)
        assertEquals(listOf(Triple(SSNO, 4, 8)), shelf.progressWrites, "连翻两次只写最后一页")
    }

    @Test
    fun `翻页：页码越界夹回页表范围，夹完没动就不写进度`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        vm.setPage(99)
        advanceUntilIdle()

        assertEquals(7, vm.pageIndex)
        assertEquals(listOf(Triple(SSNO, 7, 8)), shelf.progressWrites)
    }

    @Test
    fun `令牌过期：丢掉本地缓存并重开，但不把进度拉回书架里的旧值`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore(
            Jiaocai1ShelfRecord(
                ssno = SSNO,
                title = "上次存的书名",
                author = "",
                coverUrl = "",
                totalPages = 8,
                lastReadIndex = 2,
                lastReadAt = 1L,
                addedAt = 1L,
            ),
        )
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()
        assertEquals(2, vm.pageIndex)

        // 另一台设备把进度改回第一页；本端令牌过期重开时**不该**跟着回到书架里的值。
        shelf.rows[SSNO] = assertNotNull(shelf.rows[SSNO]).copy(lastReadIndex = 0)

        vm.onTokenExpired()
        advanceUntilIdle()

        assertEquals(listOf(SSNO, SSNO), source.opened, "重开一次")
        assertEquals(listOf(SSNO), source.evicted, "重开前把这本书的本地页缓存丢掉")
        assertEquals(2, vm.pageIndex, "停在原页，不读回书架的旧进度")
        assertEquals(listOf(SSNO), shelf.upserts, "只 upsert 过一次——重开不再写书架行")
        assertEquals(8, assertNotNull(vm.handle).pages.size)
    }

    @Test
    fun `打不开：没有开放全文 ⇒ 落在错误文案上，不写书架`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source().apply { openReturnsNull = true }
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        assertNull(vm.handle)
        assertTrue(!vm.loading)
        assertNotNull(vm.error)
        assertTrue(!vm.authExpired, "打不开不是会话失效")
        assertTrue(shelf.upserts.isEmpty())
        assertTrue(shelf.rows.isEmpty())
    }

    @Test
    fun `打不开：页表为空也算打不开`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source().apply { pageRanges = List(8) { 0..-1 } }
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        assertNull(vm.handle)
        assertNotNull(vm.error)
        assertTrue(shelf.upserts.isEmpty())
    }

    @Test
    fun `会话失效：走 authExpired 那条流，不写成错误文案`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source().apply { failOpenWith = FakeSessionExpired() }
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()

        assertTrue(vm.authExpired, "交给导航层重登")
        assertNull(vm.error)
        assertNull(vm.handle)
    }

    @Test
    fun `关掉阅读器：最后一页进度交给书架实现的页面外作用域打完`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeJiaocai1Source()
        val shelf = FakeJiaocai1ShelfStore()
        val vm = Jiaocai1ReaderViewModel(source, shelf)

        vm.bind(SSNO, "占位标题")
        advanceUntilIdle()
        vm.setPage(5)
        advanceUntilIdle()
        assertTrue(shelf.savedOnLeave.isEmpty(), "还开着的时候不落这一枪")

        closeViewModel(vm)

        assertEquals(listOf(Triple(SSNO, 5, 8)), shelf.savedOnLeave)
        assertTrue(source.closedPages, "离开阅读器要收掉预取作用域")
    }

    private companion object {
        const val SSNO = "SSNO-FAKE-1"
    }
}

/**
 * 关掉一个 VM：`onCleared()` 是 protected，测试走不进去 ⇒ 借最小的一间 [ViewModelStore] 让它
 * 自己那条清场路径（`clear()` → `onCleared()`）跑一遍，与真机上导航出栈时同一条路。
 */
private fun closeViewModel(vm: ViewModel) {
    val store = ViewModelStore()
    store.put("jiaocai1-reader", vm)
    store.clear()
}
/** 会话失效的标记异常：与 `:app` / `:data` 那边的实现同一形态（见 [SessionExpiredFailure]）。 */
private class FakeSessionExpired : RuntimeException("登录态已失效"), SessionExpiredFailure

/**
 * 记账用的假取数实现：把取数三发与页字节那一半的调用记下来，并允许注入「打不开」与失败。
 *
 * 页字节一律返回 null（本类不测解码 —— 解码在 [Jiaocai1PageImages]，走的是平台图片缝）。
 */
private class FakeJiaocai1Source : Jiaocai1Source {

    var title = "示例教材（假）"
    /** 换掉「上游给的那本书」；null 表示用 [pageRanges] 现造一本。 */
    var handleOverride: Jiaocai1BookHandle? = null

    /** true = 上游答「没有开放全文」（`openBook` 返 null）。 */
    var openReturnsNull = false
    var pageRanges: List<IntRange> = listOf(
        1..1, // 封面
        1..1, // 书名页
        0..-1,
        0..-1,
        1..2, // 目录
        1..3, // 正文
        0..-1,
        2..2, // 封底
    )
    var failOpenWith: Exception? = null

    val opened = mutableListOf<String>()
    val prefetchWindows = mutableListOf<Pair<String, Int>>()
    val evicted = mutableListOf<String>()
    var closedPages = false

    override suspend fun classifyTree(): List<Jiaocai1Category> = emptyList()

    override suspend fun search(
        keyword: String,
        field: Jiaocai1SearchField,
        cls: String,
        page: Int,
    ): Jiaocai1SearchResult = Jiaocai1SearchResult()

    override suspend fun openBook(ssno: String): Jiaocai1BookHandle? {
        opened += ssno
        failOpenWith?.let { throw it }
        if (openReturnsNull) return null
        return handleOverride ?: Jiaocai1BookHandle(
            ssno = ssno,
            title = title,
            jpgPath = "/pages/$ssno",
            pageRanges = pageRanges,
            bookPages = pageRanges.getOrElse(Jiaocai1Paging.TYPE_CONTENT) { 0..-1 }.let {
                if (it.first > it.last) 0 else it.last - it.first + 1
            },
        )
    }

    override suspend fun pageBytes(handle: Jiaocai1BookHandle, page: Jiaocai1Page): ByteArray? = null

    override suspend fun coverBytes(url: String): ByteArray? = null

    override fun prefetchWindow(handle: Jiaocai1BookHandle, center: Int) {
        prefetchWindows += handle.ssno to center
    }

    override suspend fun evictBook(ssno: String) {
        evicted += ssno
    }

    override fun closePages() {
        closedPages = true
    }
}

/** 记账用的假书架（桌面上就是这份存储的落盘版；Android 那份包住 Room DAO）。 */
private class FakeJiaocai1ShelfStore(initial: Jiaocai1ShelfRecord? = null) : Jiaocai1ShelfStore {

    val rows = linkedMapOf<String, Jiaocai1ShelfRecord>()
    val upserts = mutableListOf<String>()

    /** (ssno, index, totalPages) */
    val progressWrites = mutableListOf<Triple<String, Int, Int>>()

    /** (ssno, index, totalPages) */
    val savedOnLeave = mutableListOf<Triple<String, Int, Int>>()

    init {
        initial?.let { rows[it.ssno] = it }
    }

    override fun observeAll(): Flow<List<Jiaocai1ShelfRecord>> =
        MutableStateFlow(rows.values.sortedByDescending { it.lastReadAt })

    override suspend fun get(ssno: String): Jiaocai1ShelfRecord? = rows[ssno]

    override suspend fun upsert(row: Jiaocai1ShelfRecord) {
        rows[row.ssno] = row
        upserts += row.ssno
    }

    override suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int) {
        progressWrites += Triple(ssno, index, totalPages)
        rows[ssno]?.let { rows[ssno] = it.copy(lastReadIndex = index, lastReadAt = at, totalPages = totalPages) }
    }

    override suspend fun delete(ssno: String) {
        rows.remove(ssno)
    }

    override fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int) {
        savedOnLeave += Triple(ssno, index, totalPages)
    }
}
