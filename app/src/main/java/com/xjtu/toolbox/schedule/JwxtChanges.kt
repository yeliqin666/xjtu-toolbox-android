package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import kotlinx.serialization.json.JsonObject

/** 一个来源给出的整学期课表，加上它知道的调课/停课/补课记录。 */
data class SourceSchedule(val courses: List<CourseItem>, val changes: List<ScheduleChangeEvent> = emptyList())

/** 教务课表的一行，带教学班号：调停课记录靠它找原课，课表行里的 KBID 是空的。 */
internal data class JwxtRow(val jxbid: String, val course: CourseItem)

/**
 * 教务一条调停课记录（`wdkb/modules/xskcb/xsdkkc.do`）。
 *
 * 原位置（[weeks]、[day]、[start]..[end]）是被调走或停掉的那几周那几节；新位置（`to*`）
 * 是调去或补上的。调课两头都有，停课只有原位置，补课只有新位置。
 */
internal data class JwxtChange(
    val kind: ScheduleChangeEvent.Kind,
    val jxbid: String,
    val courseName: String,
    val courseCode: String,
    val weeks: String,
    val day: Int,
    val start: Int,
    val end: Int,
    val location: String,
    val teacher: String,
    val toWeeks: String,
    val toDay: Int,
    val toStart: Int,
    val toEnd: Int,
    val toLocation: String,
    val toTeacher: String,
    val appliedAt: String,
) {
    companion object {
        /** 认不出类型的记录返回 null。 */
        fun of(row: JsonObject): JwxtChange? {
            val kind = when (row.get("TKLXDM").safeString().trim().trimStart('0')) {
                "1" -> ScheduleChangeEvent.Kind.MOVED
                "2" -> ScheduleChangeEvent.Kind.CANCELLED
                "3" -> ScheduleChangeEvent.Kind.ADDED
                else -> return null
            }
            return JwxtChange(
                kind = kind,
                jxbid = row.get("JXBID").safeString(),
                courseName = row.get("KCM").safeString(),
                courseCode = row.get("KCH").safeString(),
                weeks = row.get("SKZC").safeString(),
                day = row.get("SKXQ").safeInt(0),
                start = row.get("KSJC").safeInt(0),
                end = row.get("JSJC").safeInt(0),
                location = row.get("JASMC").safeString(),
                teacher = teacherNames(row.get("YSKJS").safeString()),
                toWeeks = row.get("XSKZC").safeString(),
                toDay = row.get("XSKXQ").safeInt(0),
                toStart = row.get("XKSJC").safeInt(0),
                toEnd = row.get("XJSJC").safeInt(0),
                toLocation = row.get("XJASMC").safeString(),
                toTeacher = teacherNames(row.get("XSKJS").safeString()),
                appliedAt = row.get("SQSJ").safeString(),
            )
        }

        /** `"高宏/0002009003,张三/0001"` → `"高宏,张三"`；空的是 `"/"`。 */
        private fun teacherNames(raw: String): String =
            raw.split(',').map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }.joinToString(",")
    }
}

/**
 * 把调停课合进教务的原始课表。教务 `xskcb.do` 给的是排课时的原样，调课不会反映进去。
 *
 * 规则（2026-09 用真实记录核对，15 条调课/停课都唯一对上原课）：
 * - 原课 = 教学班号、星期相同，节次包含被调的节次，周次有交集；
 * - 调课、停课：从原课挖掉那几周的那几节，一节大课只调走其中两小节时剩下的照常上；
 * - 调课、补课：在新位置加一节，课程名、性质、教室、教师缺的从同教学班的课补。
 * 按申请时间依次应用，先调走再停的也能对上。
 */
internal object JwxtChanges {

    fun apply(rows: List<JwxtRow>, changes: List<JwxtChange>): SourceSchedule {
        val result = rows.toMutableList()
        val events = mutableListOf<ScheduleChangeEvent>()
        for (c in changes.sortedBy { it.appliedAt }) {
            var origin: CourseItem? = null
            if (c.kind != ScheduleChangeEvent.Kind.ADDED) {
                val hits = result.filter { it.jxbid == c.jxbid && it.course.covers(c) }
                origin = hits.firstOrNull()?.course
                hits.forEach { hit ->
                    result.remove(hit)
                    result += carve(hit.course, c).map { JwxtRow(hit.jxbid, it) }
                }
            }
            if (c.kind != ScheduleChangeEvent.Kind.CANCELLED && c.toDay in 1..7 && c.toStart > 0 &&
                c.toEnd >= c.toStart && '1' in c.toWeeks
            ) {
                val base = origin ?: result.firstOrNull { it.jxbid == c.jxbid }?.course
                val teacher = if (base != null && c.toTeacher == c.teacher) base.teacher else c.toTeacher
                result += JwxtRow(
                    c.jxbid,
                    CourseItem(
                        courseName = c.courseName.ifBlank { base?.courseName.orEmpty() },
                        teacher = teacher.ifBlank { base?.teacher.orEmpty() },
                        location = c.toLocation.ifBlank { c.location }.ifBlank { base?.location.orEmpty() },
                        weekBits = c.toWeeks,
                        dayOfWeek = c.toDay,
                        startSection = c.toStart,
                        endSection = c.toEnd,
                        courseCode = c.courseCode.ifBlank { base?.courseCode.orEmpty() },
                        courseType = base?.courseType.orEmpty(),
                    ),
                )
            }
            events += c.toEvent()
        }
        return SourceSchedule(mergeSameSlot(result.map { it.course }), events)
    }

    private fun CourseItem.covers(c: JwxtChange): Boolean =
        dayOfWeek == c.day && startSection <= c.start && endSection >= c.end &&
            weekBits.indices.any { weekBits[it] == '1' && c.weeks.getOrNull(it) == '1' }

    /** 从 [course] 挖掉 [c] 那几周的那几节，剩下的拆成几条返回。 */
    private fun carve(course: CourseItem, c: JwxtChange): List<CourseItem> {
        val len = maxOf(course.weekBits.length, c.weeks.length)
        fun bits(keep: (Boolean, Boolean) -> Boolean) = String(CharArray(len) { i ->
            if (keep(course.weekBits.getOrNull(i) == '1', c.weeks.getOrNull(i) == '1')) '1' else '0'
        })
        val kept = bits { own, gone -> own && !gone }
        val removed = bits { own, gone -> own && gone }
        return buildList {
            if ('1' in kept) add(course.copy(weekBits = kept))
            if (course.startSection < c.start) add(course.copy(endSection = c.start - 1, weekBits = removed))
            if (course.endSection > c.end) add(course.copy(startSection = c.end + 1, weekBits = removed))
        }
    }

    /** 除周次外完全相同的几条合成一条，比如同一门课两次临时换到同一间教室。 */
    private fun mergeSameSlot(courses: List<CourseItem>): List<CourseItem> =
        courses.groupBy { it.copy(weekBits = "") }.map { (_, group) ->
            if (group.size == 1) return@map group.single()
            val len = group.maxOf { it.weekBits.length }
            group.first().copy(weekBits = String(CharArray(len) { i -> if (group.any { it.weekBits.getOrNull(i) == '1' }) '1' else '0' }))
        }

    private fun JwxtChange.toEvent() = ScheduleChangeEvent(
        courseName = courseName,
        courseCode = courseCode,
        kind = kind,
        fromDay = day,
        fromStartSection = start,
        fromEndSection = end,
        toDay = toDay,
        toStartSection = toStart,
        toEndSection = toEnd,
        toLocation = toLocation,
        weeks = weekList(weeks),
        toWeeks = weekList(toWeeks),
    )

    private fun weekList(bits: String) = bits.mapIndexedNotNull { i, ch -> if (ch == '1') i + 1 else null }
}
