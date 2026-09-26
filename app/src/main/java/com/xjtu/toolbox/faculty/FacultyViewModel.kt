package com.xjtu.toolbox.faculty

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 教师主页检索。跳去浏览器看主页再返回时本页还在栈里，条件、结果、打开的详情都原样保留。 */
internal class FacultyViewModel : ViewModel() {
    val api = FacultyApi()

    var nameQuery by mutableStateOf(""); private set
    var college by mutableStateOf<FacultyOption?>(null); private set
    var discipline by mutableStateOf<FacultyOption?>(null); private set
    var proRank by mutableStateOf(""); private set

    var filters by mutableStateOf(FacultyFilters()); private set
    var members by mutableStateOf<List<FacultyMember>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    private var page = 1
    private var totalPage = 1
    val hasMore get() = page < totalPage
    var loading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** 每完成一次新查询自增，界面据此把列表滚回顶部。 */
    var searchGeneration by mutableIntStateOf(0); private set
    /** 正在查看的教师。 */
    var detail by mutableStateOf<FacultyMember?>(null)

    private var searchJob: Job? = null

    init {
        loadFilters()
        search(debounce = false)
    }

    private fun query() = FacultySearchQuery(
        name = nameQuery.trim(),
        collegeId = college?.id ?: 0,
        disciplineId = discipline?.id ?: 0,
        proRank = proRank,
    )

    /** 筛选表只拉一次：页面 400 KB，学院 / 学科一年也变不了几次。失败要留日志。 */
    private fun loadFilters() = viewModelScope.launch {
        runCatching { api.loadFilters() }
            .onSuccess { filters = it }
            .onFailure { Log.w(TAG, "筛选项加载失败", it) }
    }

    fun changeName(value: String) { nameQuery = value; search() }
    fun pickCollege(value: FacultyOption?) { college = value; search() }
    fun pickDiscipline(value: FacultyOption?) { discipline = value; search() }
    fun pickProRank(value: String) { proRank = value; search() }
    fun clearFilters() { college = null; discipline = null; proRank = ""; search() }
    fun retry() = search(debounce = false)

    /** 条件变化后防抖重查：350ms 照着输入法上屏节奏定，再短每个拼音都是一次请求。 */
    private fun search(debounce: Boolean = true) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            loading = true
            error = null
            page = 1
            try {
                val result = api.search(query(), page = 1)
                members = result.members
                total = result.total
                totalPage = result.totalPage
                searchGeneration++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "教师检索失败", e)
                error = e.message ?: "加载失败"
            }
            loading = false
        }
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val result = api.search(query(), page = page + 1)
                val seen = members.mapTo(mutableSetOf()) { it.teacherId }
                members = members + result.members.filter { seen.add(it.teacherId) }
                page += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "加载下一页失败", e)
            } finally {
                loadingMore = false
            }
        }
    }

    private companion object {
        const val TAG = "FacultyViewModel"
    }
}
