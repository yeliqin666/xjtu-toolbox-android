package com.xjtu.toolbox.schedule

import kotlinx.serialization.Serializable

// ── 共享常量 ──────────────────────────────
//
// 这三个常量是**小时**语义的（不是网格行数）：自定义日程编辑器、Agent 的冲突判定、
// 日程详情文案都在用。它们从 :app 的 ScheduleComponents.kt 搬进 :core —— 因为
// CourseItem.clockMinutes 也要用，而常量跟模型分居两个模块只会让口径漂移。

const val DAY_START_HOUR = 8
const val DAY_END_HOUR = 22
const val MAX_SECTIONS = DAY_END_HOUR - DAY_START_HOUR

/**
 * 用户自己建的日程的课程号前缀。
 *
 * 从 :app 的 `CustomCourseEntity.kt` 搬来（Room 实体本身留在 androidMain 那边），
 * 因为 [CourseItem.isUserCreated] 要用它，而 CourseItem 现在在 commonMain。
 */
const val CUSTOM_COURSE_CODE_PREFIX = "custom_"

// ── 通用课格接口 ─────────────────────────

/**
 * 网格能画的一条东西。刻意只留「展示 + 定位」需要的字段，所以自定义日程、体育课、
 * 教务课都能实现它 —— 网格本身不必认识任何一方的模型。
 */
interface ScheduleSlot {
    val slotName: String
    val slotLocation: String
    val slotDayOfWeek: Int
    val slotStartSection: Int
    val slotEndSection: Int
}

/**
 * 一节课/一条日程。从 :app 的 `ScheduleApi.kt` 里原样切出来的 —— 它跟 HTTP 无关，
 * 只被网格、冲突判定、导出、Agent 共用，所以它属于共享层，而 `ScheduleApi` 继续留在
 * :app（它还挂着 okhttp / jsoup / java.time）。
 *
 * ⚠️ 与 [CourseSlot] 的关系：那边是 campus-api / Web 端用的**新模型**（周次已展开、
 * 教室与校区分开）；这边是 Android 侧的历史形状（`weekBits` 位串、`location` 不分）。
 * 交接文档已把 `weekBits` 标成要退役的东西，收敛发生在 :app 的搬屏过程中，不在这一批。
 */
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

    /**
     * 用户自己建的日程（日程页手动添加、屁岱代加），不是教务排的课。
     * 法定假日停的是课，自建日程节假日过滤一律不碰。
     */
    val isUserCreated: Boolean get() = courseCode.startsWith(CUSTOM_COURSE_CODE_PREFIX)

    /**
     * 这一条在某套作息下的真实起止（距 00:00 的分钟，结束不含）。
     *
     * - 给了合法钟点（00:00 ≤ 开始 < 结束 ≤ 24:00）就用钟点；
     * - 自建日程没存钟点（老版本编辑器建的）：节次当年是按「8 点起每小时一节」推的，照此还原，
     *   和编辑器打开它时显示的时间一致；
     * - 其余按节次查作息表，要按哪套作息由条目所在的日期决定（[XjtuTime.isSummerTime]）。
     *
     * 节次超出范围的夹到首末节，结束节早于开始节的按开始节算。
     */
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
