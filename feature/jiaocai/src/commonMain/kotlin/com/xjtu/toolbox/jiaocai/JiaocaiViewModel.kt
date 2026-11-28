package com.xjtu.toolbox.jiaocai

import androidx.compose.runtime.getValue
import com.xjtu.toolbox.error.FriendlyError
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
/**
 * 教材中心的浏览态。
 *
 * 关键词和结果放 ViewModel：离开再回来（例如去读全文）不会把搜索结果冲掉。
 */
class JiaocaiViewModel : ViewModel() {

    private var source: JiaocaiSource? = null
    private var searchJob: Job? = null

    var keyword by mutableStateOf("")
    var books by mutableStateOf<List<JiaocaiBook>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasSearched by mutableStateOf(false)
        private set
    var authExpired by mutableStateOf(false)

    /** 当前展开的详情，null 表示停在列表 */
    var selected by mutableStateOf<JiaocaiBook?>(null)

    fun bind(source: JiaocaiSource) {
        this.source = source
    }
    fun search() {
        val src = source ?: return
        if (keyword.isBlank()) return
        // 只认最近一次搜索：旧请求晚回来不能盖掉新结果
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                books = src.search(keyword)
                hasSearched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `:core` 只能按标记接口认会话失效（`AuthExpiredException` 在 `:data`，
                // 它实现了这个接口），与 `Jiaocai1ViewModel` 同一条写法。
                if (e is SessionExpiredFailure) authExpired = true else error = FriendlyError.of(e, "搜索")
            } finally {
                if (isActive) loading = false   // 被新搜索顶掉时别清新搜索的加载态
            }
        }
    }

    fun searchWith(word: String) {
        keyword = word
        search()
    }
}
