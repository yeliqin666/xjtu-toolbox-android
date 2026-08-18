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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class Jiaocai1ReaderViewModel : ViewModel() {
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

    private var site: SiteSession? = null
    private var appContext: Context? = null
    var loader: Jiaocai1PageLoader? = null
        private set

    private var bound = false
    private var progressJob: Job? = null

    fun bind(context: Context, site: SiteSession, ssno: String, title: String) {
        this.ssno = ssno
        this.fallbackTitle = title
        this.site = site
        this.appContext = context.applicationContext
        if (loader == null) loader = Jiaocai1PageLoader(context, site)
        if (bound) return
        bound = true
        open(restoreFromRoom = true)
    }

    fun retry() = open(restoreFromRoom = true)

    fun onTokenExpired() {
        loader?.evictBook(ssno)
        open(restoreFromRoom = false)
    }

    fun setPage(index: Int) {
        val pages = handle?.pages ?: return
        val next = index.coerceIn(0, pages.lastIndex)
        if (next == pageIndex) return
        pageIndex = next
        loader?.let { h -> handle?.let { it1 -> h.setPrefetchWindow(it1, next) } }
        scheduleProgress()
    }

    private fun open(restoreFromRoom: Boolean) {
        val site = site ?: return
        val id = ssno
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val h = withContext(Dispatchers.IO) { Jiaocai1Api(site).openBook(id) }
                if (h == null || h.pages.isEmpty()) {
                    error = "这本书暂时打不开，可能没有开放全文，或阅读令牌已过期。"
                } else {
                    if (restoreFromRoom) restoreShelf(h) else keepPage(h)
                    handle = h
                    loader?.setPrefetchWindow(h, pageIndex)
                }
            } catch (e: AuthExpiredException) {
                authExpired = true
            } catch (e: Exception) {
                error = "打开失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun restoreShelf(h: Jiaocai1BookHandle) {
        val context = appContext ?: return
        val dao = AppDatabase.getInstance(context).jiaocai1ShelfDao()
        val existing = withContext(Dispatchers.IO) { dao.get(h.ssno) }
        val now = System.currentTimeMillis()
        val index = existing?.lastReadIndex?.coerceIn(0, h.pages.lastIndex) ?: 0
        pageIndex = index
        withContext(Dispatchers.IO) {
            dao.upsert(
                (existing ?: Jiaocai1ShelfEntity(
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
        val context = appContext ?: return
        val h = handle ?: return
        withContext(Dispatchers.IO) {
            AppDatabase.getInstance(context).jiaocai1ShelfDao()
                .updateProgress(h.ssno, pageIndex, System.currentTimeMillis(), h.pages.size)
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        val context = appContext
        val h = handle
        val index = pageIndex
        if (context != null && h != null) {
            runBlocking {
                AppDatabase.getInstance(context).jiaocai1ShelfDao()
                    .updateProgress(h.ssno, index, System.currentTimeMillis(), h.pages.size)
            }
        }
        loader?.close()
        loader = null
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_DEBOUNCE_MS = 400L
    }
}
