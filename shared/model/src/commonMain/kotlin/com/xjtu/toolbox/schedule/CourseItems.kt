package com.xjtu.toolbox.schedule

import kotlinx.serialization.Serializable


const val DAY_START_HOUR = 8
const val DAY_END_HOUR = 22
const val MAX_SECTIONS = DAY_END_HOUR - DAY_START_HOUR

/** 用户自己建的日程的课程号前缀。 */
const val CUSTOM_COURSE_CODE_PREFIX = "custom_"

// ── 通用课格接口 ─────────────────────────

/** 网格能画的一条东西。 */
interface ScheduleSlot {
    val slotName: String
    val slotLocation: String
    val slotDayOfWeek: Int
    val slotStartSection: Int
    val slotEndSection: Int
}

/** 一节课/一条日程。 */
@Serializable
data class CourseItem(
    val courseName: String = "",
    val teacher: String = "",
    val location: String = "",
    val weekBits: String = "",
    val dayOfWeek: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    val courseCode: String = "",
    val courseType: String = "",
    /** 分钟级开始时间（距 00:00 的分钟），只有自建日程填；-1 表示未提供 */
    val startMinuteOfDay: Int = -1,
    /** 分钟级结束时间（距 00:00 的分钟），只有自建日程填；-1 表示未提供 */
    val endMinuteOfDay: Int = -1
) : ScheduleSlot {
    override val slotName get() = courseName
    override val slotLocation get() = location
    override val slotDayOfWeek get() = dayOfWeek
    override val slotStartSection get() = startSection
    override val slotEndSection get() = endSection

    fun getWeeks(): List<Int> = weekBits.mapIndexedNotNull { index, c -> if (c == '1') index + 1 else null }

    fun isInWeek(week: Int): Boolean {
        val idx = week - 1
        return idx in weekBits.indices && weekBits[idx] == '1'
    }

    /** 用户自己建的日程（日程页手动添加、屁岱代加），不是教务排的课。 */
    val isUserCreated: Boolean get() = courseCode.startsWith(CUSTOM_COURSE_CODE_PREFIX)

    /** 这一条在某套作息下的真实起止（距 00:00 的分钟，结束不含）。 */
    fun clockMinutes(summer: Boolean): Pair<Int, Int> {
        if (startMinuteOfDay in 0 until MINUTES_PER_DAY && endMinuteOfDay in (startMinuteOfDay + 1)..MINUTES_PER_DAY) {
            return startMinuteOfDay to endMinuteOfDay
        }
        if (isUserCreated) {
            val start = startSection.coerceIn(1, MAX_SECTIONS)
            val end = endSection.coerceIn(start, MAX_SECTIONS)
            return (DAY_START_HOUR + start - 1) * 60 to (DAY_START_HOUR + end) * 60
        }
        val sections = XjtuTime.getAllTimes(summer)
        val first = sections.first().first
        val last = sections.last().first
        val start = startSection.coerceIn(first, last)
        val end = endSection.coerceIn(start, last)
        val startTime = XjtuTime.getClassTime(start, summer)!!.start
        val endTime = XjtuTime.getClassTime(end, summer)!!.end
        return startTime.hour * 60 + startTime.minute to endTime.hour * 60 + endTime.minute
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
