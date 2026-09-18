package com.xjtu.toolbox.schedule

/**
 * jwapp 一条调课/停课/新增课记录，官方原始信息，用于课表变更提醒。
 *
 * 字段含义反查自 jwapp 官方 App 自己的前端逻辑（HAR 抓包 + 反编译
 * `pages-timetable-index` 静态资源核实，非推测）：`bz` 就是官方展示给学生的调课备注；
 * 目标节次/教室在 `xksjc`/`xjsjc`/`xjasmc`，不是原始行的 `ksjc`/`jsjc`/`jasmc`——那三个
 * 只用于定位被改的原始行，[JwappScheduleApi] 早前的合并逻辑曾经把它们错当成新时段用。
 *
 * 只有课表源选 jwapp 时才有这份数据；jwxt 不返回变更记录，bkkq 未核实是否有等价字段。
 */
data class ScheduleChangeEvent(
    val courseName: String,
    val courseCode: String,
    val kind: Kind,
    /** 原时段；停课/移课有效。新增课没有原时段，值为 0。 */
    val fromDay: Int,
    val fromStartSection: Int,
    val fromEndSection: Int,
    /** 新时段/新教室；移课/新增课有效。停课没有新时段，day 为 0。 */
    val toDay: Int,
    val toStartSection: Int,
    val toEndSection: Int,
    val toLocation: String,
    /** 官方调课备注（`bz`）。可能为空——不是每条变更都会填。 */
    val reason: String,
) {
    enum class Kind { MOVED, CANCELLED, ADDED }

    /** 落盘反序列化兜底，原理见 [CourseItem.sanitized]。kind 缺失的条目无法描述，返回 null。 */
    fun sanitized(): ScheduleChangeEvent? {
        val k = (kind as Kind?) ?: return null
        return copy(
            courseName = (courseName as String?) ?: "",
            courseCode = (courseCode as String?) ?: "",
            kind = k,
            toLocation = (toLocation as String?) ?: "",
            reason = (reason as String?) ?: "",
        )
    }

    /** 一句话人话描述，不含课程名（调用方按需拼在课程名后面）。 */
    fun describe(): String {
        fun slot(day: Int, start: Int, end: Int): String? =
            if (day in 1..7 && start > 0 && end > 0) "${DAY_NAMES[day]}第${start}-${end}节" else null
        val from = slot(fromDay, fromStartSection, fromEndSection)
        val to = slot(toDay, toStartSection, toEndSection)
        val place = toLocation.takeIf { it.isNotBlank() }
        return when (kind) {
            Kind.CANCELLED -> "停课（${from ?: "原安排"}）"
            Kind.ADDED -> "新增" + (to?.let { "，$it" } ?: "") + (place?.let { "，$it" } ?: "")
            Kind.MOVED -> "由${from ?: "原安排"}调至${to ?: "新安排"}" + (place?.let { "，$it" } ?: "")
        }
    }

    private companion object {
        val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
