package com.xjtu.toolbox.schedule

import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * 用户自建的一条日程/课程（手动添加的实验课、临时排课，以及屁岱代加的日程）。
 *
 * ## 与 `:app` 的 `CustomCourseEntity` 是什么关系
 *
 * 字段**一个不差**，差的是注解：那张表是 Room 的实体（`@Entity(tableName = "custom_courses")`），
 * 而 Room 注解是 Android 专属的 —— 屏与 ViewModel 进了 `:core` 之后就看不见它了。
 * 所以这里放**领域模型**，`:app` 留 Room 实体与两张之间的逐字段映射
 * （见 `:app` 的 `CustomCourseEntity.toCustomCourse()` / `CustomCourse.toEntity()`）：
 * **同一张表、同一批列名、同一份数据**，只是「屏说什么」与「表怎么存」分成两件事。
 *
 * 谁能直接读写表：`ScheduleWidgetProvider` / `HomeTab` / `AgentTool` / `AccountManager` 四处
 * 仍走 `CustomCourseDao`（一行未改，它们本来就在 `:app`）。屏与 VM 走 [CustomCourseStore] 这条缝。
 */
@Serializable
data class CustomCourse(
    /** 表里的自增主键；0 = 还没落库（新增）。 */
    val id: Long = 0,
    /** 所属账号 ID（学号/手机号）。多账号隔离用。 */
    val accountId: String = "",
    val courseName: String,
    val teacher: String = "",
    val location: String = "",
    /** 上课周次位图, e.g. "01111111111111111100"（第1位=第1周） */
    val weekBits: String,
    /** 星期几 1-7 (1=周一) */
    val dayOfWeek: Int,
    /** 开始节次 */
    val startSection: Int,
    /** 结束节次 */
    val endSection: Int,
    /** 分钟级开始时间，单位：距 00:00 的分钟；-1 表示使用节次推导 */
    val startMinuteOfDay: Int = -1,
    /** 分钟级结束时间，单位：距 00:00 的分钟；-1 表示使用节次推导 */
    val endMinuteOfDay: Int = -1,
    /** 所属学期代码, e.g. "2024-2025-2" */
    val termCode: String,
    /** 备注信息 */
    val note: String = "",
    /** 创建时间戳 */
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
) {
    /** 转为 CourseItem（与 API 课程统一展示） */
    fun toCourseItem(): CourseItem = CourseItem(
        courseName = courseName,
        teacher = teacher,
        location = location,
        weekBits = weekBits,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        courseCode = "$CUSTOM_COURSE_CODE_PREFIX$id",
        courseType = if (note.startsWith(AGENDA_NOTE_PREFIX)) "日程" else "自定义",
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay
    )
}

const val AGENDA_NOTE_PREFIX = "[AGENDA]"

fun encodeAgendaNote(note: String): String {
    val trimmed = note.trim()
    return if (trimmed.startsWith(AGENDA_NOTE_PREFIX)) trimmed else "$AGENDA_NOTE_PREFIX$trimmed"
}

fun decodeAgendaNote(note: String): String = note.removePrefix(AGENDA_NOTE_PREFIX).trim()

/**
 * 将诸如 "第1-4，6-16周"、"1,2,4-10" 等不规则字符串解析为周次列表
 * 自建课程落库模型。
 */
fun parseWeeksString(weeksStr: String): List<Int> {
    if (weeksStr.isBlank()) return emptyList()
    var s = weeksStr.trim().replace("第", "").replace("周", "")
    val seps = arrayOf("，", "、", ";", "；", " ")
    for (sep in seps) {
        s = s.replace(sep, ",")
    }
    val parts = s.split(",").filter { it.isNotBlank() }
    val weeks = mutableSetOf<Int>()
    for (p in parts) {
        val part = p.trim()
        if (part.isBlank()) continue
        if ("-" in part) {
            val range = part.split("-", limit = 2)
            val start = range.getOrNull(0)?.toIntOrNull()
            val end = range.getOrNull(1)?.toIntOrNull()
            if (start != null && end != null) {
                val s1 = minOf(start, end)
                val e1 = maxOf(start, end)
                weeks.addAll(s1..e1)
            }
        } else {
            part.toIntOrNull()?.let { weeks.add(it) }
        }
    }
    return weeks.sorted()
}
