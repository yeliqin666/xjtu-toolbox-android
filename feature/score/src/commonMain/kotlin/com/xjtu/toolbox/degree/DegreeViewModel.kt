package com.xjtu.toolbox.degree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 用户自己确认的抵扣（`课程号 -> 课组 id`），按账号存本机。教务并不知道。 */
object DegreeConfirmations {
    private const val KEY = "confirmed"
    private fun store() = keyValueStore("degree" + AccountContext.safeSuffix())

    fun load(): Map<String, String> = runCatching {
        AppJson.parseToJsonElement(store().getString(KEY) ?: "{}").jsonObject
            .mapNotNull { (k, v) -> v.safeStringOrNull()?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun save(value: Map<String, String>) {
        store().putString(KEY, JsonObject(value.mapValues { JsonPrimitive(it.value) }).toString())
    }
}

/** 成绩和进度都不落盘：每次进页面现查。 */
internal class DegreeViewModel(private val source: DegreeSource) : ViewModel() {
    private var raw: DegreeRaw? = null
    private var mine: DegreeRaw? = null
    private var browsingPlan: DegreePlanSummary? = null

    var progress by mutableStateOf<DegreeProgress?>(null); private set
    var isLoading by mutableStateOf(true); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var recalculating by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var confirmed by mutableStateOf(DegreeConfirmations.load()); private set

    /** 正在看别的方案：用成绩对照估算，不读教务报告，也不套用个人抵扣。 */
    var browsing by mutableStateOf(false); private set

    var planResults by mutableStateOf<List<DegreePlanSummary>>(emptyList()); private set
    var planHasMore by mutableStateOf(false); private set
    var planLoading by mutableStateOf(false); private set
    var planError by mutableStateOf<String?>(null); private set
    private var planJob: Job? = null
    private var loadJob: Job? = null

    /** 换方案时默认看自己这一级：转专业基本是看同级的方案。 */
    val defaultGrade: String get() = mine?.grade ?: todayOnCampus().year.toString()

    val gradeChoices: List<String>
        get() {
            val own = defaultGrade.toIntOrNull() ?: return listOf(defaultGrade)
            return (own + 2 downTo own - 2).map { it.toString() }
        }

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        isLoading = true
        error = null
        val plan = browsingPlan
        loadJob = viewModelScope.launch {
            try {
                if (plan != null) {
                    val fresh = source.fetchPlan(plan)
                    raw = fresh
                    rebuild(fresh)
                } else {
                    val fresh = source.fetch()
                    raw = fresh
                    mine = fresh
                    rebuild(fresh)
                }
                isLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                isLoading = false
                if (plan != null) notice = "这个方案没打开"
            }
        }
    }

    private fun rebuild(data: DegreeRaw) {
        // 别的方案不套用个人抵扣：那些是对自己方案的确认
        progress = DegreeBuilder.build(data, if (browsingPlan == null) confirmed else emptyMap())
    }

    fun browse(plan: DegreePlanSummary) {
        browsingPlan = plan
        browsing = true
        notice = null
        progress = null
        load()
    }

    fun backToMine() {
        browsingPlan = null
        browsing = false
        notice = null
        val own = mine
        if (own != null) {
            loadJob?.cancel()
            raw = own
            isLoading = false
            error = null
            rebuild(own)
        } else {
            progress = null
            load()
        }
    }

    fun searchPlans(keyword: String, grade: String) {
        planJob?.cancel()
        planLoading = true
        planError = null
        planJob = viewModelScope.launch {
            try {
                val page = source.searchPlans(keyword.trim(), grade)
                planResults = page.plans
                planHasMore = page.total > page.plans.size
                planLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                planResults = emptyList()
                planHasMore = false
                planError = "没查到方案，稍后再试"
                planLoading = false
            }
        }
    }

    /** 用户点了「重新计算」才走教务那边算，算完再读一遍。 */
    fun recalculate() {
        if (recalculating) return
        recalculating = true
        notice = null
        viewModelScope.launch {
            try {
                if (!source.recalculate()) notice = "教务还在计算，稍后下拉刷新看看"
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "重新计算没成功，下面还是上次的结果"
            } finally {
                recalculating = false
            }
        }
    }

    fun confirm(course: DegreeLooseCourse, nodeId: String) = update(confirmed + (course.code.trim().uppercase() to nodeId))

    fun reassign(code: String, nodeId: String) = update(confirmed + (code.trim().uppercase() to nodeId))

    fun undoConfirm(code: String) = update(confirmed - code.trim().uppercase())

    private fun update(value: Map<String, String>) {
        confirmed = value
        DegreeConfirmations.save(value)
        raw?.let(::rebuild)
    }
}
