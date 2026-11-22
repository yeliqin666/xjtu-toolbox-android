package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * 日程页里系统日历（#462）的重读调度。
 *
 * - [refresh]：进页面、跨天、回到前台时直接重读；新的一次会取消还没读完的上一次。
 * - [watch]：收集宿主给的日历库变化信号（ContentObserver），[debounceMillis] 内的一串变化只重读一次
 *   （账户同步时会连着来好几下）。
 *
 * 读的时间段由 [SystemCalendarRules.queryWindow] 给出（前后各多一天）。读失败交给 [onError]、结果记为空表。
 */
internal class SystemCalendarRefresher(
    private val scope: CoroutineScope,
    private val days: Int,
    private val today: () -> LocalDate,
    private val load: suspend (from: Instant, to: Instant) -> List<SystemCalendarEvent>,
    private val onLoaded: (List<SystemCalendarEvent>) -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val zone: TimeZone = CampusTimeZone,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
) {
    private var job: Job? = null

    fun refresh(day: LocalDate = today()) {
        job?.cancel()
        job = scope.launch {
            val (from, to) = SystemCalendarRules.queryWindow(day, days, zone)
            val events = try {
                load(from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                emptyList()
            }
            onLoaded(events)
        }
    }

    /** 一直收集到调用方被取消（日程页退到后台 / 离开）为止。 */
    @OptIn(FlowPreview::class)
    suspend fun watch(changes: Flow<Unit>) {
        changes.debounce(debounceMillis).collect { refresh() }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
