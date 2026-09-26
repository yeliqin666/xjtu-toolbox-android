package com.xjtu.toolbox.schedule

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全校课程的查询条件；空串 / 0 / null 表示不限。 */
internal data class SchoolCourseQuery(
    val termCode: String,
    val courseName: String = "",
    val courseCode: String = "",
    val teacher: String = "",
    val departmentCode: String = "",
    val className: String = "",
    val campusCode: String = "",
    val isPublicElective: Boolean? = null,
    val electiveCategoryCode: String = "",
    val weekday: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
)

internal class SchoolCourseViewModel(site: SiteSession) : ViewModel() {
    private val api = SchoolCourseApi(site)
    val campusList = api.getCampusList()
    val electiveCategories = api.getElectiveCategories()
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
                    val terms = async(Dispatchers.IO) { api.getTermList() }
                    val departments = async(Dispatchers.IO) { api.getDepartments() }
                    val current = async(Dispatchers.IO) { api.getCurrentTerm() }
                    termList = terms.await()
                    departmentList = departments.await()
                    currentTermCode = current.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpiredChannel.send(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "init failed", e)
                initError = "初始化失败: ${e.message}"
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
                result = withContext(Dispatchers.IO) {
                    api.queryCourses(
                        termCode = query.termCode,
                        courseName = query.courseName.ifBlank { null },
                        courseCode = query.courseCode.ifBlank { null },
                        teacher = query.teacher.ifBlank { null },
                        departmentCode = query.departmentCode.ifBlank { null },
                        className = query.className.ifBlank { null },
                        campusCode = query.campusCode.ifBlank { null },
                        isPublicElective = query.isPublicElective,
                        electiveCategoryCode = query.electiveCategoryCode.ifBlank { null },
                        weekday = query.weekday.takeIf { it > 0 },
                        startSection = query.startSection.takeIf { it > 0 },
                        endSection = query.endSection.takeIf { it > 0 },
                        pageSize = 20,
                        pageNumber = page,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpiredChannel.send(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "search failed", e)
                searchError = "查询失败: ${e.message}"
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
