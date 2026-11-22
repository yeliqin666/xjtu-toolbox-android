package com.xjtu.toolbox.zyxf

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class Crumb(val id: Int, val name: String)

internal const val DOWNLOADING = "下载中…"
private const val TAG = "ZyxfBrowse"

/** 仲英学辅资料站：目录栈浏览、检索、排序、下载。每次只跑一个加载，新的顶掉旧的。 */
internal class ZyxfBrowseViewModel(context: Context) : ViewModel() {
    private val context = context.applicationContext

    /** 目录栈。栈底是根目录，用于面包屑和返回。 */
    val stack = mutableStateListOf(Crumb(0, "全部资料"))
    var entries by mutableStateOf<List<ZyxfApi.Entry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set

    var query by mutableStateOf(""); private set
    var searching by mutableStateOf(false); private set
    var truncated by mutableStateOf(false); private set

    // 排序跟网页版一致：默认是管理员手工排的顺序，再点同一项翻转升降序
    var sort by mutableStateOf(ZyxfApi.Sort.MANUAL); private set
    var desc by mutableStateOf(false); private set

    /** 每个文件的下载状态，key 是文件 ID。 */
    val downloadState = mutableStateMapOf<Int, String>()

    /** 正在预览的文件。 */
    var previewing by mutableStateOf<ZyxfApi.Entry?>(null)
    /**
     * 这次预览是不是在宽屏分栏里选的：分栏时点文件只是右栏换内容，不等于打开全屏预览，
     * 转成窄屏时分栏选的那一份作废，不能被当成全屏预览弹出来。
     */
    var previewFromSplit by mutableStateOf(false)

    private var job: Job? = null
    private var searchJob: Job? = null
    private var searchedQuery: String? = null
    private var started = false

    /**
     * 第一次真正上屏时才拉目录。
     *
     * ViewModel 现在建在宿主层（学辅 tab 的顶栏要读 query），不能再在 `init` 里请求：
     * 应用启动/常驻 tab 里建了实例但用户没点进学辅时，不该白打一次按 IP 限流的资料站接口。
     */
    fun ensureStarted() {
        if (started) return
        started = true
        reload()
    }

    /** 重新拉当前目录（或重跑当前检索）。 */
    fun reload() {
        if (searching) return runSearch(force = true)
        // 参数在调用线程上取好再交给 IO：ViewModel 是在组合里建的，那一刻 stack 还只在组合的
        // 快照里，IO 线程读全局快照看到的是空列表，stack.last() 直接抛「List is empty.」
        val folder = stack.last().id
        val sort = sort
        val desc = desc
        load { ZyxfApi.listFolder(folder, sort, desc) to false }
    }

    private fun load(fetch: suspend () -> Pair<List<ZyxfApi.Entry>, Boolean>) {
        job?.cancel()
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                val (list, cut) = withContext(Dispatchers.IO) { fetch() }
                entries = list
                truncated = cut
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
                // 服务端的 {"error": "..."} 是中文，原样给；其余异常的英文消息用户看不懂，换成人话
                error = if (e is ZyxfApi.ServerError) e.message else if (searching) "检索失败，检查网络后下拉重试" else "连不上资料站，检查网络后下拉重试"
            }
            loading = false
        }
    }

    fun openFolder(entry: ZyxfApi.Entry) {
        cancelPendingSearch()
        searchedQuery = null
        query = ""
        searching = false
        stack.add(Crumb(entry.id, entry.name))
        reload()
    }

    fun goTo(index: Int) {
        if (index >= stack.lastIndex) return
        cancelPendingSearch()
        searchedQuery = null
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        query = ""
        searching = false
        reload()
    }

    fun updateQuery(value: String) {
        if (query == value) return
        query = value
        searchJob?.cancel()
        job?.cancel() // 输入已经变化，不让旧响应覆盖新查询。
        searchedQuery = null // 取消中的查询没有完整结果，重新输回同一词也必须可重试。
        loading = false
        if (value.isBlank()) { clearSearch(); return }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            searchJob = null
            runSearch()
        }
    }

    fun cancelPendingSearch() { searchJob?.cancel(); searchJob = null }

    fun runSearch(force: Boolean = false) {
        cancelPendingSearch()
        val q = query.trim()
        if (q.isEmpty()) { clearSearch(); return }
        if (!force && searching && searchedQuery == q && error == null) return
        searching = true
        searchedQuery = q
        load { ZyxfApi.search(q).let { it.entries to it.truncated } }
    }

    fun clearSearch() {
        cancelPendingSearch()
        searchedQuery = null
        query = ""
        searching = false
        reload()
    }

    /** 再点当前项＝翻转方向，换一项＝切字段并回到默认方向（时间默认新的在前）。 */
    fun pickSort(picked: ZyxfApi.Sort) {
        if (picked == ZyxfApi.Sort.MANUAL) {
            // 「默认」没有方向：已经默认时再点不做事，也不白发一次目录请求
            if (sort == ZyxfApi.Sort.MANUAL) return
            sort = ZyxfApi.Sort.MANUAL
            desc = false
            reload()
            return
        }
        if (picked == sort) desc = !desc else { sort = picked; desc = picked == ZyxfApi.Sort.TIME }
        reload()
    }

    private val prefs = this.context.getSharedPreferences("zyxf_browse", android.content.Context.MODE_PRIVATE)

    /** 列表视图（#199）：一项一行，像官网；false = 文件夹两列卡片。记住用户的选择。 */
    var listView by mutableStateOf(prefs.getBoolean("list_view", false)); private set

    fun toggleView() {
        listView = !listView
        prefs.edit().putBoolean("list_view", listView).apply()
    }

    /** 长按菜单里「位置」一栏：浏览目录时取面包屑。 */
    fun location(): String = stack.drop(1).joinToString("/") { it.name }

    fun download(entry: ZyxfApi.Entry, then: ((uri: String) -> Unit)? = null) {
        if (downloadState[entry.id] == DOWNLOADING) return
        downloadState[entry.id] = DOWNLOADING
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { ZyxfApi.download(context, entry.id) }.getOrNull() }
            downloadState[entry.id] = if (saved != null) "已保存到下载" else "下载失败"
            if (saved == null || then == null) return@launch
            // 下载只回文件名，uri 去下载登记表里取刚存的那条
            withContext(Dispatchers.IO) {
                com.xjtu.toolbox.lms.LmsDownloadStore.getAll(context).filter { it.name == saved }.maxByOrNull { it.savedAt }?.uri
            }?.let(then)
        }
    }

    /** 长按「分享」：先下载，下完弹系统分享。 */
    fun share(entry: ZyxfApi.Entry) = download(entry) { uri ->
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.ext.lowercase()) ?: "*/*"
            putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(send, "分享「${entry.name}」").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 长按「加入待办」：当收藏用，待办里写清在哪能找到。 */
    fun addTodo(entry: ZyxfApi.Entry): Boolean {
        val where = entry.path.ifBlank { location() }
        return com.xjtu.toolbox.inbox.SelfTodoStore.create(
            title = (if (entry.isFolder) "看学辅文件夹「" else "看学辅资料「") + entry.name + "」",
            content = if (where.isNotEmpty()) "学辅 · $where" else "学辅资料站",
        ) != null
    }

    /** 长按「小岱解读」：新开一个对话发给小岱。 */
    fun askPidai(entry: ZyxfApi.Entry) {
        com.xjtu.toolbox.agent.AgentInsightHub.insights.ask(
            if (entry.isFolder) "帮我看看学辅资料站的文件夹「${entry.name}」（folder_id ${entry.id}）里有什么，哪些值得先看。"
            else "帮我讲讲学辅资料「${entry.name}」：先读它（file_id ${entry.id}），再挑重点讲，有题就讲思路。",
            null,
        )
    }
}
