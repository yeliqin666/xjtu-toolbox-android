package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.error.FriendlyError
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal class SchoolCourseViewModel(private val source: SchoolCourseSource) : ViewModel() {
    val campusList = SCHOOL_COURSE_CAMPUSES
    val electiveCategories = SCHOOL_COURSE_ELECTIVE_CATEGORIES
    private val authExpiredChannel = Channel<Unit>(Channel.CONFLATED)
    val authExpired = authExpiredChannel.receiveAsFlow()

    var isInitializing by mutableStateOf(true); private set
    var initError by mutableStateOf<String?>(null); private set
    var termList by mutableStateOf<List<TermOption>>(emptyList()); private set
    var departmentList by mutableStateOf<List<DepartmentOption>>(emptyList()); private set
    var currentTermCode by mutableStateOf(""); private set

    var result by mutableStateOf<SchoolCourseResult?>(null); private set
    var isSearching by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    var currentPage by mutableIntStateOf(1); private set
    private var lastQuery: SchoolCourseQuery? = null
    private var searchJob: Job? = null

    init { loadOptions() }

    fun loadOptions() {
        isInitializing = true
        initError = null
        viewModelScope.launch {
            try {
                coroutineScope {
                    val terms = async { source.terms() }
                    val departments = async { source.departments() }
                    val current = async { source.currentTerm() }
                    termList = terms.await()
                    departmentList = departments.await()
                    currentTermCode = current.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 会话失效的判据是 :core 的标记接口（:app 的 AuthExpiredException 实现了它）——
                // `catch` 抓不了接口，所以先抓 Exception 再判。
                if (e is SessionExpiredFailure) {
                    authExpiredChannel.send(Unit)
                } else {
                    Log.e(TAG, "init failed", e)
                    initError = FriendlyError.of(e, "初始化")
                }
            } finally {
                isInitializing = false
            }
        }
    }

    fun search(query: SchoolCourseQuery, page: Int = 1) {
        if (query.termCode.isBlank()) return
        lastQuery = query
        isSearching = true
        searchError = null
        currentPage = page
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                result = source.query(query, page = page, pageSize = 20)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpiredChannel.send(Unit)
                } else {
                    Log.e(TAG, "search failed", e)
                    searchError = FriendlyError.of(e, "查询")
                }
            } finally {
                isSearching = false
            }
        }
    }

    /** 翻页 / 重试沿用上一次的条件。 */
    fun goToPage(page: Int) {
        lastQuery?.let { search(it, page) }
    }

    private companion object {
        const val TAG = "SchoolCourseViewModel"
    }
}
