package com.xjtu.toolbox.bus

import kotlinx.datetime.LocalDate

/** 校车（`xjbus.xjtu.edu.cn`）的数据模型。上游响应里的乘客名单有意不建模，见 `BusApi`。 */
data class BusStop(
    val name: String,
    /** 上游多数站点没有时间，只有发车站和个别站点有。 */
    val time: String? = null,
    /** 上车点说明，如「阳光小区西门路对面」。 */
    val remark: String = "",
)

data class BusRun(
    val lineName: String,
    /** 发车时间 `HH:mm`。 */
    val time: String,
    /** 「创新港班车」「兴庆班车」。 */
    val category: String,
    val seats: Int,
    val usedSeats: Int,
    val plate: String,
    val from: String,
    val to: String,
    val stops: List<BusStop>,
) {
    val seatsLeft: Int get() = (seats - usedSeats).coerceAtLeast(0)
    val isFull: Boolean get() = seats > 0 && seatsLeft == 0
    /** 余量不到两成：提醒早点去排队。 */
    val isTight: Boolean get() = seats > 0 && seatsLeft * 5 < seats
}

/** 校车取数端口。 */
interface BusSource {
    suspend fun runs(date: LocalDate): List<BusRun>
}

object BusRules {
    /** 学生坐校车不用预约，页面和小岱都要这么说，别让人去找预约入口。 */
    const val BOARDING_TIP = "学生无需预约，留意余量，早点去排队"

    /** 校区名不是站名：兴庆校区的站叫思源中心、思源南广场，雁塔校区的叫医学行政楼。 */
    private val ALIASES = mapOf("兴庆" to listOf("思源"), "雁塔" to listOf("医学"))
    private val ROUTE_SEPARATOR = Regex("""\s*(?:到|至|→|->|-|\s)\s*""")

    private fun terms(query: String): List<String> {
        val q = query.trim().removeSuffix("校区")
        return listOf(q) + ALIASES.entries.filter { q.startsWith(it.key) }.flatMap { it.value }
    }

    private fun stopMatches(stop: BusStop, query: String) = terms(query).any { stop.name.contains(it, ignoreCase = true) }

    /** 这班车是否先经过 [from] 再到 [to]；只给一头时只看这一头。方向反了不算。 */
    fun runsBetween(run: BusRun, from: String?, to: String?): Boolean {
        val start = from?.takeIf { it.isNotBlank() }
        val end = to?.takeIf { it.isNotBlank() }
        val first = if (start == null) -1 else run.stops.indexOfFirst { stopMatches(it, start) }.also { if (it < 0) return false }
        val last = if (end == null) Int.MAX_VALUE else run.stops.indexOfLast { stopMatches(it, end) }.also { if (it < 0) return false }
        return first < last
    }

    /** 搜索框：「创新港 思源」「创新港到思源」按方向找；单个词匹配线路名、车牌或站点。 */
    fun search(runs: List<BusRun>, query: String): List<BusRun> {
        val q = query.trim()
        if (q.isEmpty()) return runs
        val parts = q.split(ROUTE_SEPARATOR).filter { it.isNotBlank() }
        if (parts.size == 2) return runs.filter { runsBetween(it, parts[0], parts[1]) }
        return runs.filter { run ->
            run.lineName.contains(q, ignoreCase = true) || run.plate.contains(q, ignoreCase = true) ||
                run.stops.any { stopMatches(it, q) }
        }
    }

    /** 同一分钟算还没走：人可能正在上车。 */
    fun hasDeparted(run: BusRun, nowHhmm: String): Boolean = run.time < nowHhmm
}
