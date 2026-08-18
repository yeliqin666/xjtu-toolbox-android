package com.xjtu.toolbox.jiaocai1

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.AppDatabase
import com.xjtu.toolbox.util.DataCache
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Jiaocai1ViewModel : ViewModel() {
    var tab by mutableIntStateOf(0)
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
        tab = 1
        search(1)
    }

    fun clearCls() {
        cls = ""
        clsName = ""
        search(1)
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
    val gson = Gson()
    cache.get(CATEGORY_CACHE_KEY, CATEGORY_TTL_MS)?.let { json ->
        runCatching {
            gson.fromJson(json, Array<Jiaocai1Category>::class.java).toList()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
    }
    val fresh = Jiaocai1Api(site).classifyTree()
    if (fresh.isNotEmpty()) cache.put(CATEGORY_CACHE_KEY, gson.toJson(fresh))
    fresh
}

private const val CATEGORY_CACHE_KEY = "jiaocai1_classify_v2"
private const val CATEGORY_TTL_MS = 30L * 24 * 60 * 60 * 1000L
