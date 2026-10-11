package com.xjtu.toolbox.jiaocai1

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.error.FriendlyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * 阅读器的状态：打开一本书、翻页、进度记账、预取窗口。
 *
 * ## 搬进 `:core` 时改了什么（四处，编排一行未改）
 *
 * 1. `Jiaocai1Api(site).openBook(...)` → [Jiaocai1Source.openBook]（实现方包 IO 调度器）；
 * 2. `loader: Jiaocai1PageLoader`（`Context` + `BitmapFactory`）→ [Jiaocai1PageImages]
 *    （解码走 `:core` 的图片缝，见那个类的 KDoc）；
 * 3. `AppDatabase…jiaocai1ShelfDao()` → [Jiaocai1ShelfStore]（方法逐个对应）；
 * 4. `onCleared()` 里那一发「页面作用域已取消之后仍要打完的进度」→ [Jiaocai1ShelfStore.saveProgressOnLeave]
 *    （实现方自带页面之外的作用域 —— 与 `LibrarySource.restoreCampus` 同一条规矩）。
 *
 * 另外 `System.currentTimeMillis()` → `Clock.System`（同一时刻、同一 epoch 毫秒，
 * `:core` 的既有写法）；400ms 的进度防抖、`(existing ?: 新行).copy(...)` 那套字段口径、
 * 令牌过期时 `evictBook` + 不恢复进度的分支，全部逐字沿用。
 */
class Jiaocai1ReaderViewModel(
    private val source: Jiaocai1Source,
    private val shelfStore: Jiaocai1ShelfStore,
) : ViewModel() {

    var ssno: String = ""
        private set
    var fallbackTitle: String = ""
        private set

    var handle by mutableStateOf<Jiaocai1BookHandle?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    var authExpired by mutableStateOf(false)

    /** 页图（字节来自 [source]，解码与内存 LRU 在这一层）。屏用它取页面与封面。 */
    val images: Jiaocai1PageImages by lazy { Jiaocai1PageImages(source) }

    private var bound = false
    private var progressJob: Job? = null

    fun bind(ssno: String, title: String) {
        this.ssno = ssno
        this.fallbackTitle = title
        if (bound) return
        bound = true
        open(restoreShelf = true)
    }

    fun retry() = open(restoreShelf = true)

    /** 页面取不到（多半是阅读令牌过期）：丢掉这本书的本地缓存，重新开一次，且不恢复进度。 */
    fun onTokenExpired() {
        viewModelScope.launch { source.evictBook(ssno) }
        images.clear()
        open(restoreShelf = false)
    }

    fun setPage(index: Int) {
        val pages = handle?.pages ?: return
        val next = index.coerceIn(0, pages.lastIndex)
        if (next == pageIndex) return
        pageIndex = next
        handle?.let { images.setPrefetchWindow(it, next) }
        scheduleProgress()
    }

    private fun open(restoreShelf: Boolean) {
        val id = ssno
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val h = source.openBook(id)
                if (h == null || h.pages.isEmpty()) {
                    error = "这本书暂时打不开，可能没有开放全文，或阅读令牌已过期。"
                } else {
                    if (restoreShelf) restoreShelfRow(h) else keepPage(h)
                    handle = h
                    images.setPrefetchWindow(h, pageIndex)
                }
            } catch (e: Exception) {
                // 与 [Jiaocai1ViewModel] 同一条写法：`:core` 只能按标记接口认会话失效。
                if (e is SessionExpiredFailure) authExpired = true
                else error = FriendlyError.of(e, "打开")
            } finally {
                loading = false
            }
        }
    }

    /**
     * 打开一本书时对齐书架那一行：已有行就沿用它的书名（新拿到的标题为空时），
     * 没有就新建一行；两种情况的进度都从**存的**那一页接着读。
     */
    private suspend fun restoreShelfRow(h: Jiaocai1BookHandle) {
        val existing = shelfStore.get(h.ssno)
        val now = Clock.System.now().toEpochMilliseconds()
        val index = existing?.lastReadIndex?.coerceIn(0, h.pages.lastIndex) ?: 0
        pageIndex = index
        shelfStore.upsert(
            (existing ?: Jiaocai1ShelfRecord(
                ssno = h.ssno,
                title = h.title.ifBlank { fallbackTitle },
                author = "",
                coverUrl = "",
                totalPages = h.pages.size,
                lastReadIndex = index,
                lastReadAt = now,
                addedAt = now,
            )).copy(
                title = h.title.ifBlank { existing?.title ?: fallbackTitle },
                totalPages = h.pages.size,
                lastReadIndex = index,
                lastReadAt = now,
            )
        )
    }

    private fun keepPage(h: Jiaocai1BookHandle) {
        pageIndex = pageIndex.coerceIn(0, h.pages.lastIndex)
    }

    private fun scheduleProgress() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            writeProgress()
        }
    }

    private suspend fun writeProgress() {
        val h = handle ?: return
        shelfStore.updateProgress(
            h.ssno,
            pageIndex,
            Clock.System.now().toEpochMilliseconds(),
            h.pages.size,
        )
    }

    override fun onCleared() {
        progressJob?.cancel()
        val h = handle
        val index = pageIndex
        // viewModelScope 此时已取消，最后一次进度交给实现方在页面之外完成（不阻塞主线程）
        if (h != null) {
            shelfStore.saveProgressOnLeave(
                ssno = h.ssno,
                index = index,
                at = Clock.System.now().toEpochMilliseconds(),
                totalPages = h.pages.size,
            )
        }
        source.closePages()
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_DEBOUNCE_MS = 400L
    }
}
