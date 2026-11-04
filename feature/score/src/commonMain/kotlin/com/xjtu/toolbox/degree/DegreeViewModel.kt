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
import kotlinx.coroutines.CancellationException
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

    var progress by mutableStateOf<DegreeProgress?>(null); private set
    var isLoading by mutableStateOf(true); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var recalculating by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var confirmed by mutableStateOf(DegreeConfirmations.load()); private set

    init {
        load()
    }

    fun load() {
        isLoading = true
        error = null
        viewModelScope.launch {
            try {
                val fresh = source.fetch()
                raw = fresh
                rebuild(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                isLoading = false
            }
        }
    }

    private fun rebuild(data: DegreeRaw) {
        progress = DegreeBuilder.build(data, confirmed)
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

    fun undoConfirm(code: String) = update(confirmed - code.trim().uppercase())

    private fun update(value: Map<String, String>) {
        confirmed = value
        DegreeConfirmations.save(value)
        raw?.let(::rebuild)
    }
}
