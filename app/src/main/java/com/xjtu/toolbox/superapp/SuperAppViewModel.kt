package com.xjtu.toolbox.superapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SuperAppViewModel(site: SiteSession, private val cache: DataCache) : ViewModel() {
    private val api = SuperAppApi(site)

    /** null = 还没有任何数据（缓存也没有）。 */
    var categories by mutableStateOf<List<SuperAppCategory>?>(null); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null)

    private val expired = Channel<Unit>(Channel.CONFLATED)
    val authExpired = expired.receiveAsFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cache.getStale(CACHE_KEY)?.let { runCatching { SuperAppApi.parse(it) }.getOrNull() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { categories = it }
            refresh()
        }
    }

    /** 有数据时下拉刷新失败只提示一句，页面照旧；没数据时整页报错。 */
    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            refreshing = categories != null
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = api.fetchRaw()
                    SuperAppApi.parse(raw).also { if (it.isNotEmpty()) cache.put(CACHE_KEY, raw) }
                }
            }.onSuccess { categories = it }.onFailure { e ->
                if (e is AuthExpiredException) expired.trySend(Unit)
                val message = e.message?.takeIf { it.isNotBlank() } ?: "服务列表加载失败"
                if (categories == null) error = message else toast = "刷新失败：$message"
            }
            refreshing = false
        }
    }

    private companion object {
        const val CACHE_KEY = "superapp_services"
    }
}
