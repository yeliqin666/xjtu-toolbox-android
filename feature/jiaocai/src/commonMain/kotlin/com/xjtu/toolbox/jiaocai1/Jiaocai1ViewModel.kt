package com.xjtu.toolbox.jiaocai1

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.error.FriendlyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 教材全文库的浏览态：检索 / 分类树 / 书架列表。 */
class Jiaocai1ViewModel(
    private val source: Jiaocai1Source,
    private val shelfStore: Jiaocai1ShelfStore,
    /** 分类树的落盘缓存；没有就传 null（每次都拉一遍）。 */
    private val cache: Jiaocai1Cache? = null,
) : ViewModel() {

    var keyword by mutableStateOf("")
    var field by mutableStateOf(Jiaocai1SearchField.BOOK_NAME)
    var result by mutableStateOf<Jiaocai1SearchResult?>(null)
    var books by mutableStateOf<List<Jiaocai1Book>>(emptyList())
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var moreFailed by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var cls by mutableStateOf("")
    var clsName by mutableStateOf("")

    var categoryRoots by mutableStateOf<List<Jiaocai1Category>>(emptyList())
    var categoryPath by mutableStateOf<List<Jiaocai1Category>>(emptyList())
    var categoryLoading by mutableStateOf(false)
    var categoryError by mutableStateOf<String?>(null)
    var authExpired by mutableStateOf(false)

    private val _shelf = MutableStateFlow<List<Jiaocai1ShelfRecord>>(emptyList())
    val shelf: StateFlow<List<Jiaocai1ShelfRecord>> = _shelf

    /**
     * 书架取数失败的一句话（见 [Jiaocai1ShelfStore.notices]）；读得回来时为 `null`。
     *
     * 书架栏据此画 `ErrorState` —— 空书架与「没读到」在屏上必须是两件事。
     */
    var shelfError by mutableStateOf<String?>(null)
    private var searchJob: Job? = null
    private var bound = false

    /** 订阅书架（`observeAll()` 是热流，屏幕上一直挂着）。 */
    fun bind() {
        if (bound) return
        bound = true
        viewModelScope.launch {
            shelfStore.observeAll().collect { _shelf.value = it }
        }
        // 失败通道（默认空流：Android / 桌面那一份从不发）
        viewModelScope.launch {
            shelfStore.notices.collect { shelfError = it.ifBlank { null } }
        }
    }

    fun search(page: Int) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (page == 1) {
                loading = true
                error = null
            } else {
                loadingMore = true
            }
            moreFailed = false
            try {
                val r = source.search(
                    keyword = keyword.trim(),
                    field = field,
                    cls = cls,
                    page = page,
                )
                if (page > 1 && r.books.isEmpty()) moreFailed = true
                result = r
                books = if (page == 1) r.books else books + r.books
            } catch (e: Exception) {
                // `:core` 抓不到 `:app`/`:data` 的 `AuthExpiredException` ⇒ 按 `:core` 的标记接口
                // 会话失效交给宿主重新认证。
                if (e is SessionExpiredFailure) authExpired = true
                else if (page == 1) error = FriendlyError.of(e, "检索") else moreFailed = true
            } finally {
                loading = false
                loadingMore = false
            }
        }
    }

    fun loadCategories() {
        if (categoryRoots.isNotEmpty() || categoryLoading) return
        viewModelScope.launch {
            categoryLoading = true
            categoryError = null
            try {
                val cached = cache?.readCategories(CATEGORY_TTL_MS)?.takeIf { it.isNotEmpty() }
                if (cached != null) {
                    categoryRoots = cached
                } else {
                    val fresh = source.classifyTree()
                    if (fresh.isNotEmpty()) cache?.writeCategories(fresh)
                    categoryRoots = fresh
                }
                if (categoryRoots.isEmpty()) categoryError = "分类目录暂时取不到"
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) authExpired = true
                else categoryError = FriendlyError.of(e, "加载分类")
            } finally {
                categoryLoading = false
            }
        }
    }

    fun reloadCategories() {
        categoryRoots = emptyList()
        loadCategories()
    }

    fun pickCategory(node: Jiaocai1Category) {
        cls = node.id
        clsName = node.name
        keyword = ""
        search(1)
    }

    /** 取消分类限定。 */
    fun clearCls() {
        cls = ""
        clsName = ""
        if (keyword.isNotBlank()) {
            search(1)
        } else {
            result = null
            books = emptyList()
        }
    }

    fun changeField(next: Jiaocai1SearchField) {
        field = next
        if (books.isNotEmpty() || keyword.isNotBlank() || cls.isNotBlank()) search(1)
    }

    /** 移出书架：删行 + 丢掉这本书的本地页缓存。 */
    suspend fun removeFromShelf(ssno: String) {
        shelfStore.delete(ssno)
        source.evictBook(ssno)
    }

    /** 书架栏的「重试」：把那一句话清掉，再让存储那一端去取一份（见 [Jiaocai1ShelfStore.refresh]）。 */
    fun reloadShelf() {
        shelfError = null
        viewModelScope.launch { shelfStore.refresh() }
    }
}

/** 分类树的落盘缓存端口。 */
interface Jiaocai1Cache {

    /** 命中且未超过 [maxAgeMs] 就给分类树；否则 null。 */
    fun readCategories(maxAgeMs: Long): List<Jiaocai1Category>?

    /** 写回分类树（实现方自己决定落哪儿、怎么序列化）。 */
    fun writeCategories(items: List<Jiaocai1Category>)
}

private const val CATEGORY_TTL_MS = 30L * 24 * 60 * 60 * 1000L
