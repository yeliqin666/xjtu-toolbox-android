package com.xjtu.toolbox.web

import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfStore
import com.xjtu.toolbox.jiaocai1.ServeJiaocai1Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 教材全文库的**书架**在浏览器这一端的宿主（契约 §5.14）—— 数据落在 **serve 侧、按账号**
 * （店名 `serve_jiaocai1_shelf<账号后缀>`）：口径照会议 #9 的先例「serve 装真的那一层、浏览器只当界面」，
 * 与桌面的 `DesktopJiaocai1ShelfStore` 同性质 —— **跟着账号、换浏览器还在**。
 *
 * 三处如实差异：
 *
 * 1. **取数与写都是一发 HTTP**（`observeAll()` 是冷流：收一次就先拉一份）；
 * 2. **写端点回整份书架**（契约 §5.14）⇒ 写完直接拿那一份覆盖本地这份，不必再读一次；
 * 3. **失败要说出来**：serve 那一侧会话过期 / 网络故障时，书架栏会显示「取不到」而不是
 *    「书架还是空的」（那是编事实）—— 走 [notices] 那条通道，屏上画 `ErrorState` + 重试。
 *
 * ⚠️ 档案导出读不到它（浏览器那一侧导出是同步的）：`files/jiaocai/jiaocai1_shelf.json` 这一族
 * 在 web 的档案里**仍不落地**，见契约 §5.14 与 `WebArchiveRows` 那句如实说明。
 */
internal class WebJiaocai1ShelfStore(
    private val api: ServeJiaocai1Api,
) : Jiaocai1ShelfStore {

    /** 页面之外的作用域：`saveProgressOnLeave` 那一枪要它（浏览器页面本身就是这个寿命）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rows = MutableStateFlow<List<Jiaocai1ShelfRecord>>(emptyList())

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override val notices: Flow<String> = _notices.asSharedFlow()

    private val mutex = Mutex()

    /**
     * 收一次拉一份（第一次就把书架从 serve 那边取回来），之后只跟着这一份走。
     *
     * 为什么是冷流：屏上那两处（书架栏、阅读器打开书时的 `get`）都要「先有一次真取数」，
     * 而本机那一份（Room / JVM 文件）的 `observeAll()` 是热的、本来就有数据。
     */
    override fun observeAll(): Flow<List<Jiaocai1ShelfRecord>> = flow {
        load()
        emitAll(rows)
    }

    override suspend fun get(ssno: String): Jiaocai1ShelfRecord? {
        if (rows.value.none { it.ssno == ssno }) load()
        return rows.value.firstOrNull { it.ssno == ssno }
    }

    override suspend fun upsert(row: Jiaocai1ShelfRecord) {
        write("写书架") { api.upsert(row) }
    }

    override suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int) {
        write("写阅读进度") { api.updateProgress(ssno, index, at, totalPages) }
    }

    override suspend fun delete(ssno: String) {
        write("移出书架") { api.delete(ssno) }
    }

    override suspend fun refresh() = load()

    override fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int) {
        // 不阻塞那个正在走人的协程作用域（`onCleared` 那一刻 `viewModelScope` 已经取消）—— 与桌面那一份同一条
        scope.launch { runCatching { updateProgress(ssno, index, at, totalPages) } }
    }

    /** 读一份；成功/失败都在 [notices] 上说一句（成功发空串，把上一次那句清掉）。 */
    private suspend fun load() {
        mutex.withLock {
            runCatching { api.shelf() }
                .onSuccess { accept(it) }
                .onFailure { _notices.tryEmit(FriendlyError.of(it, "读书架")) }
        }
    }

    /** 写一发；回的是整份书架（契约 §5.14：这一族每个端点都回整份）。 */
    private suspend fun write(what: String, block: suspend () -> List<Jiaocai1ShelfRecord>) {
        runCatching { block() }
            .onSuccess { accept(it) }
            .onFailure { _notices.tryEmit(FriendlyError.of(it, what)) }
    }

    /** 收下一份：置成当前值 + 把上一次那句「取不到」清掉。顺序按最近阅读倒序（[observeAll] 的约定）。 */
    private fun accept(fresh: List<Jiaocai1ShelfRecord>) {
        rows.value = fresh.sortedByDescending { it.lastReadAt }
        _notices.tryEmit("")
    }
}
