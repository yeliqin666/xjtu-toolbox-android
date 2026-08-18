package com.xjtu.toolbox.jiaocai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 教材中心的浏览态。
 *
 * 关键词和结果放 ViewModel：离开再回来（例如去读全文）不会把搜索结果冲掉。
 */
class JiaocaiViewModel : ViewModel() {

    private var site: SiteSession? = null

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

    fun bind(session: SiteSession) {
        site = session
    }

    fun search() {
        val session = site ?: return
        if (keyword.isBlank()) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                books = withContext(Dispatchers.IO) { JiaocaiApi(session).search(keyword) }
                hasSearched = true
            } catch (e: AuthExpiredException) {
                authExpired = true
            } catch (e: Exception) {
                error = "搜索失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun searchWith(word: String) {
        keyword = word
        search()
    }
}
