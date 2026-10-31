package com.xjtu.toolbox.notification

import androidx.compose.runtime.getValue
import com.xjtu.toolbox.error.FriendlyError
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/**
 * 通知：来源多选、合并分页与站内搜索。
 * 选择项由界面直接改，这里监听变化：来源变了重新加载，搜索词停手 0.5 秒后查全站。
 */
internal class NotificationViewModel(
    private val api: NoticeSource,
    private val preferences: KeyValueStore = keyValueStore("notification_sources" + AccountContext.safeSuffix()),
) : ViewModel() {

    var selectedSources by mutableStateOf(
        preferences.getStringSet("sources").orEmpty().mapNotNull { name -> NotificationSource.entries.firstOrNull { it.name == name } }
            .toSet().ifEmpty { setOf(NotificationSource.JWC) }
    ); private set
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

    /** 每次换来源后的重新加载完成就加一，界面据此滚回顶部。 */
    var reloadGeneration by mutableIntStateOf(0); private set

    /** 按来源组合缓存：切回去时先显示上次的内容。 */
    private val cache = mutableMapOf<String, List<Notification>>()
    // `toSortedSet()` 是 JVM 专属（默认导入，import 判据抓不到）⇒ 换成 `sorted()`：
    // 枚举的自然序就是 ordinal 序，键的语义不变（它只是缓存 map 的键）。
    private val cacheKey: String get() = selectedSources.sorted().joinToString(",")
    val searching get() = searchQuery.isNotBlank()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            snapshotFlow { selectedSources }.distinctUntilChanged().collectLatest {
                reload()
            }
        }
        viewModelScope.launch {
            snapshotFlow { searchQuery to cacheKey }.distinctUntilChanged().drop(1).collectLatest { (query, _) -> search(query) }
        }
    }

    fun selectSources(sources: Set<NotificationSource>) {
        if (sources.isNotEmpty()) {
            preferences.putStringSet("sources", sources.map { it.name }.toSet())
            selectedSources = sources.toSet()
        }
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
        // Compose 状态在主线程取好再进 IO：init 里就会走到这里，那时状态还在组合的快照里没提交
        val sources = selectedSources.toList()
        try {
            val fetched = api.merged(sources, page)
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
            if (append) hasMorePages = false else errorMessage = FriendlyError.of(e, "加载通知")
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
            val sources = selectedSources.toList()
            val r = api.search(sources, kw)
            searchResults = r.items
            searchNotice = r.skipped.takeIf { it.isNotEmpty() }
                ?.joinToString("、") { it.displayName }?.let { "$it 这次没搜成，可能是网络问题或站点维护" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchNotice = "${FriendlyError.of(e, "站内搜索")}，下面只是已加载通知里的匹配"
        } finally {
            searchLoading = false
        }
    }
}
