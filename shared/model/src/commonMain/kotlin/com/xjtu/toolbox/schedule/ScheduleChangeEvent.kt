package com.xjtu.toolbox.schedule

/**
 * 一条调课/停课/补课记录，来自教务 `xsdkkc.do`（见 [JwxtChanges]），供屁岱回答「最近调了什么课」。
 * 课表本身已经合并过这些记录，这里只是给人看的说明。
 */
@kotlinx.serialization.Serializable
data class ScheduleChangeEvent(
    val courseName: String = "",
    val courseCode: String = "",
    /** 不给默认值：缺了这条就没法描述，读的时候整条丢掉。 */
    val kind: Kind,
    /** 原时段；停课/移课有效。新增课没有原时段，值为 0。 */
    val fromDay: Int = 0,
    val fromStartSection: Int = 0,
    val fromEndSection: Int = 0,
    /** 新时段/新教室；移课/新增课有效。停课没有新时段，day 为 0。 */
    val toDay: Int = 0,
    val toStartSection: Int = 0,
    val toEndSection: Int = 0,
    val toLocation: String = "",
    /** 原时段在哪几周被停/被调走；补课为空。 */
    val weeks: List<Int> = emptyList(),
    /** 新时段落在哪几周；停课为空。调课可以跨周（第 4 周的课挪到第 6 周周末补）。 */
    val toWeeks: List<Int> = emptyList(),
) {
    enum class Kind { MOVED, CANCELLED, ADDED }

    /** 一句话人话描述，不含课程名（调用方按需拼在课程名后面）。 */
    fun describe(): String {
        fun slot(day: Int, start: Int, end: Int): String? =
            if (day in 1..7 && start > 0 && end > 0) "${DAY_NAMES[day]}第${start}-${end}节" else null
        val fromWeeks = weekLabel(weeks)
        val targetWeeks = weekLabel(toWeeks)
        val from = slot(fromDay, fromStartSection, fromEndSection)
        val to = slot(toDay, toStartSection, toEndSection)
        val place = toLocation.takeIf { it.isNotBlank() }
        return when (kind) {
            Kind.CANCELLED -> "${fromWeeks}停课（${from ?: "原安排"}）"
            Kind.ADDED -> "${targetWeeks}补课" + (to?.let { "，$it" } ?: "") + (place?.let { "，$it" } ?: "")
            Kind.MOVED -> {
                // 同一周内挪动只说一次周次；跨周时两头各说各的
                val sameWeeks = weeks == toWeeks || toWeeks.isEmpty()
                // 教务的调课很多只是换教室
                if (sameWeeks && from != null && from == to && place != null) return "$fromWeeks${from}换到$place"
                val head = if (sameWeeks) fromWeeks else ""
                val fromPart = (if (sameWeeks) "" else fromWeeks) + (from ?: "原安排")
                val toPart = (if (sameWeeks) "" else targetWeeks) + (to ?: "新安排")
                "${head}由${fromPart}调至${toPart}" + (place?.let { "，$it" } ?: "")
            }
        }
    }

    /** `[4, 6, 7, 8]` → `第4、6-8周`；空表返回空串。 */
    private fun weekLabel(list: List<Int>): String {
        if (list.isEmpty()) return ""
        val sorted = list.distinct().sorted()
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j > i) "${sorted[i]}-${sorted[j]}" else "${sorted[i]}"
            i = j + 1
        }
        return "第${parts.joinToString("、")}周"
    }
}

// 不能放进 private companion object：@Serializable 类的伴生对象由插件生成、别的类取序列化器要读它，
// 自己写成 private 后跨类读它直接 IllegalAccessError（5.0.8 正式版保存调课记录时崩过）
private val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
