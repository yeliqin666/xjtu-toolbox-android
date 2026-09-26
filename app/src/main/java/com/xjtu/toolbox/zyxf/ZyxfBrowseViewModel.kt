package com.xjtu.toolbox.zyxf

import android.content.Context
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

/** 仲英学辅资料站：目录栈浏览、检索、排序、下载。每次只跑一个加载，新的顶掉旧的。 */
internal class ZyxfBrowseViewModel(context: Context) : ViewModel() {
    private val context = context.applicationContext

    /** 目录栈。栈底是根目录，用于面包屑和返回。 */
    val stack = mutableStateListOf(Crumb(0, "全部资料"))
    var entries by mutableStateOf<List<ZyxfApi.Entry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set

    var query by mutableStateOf("")
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

    init { reload() }

    /** 重新拉当前目录（或重跑当前检索）。 */
    fun reload() {
        if (searching) runSearch() else load { ZyxfApi.listFolder(stack.last().id, sort, desc) to false }
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
                error = e.message ?: if (searching) "检索失败" else "加载失败"
            }
            loading = false
        }
    }

    fun openFolder(entry: ZyxfApi.Entry) {
        query = ""
        searching = false
        stack.add(Crumb(entry.id, entry.name))
        reload()
    }

    fun goTo(index: Int) {
        if (index >= stack.lastIndex) return
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        query = ""
        searching = false
        reload()
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) { clearSearch(); return }
        searching = true
        load { ZyxfApi.search(q).let { it.entries to it.truncated } }
    }

    fun clearSearch() {
        query = ""
        searching = false
        reload()
    }

    /** 再点当前项＝翻转方向，换一项＝切字段并回到默认方向（时间默认新的在前）。 */
    fun pickSort(picked: ZyxfApi.Sort) {
        if (picked == sort) desc = !desc else { sort = picked; desc = picked == ZyxfApi.Sort.TIME }
        reload()
    }

    fun download(entry: ZyxfApi.Entry) {
        if (downloadState[entry.id] == DOWNLOADING) return
        downloadState[entry.id] = DOWNLOADING
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { ZyxfApi.download(context, entry.id) }.getOrNull() }
            downloadState[entry.id] = if (saved != null) "已保存到下载" else "下载失败"
        }
    }
}
