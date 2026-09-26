package com.xjtu.toolbox.jiaocai1

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Jiaocai1ViewModel : ViewModel() {
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

    private val _shelf = MutableStateFlow<List<Jiaocai1ShelfEntity>>(emptyList())
    val shelf: StateFlow<List<Jiaocai1ShelfEntity>> = _shelf

    private var site: SiteSession? = null
    private var appContext: Context? = null
    private var searchJob: Job? = null
    private var bound = false

    fun bind(context: Context, site: SiteSession) {
        if (bound) return
        bound = true
        this.site = site
        this.appContext = context.applicationContext
        viewModelScope.launch {
            AppDatabase.getInstance(context).jiaocai1ShelfDao().observeAll().collect { _shelf.value = it }
        }
    }

    fun search(page: Int) {
        val site = site ?: return
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
                val r = withContext(Dispatchers.IO) {
                    Jiaocai1Api(site).search(
                        keyword = keyword.trim(),
                        field = field,
                        cls = cls,
                        page = page,
                    )
                }
                if (page > 1 && r.books.isEmpty()) moreFailed = true
                result = r
                books = if (page == 1) r.books else books + r.books
            } catch (e: AuthExpiredException) {
                authExpired = true
            } catch (e: Exception) {
                if (page == 1) error = "检索失败：${e.message}" else moreFailed = true
            } finally {
                loading = false
                loadingMore = false
            }
        }
    }

    fun loadCategories() {
        val site = site ?: return
        val context = appContext ?: return
        if (categoryRoots.isNotEmpty() || categoryLoading) return
        viewModelScope.launch {
            categoryLoading = true
            categoryError = null
            try {
                categoryRoots = withContext(Dispatchers.IO) { loadCategoryTree(context, site) }
                if (categoryRoots.isEmpty()) categoryError = "分类目录暂时取不到"
            } catch (e: AuthExpiredException) {
                authExpired = true
            } catch (e: Exception) {
                categoryError = "加载失败：${e.message}"
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

    suspend fun removeFromShelf(ssno: String) {
        val context = appContext ?: return
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).jiaocai1ShelfDao()
            dao.delete(ssno)
            Jiaocai1PageLoader.evictBookStatic(context, ssno)
        }
    }
}

internal suspend fun loadCategoryTree(
    context: Context,
    site: SiteSession,
): List<Jiaocai1Category> = withContext(Dispatchers.IO) {
    val cache = DataCache(context)
    cache.read<List<Jiaocai1Category>>(CATEGORY_CACHE_KEY, CATEGORY_TTL_MS)
        ?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
    val fresh = Jiaocai1Api(site).classifyTree()
    if (fresh.isNotEmpty()) cache.write(CATEGORY_CACHE_KEY, fresh)
    fresh
}

private const val CATEGORY_CACHE_KEY = "jiaocai1_classify_v2"
private const val CATEGORY_TTL_MS = 30L * 24 * 60 * 60 * 1000L
