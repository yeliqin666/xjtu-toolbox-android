package com.xjtu.toolbox.notification

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知：单个来源或多来源合并、翻页、站内搜索。
 * 选择项由界面直接改，这里监听变化：来源 / 模式变了重新加载，搜索词停手 0.5 秒后查全站。
 */
internal class NotificationViewModel : ViewModel() {
    private val api = NotificationApi()

    var selectedCategory by mutableStateOf<SourceCategory?>(null)   // null = 全部分类
    var selectedSource by mutableStateOf(NotificationSource.JWC)
    var mergeMode by mutableStateOf(false)
    var selectedSources by mutableStateOf(setOf(NotificationSource.JWC))
    var searchQuery by mutableStateOf("")

    var notifications by mutableStateOf<List<Notification>>(emptyList()); private set
    var isLoading by mutableStateOf(true); private set
    var isLoadingMore by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    /** 本轮被静默跳过的来源（域名级失败）：告诉用户可能没拉到，不是没人发通知。 */
    var skippedSourceNotice by mutableStateOf<String?>(null); private set
    var hasMorePages by mutableStateOf(true); private set
    private var page = 1

    var searchResults by mutableStateOf<List<Notification>?>(null); private set
    var searchLoading by mutableStateOf(false); private set
    var searchNotice by mutableStateOf<String?>(null); private set

    /** 每次换来源 / 模式后的重新加载完成就加一，界面据此滚回顶部。 */
    var reloadGeneration by mutableIntStateOf(0); private set

    /** 按来源组合缓存：切回去时先显示上次的内容。 */
    private val cache = mutableMapOf<Any, List<Notification>>()
    private val cacheKey: Any get() = if (mergeMode) selectedSources.toSortedSet().joinToString(",") else selectedSource
    val searching get() = searchQuery.isNotBlank()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            snapshotFlow { Triple(selectedSource, mergeMode, selectedSources) }.distinctUntilChanged().collectLatest {
                reload()
            }
        }
        viewModelScope.launch {
            snapshotFlow { searchQuery to cacheKey }.distinctUntilChanged().drop(1).collectLatest { (query, _) -> search(query) }
        }
    }

    fun toggleMerge() {
        mergeMode = !mergeMode
        if (mergeMode && selectedSources.isEmpty()) selectedSources = setOf(selectedSource)
    }

    /** 合并模式下至少留一个来源。 */
    fun toggleSource(source: NotificationSource) {
        selectedSources = if (source in selectedSources) {
            if (selectedSources.size > 1) selectedSources - source else selectedSources
        } else selectedSources + source
    }

    /** 下拉刷新：丢掉这个组合的缓存重拉。 */
    fun refresh() {
        cache.remove(cacheKey)
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        page = 1
        hasMorePages = true
        cache[cacheKey]?.let { notifications = it }
        loadJob = viewModelScope.launch {
            load(page = 1, append = false)
            reloadGeneration++
        }
    }

    fun loadMore() {
        if (searching || isLoading || isLoadingMore || !hasMorePages || notifications.isEmpty()) return
        isLoadingMore = true
        viewModelScope.launch { load(page = page + 1, append = true) }
    }

    private suspend fun load(page: Int, append: Boolean) {
        val key = cacheKey
        if (!append && cache[key] == null) isLoading = true
        errorMessage = null
        try {
            val fetched = withContext(Dispatchers.IO) {
                if (mergeMode) {
                    api.getMergedNotificationsWithSkipped(selectedSources.toList(), page)
                } else {
                    val single = try {
                        api.getNotificationPage(selectedSource, page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        NotificationPage(emptyList(), false)
                    }
                    MergedNotificationPage(single.items, emptySet(), single.hasMore)
                }
            }
            skippedSourceNotice = fetched.skipped.takeIf { it.isNotEmpty() }
                ?.joinToString("、") { it.displayName }?.let { "$it 暂不可达，可能是网络问题或站点维护" }
            if (append) {
                val seen = notifications.mapTo(HashSet()) { it.link }
                val fresh = fetched.items.filter { it.link !in seen }
                if (fresh.isEmpty()) {
                    hasMorePages = false
                } else {
                    notifications = notifications + fresh
                    hasMorePages = fetched.hasMore
                }
            } else {
                notifications = fetched.items
                hasMorePages = fetched.hasMore
            }
            cache[key] = notifications
            this.page = page
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (append) hasMorePages = false else errorMessage = "加载失败: ${e.message}"
        } finally {
            isLoading = false
            isLoadingMore = false
        }
    }

    /** 各站自己的检索查全站；结果回来之前界面先用本地已加载里的匹配顶着。 */
    private suspend fun search(query: String) {
        searchResults = null
        searchNotice = null
        val kw = query.trim()
        if (kw.isEmpty()) { searchLoading = false; return }
        searchLoading = true
        delay(500)
        try {
            val sources = if (mergeMode) selectedSources.toList() else listOf(selectedSource)
            val r = api.search(sources, kw)
            searchResults = r.items
            searchNotice = r.skipped.takeIf { it.isNotEmpty() }
                ?.joinToString("、") { it.displayName }?.let { "$it 这次没搜成，可能是网络问题或站点维护" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchNotice = "站内搜索失败：${e.message ?: "未知错误"}，下面只是已加载通知里的匹配"
        } finally {
            searchLoading = false
        }
    }
}
