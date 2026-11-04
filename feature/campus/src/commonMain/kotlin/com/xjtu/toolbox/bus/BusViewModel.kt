package com.xjtu.toolbox.bus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** 余量一直在变，不做缓存：切日期、下拉都现查。 */
internal class BusViewModel(
    private val source: BusSource,
    today: LocalDate = todayOnCampus(),
) : ViewModel() {
    val dates: List<LocalDate> = List(DAY_COUNT) { today.plus(DatePeriod(days = it)) }

    var date by mutableStateOf(today); private set
    var runs by mutableStateOf<List<BusRun>>(emptyList()); private set
    var isLoading by mutableStateOf(true); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var query by mutableStateOf("")
    var category by mutableStateOf<String?>(null)

    val categories: List<String> get() = runs.map { it.category }.filter { it.isNotEmpty() }.distinct()
    val shown: List<BusRun>
        get() = BusRules.search(runs, query).filter { run -> category.let { it == null || run.category == it } }

    private var job: Job? = null

    init {
        load()
    }

    fun selectDate(value: LocalDate) {
        if (value == date) return
        date = value
        runs = emptyList()
        load()
    }

    fun load() {
        job?.cancel()
        isLoading = true
        error = null
        val target = date
        job = viewModelScope.launch {
            try {
                runs = source.runs(target)
                // 兴庆班车不是天天有：换天后选中的分类可能没了，别留一个空列表
                if (category !in categories) category = null
                isLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                isLoading = false
            }
        }
    }

    private companion object {
        const val DAY_COUNT = 3
    }
}
