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

/**
 * 教材全文库的浏览态：检索 / 分类树 / 书架列表。
 *
 * ## 搬进 `:core` 时改了什么（编排一行未改）
 *
 * 搬迁前它收 `Context` + `SiteSession`，自己做三件事：`Jiaocai1Api(site)` 取数、
 * `DataCache(context)` 缓存分类树、`AppDatabase` 读写书架。现在这三样都从外面注入：
 *  - 取数 → [Jiaocai1Source] 端口（实现方包 IO 调度器，所以这里不再有 `withContext(Dispatchers.IO)`）；
 *  - 分类树缓存 → [Jiaocai1Cache] 端口（`null` = 本端不缓存，屏上的语义不变，只是每次进程都要拉一遍）；
 *  - 书架 → [Jiaocai1ShelfStore] 端口。
 *
 * 错误文案、`FriendlyError.of(e, …)` 的那几个词、`AuthExpiredException` 的处理时机、
 * 分页的 `moreFailed` 判据、`clearCls()` 的两种分支，全部逐字沿用。
 */
class Jiaocai1ViewModel(
    private val source: Jiaocai1Source,
    private val shelfStore: Jiaocai1ShelfStore,
    /** 分类树的落盘缓存；本端没有就传 null（桌面/Web）—— 屏上的行为不变，只是每次都拉一遍。 */
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

    private var searchJob: Job? = null
    private var bound = false

    /**
     * 订阅书架（`observeAll()` 是热流，屏幕上一直挂着）。搬迁前这一步同时发生：
     * `AppDatabase.getInstance(context).jiaocai1ShelfDao().observeAll().collect { … }`。
     */
    fun bind() {
        if (bound) return
        bound = true
        viewModelScope.launch {
            shelfStore.observeAll().collect { _shelf.value = it }
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
                // [SessionExpiredFailure] 认（它实现了那个接口），与 coupon / dormpower 同一条写法。
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
                // 缓存口径与搬迁前 `loadCategoryTree` 逐字同构：命中（且非空）就用，
                // 拉到新的且非空才写回；30 天的 TTL 由实现方按 [Jiaocai1Cache.readCategories] 判。
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

    /**
     * 取消分类限定。有关键词就照原关键词重新检索；关键词也是空的就直接退回分类树
     * （跟「关键词为空、没有分类限定就显示分类树」用的是同一个判据），不必带着
     * 一对空参数再打一次检索。
     */
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

    /** 移出书架：删行 + 把这本书的本地页缓存一起丢掉（搬迁前那两句，同一顺序）。 */
    suspend fun removeFromShelf(ssno: String) {
        shelfStore.delete(ssno)
        source.evictBook(ssno)
    }
}

/**
 * 分类树的**落盘缓存端口**。
 *
 * 搬迁前这一块是 `:app` 的 `DataCache(context)`（`cacheDir/data_cache<suffix>/jiaocai1_classify_v2.json`，
 * 账号隔离、可被「清除缓存」清掉，TTL 30 天）。`:core` 里既没有 `Context` 也不能落盘 ⇒ 切成端口：
 *  - Android（`AppJiaocai1Cache`）= 原样包住 `DataCache`（同一个键、同一个 TTL、同一个目录）；
 *  - 桌面/Web = 不传（`null`）⇒ 分类树每个进程拉一次，屏上的文案与流程一字未改
 *    （这与 `EmptyRoomStore` 在 Web 上的那条缺口是同一种如实降级）。
 */
interface Jiaocai1Cache {

    /** 命中且未超过 [maxAgeMs] 就给分类树；否则 null。 */
    fun readCategories(maxAgeMs: Long): List<Jiaocai1Category>?

    /** 写回分类树（实现方自己决定落哪儿、怎么序列化）。 */
    fun writeCategories(items: List<Jiaocai1Category>)
}

private const val CATEGORY_TTL_MS = 30L * 24 * 60 * 60 * 1000L
