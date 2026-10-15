package com.xjtu.toolbox.agent

import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.formatTwoDigits
/** 工具结果里那几段纯格式化。 */

/** 一条课一行。 */
fun agentCourseLine(c: CourseItem, isSummer: Boolean, withTeacher: Boolean = false): String {
    fun hhmm(min: Int) = "${formatTwoDigits(min / 60)}:${formatTwoDigits(min % 60)}"
    val (start, end) = c.clockMinutes(isSummer)
    val days = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val sections = if (c.isUserCreated) "" else "（${c.startSection}–${c.endSection}节）"
    return listOfNotNull(
        c.courseName,
        "${days.getOrElse(c.dayOfWeek) { "" }} ${hhmm(start)}–${hhmm(end)}$sections".trim(),
        c.location.ifBlank { null },
        c.teacher.takeIf { withTeacher && it.isNotBlank() },
    ).joinToString("｜")
}

/** 整学期列表里给每条标上周次：`第1-4、6-16周 `。周次拿不到就不标。 */
fun agentWeekRange(c: CourseItem): String {
    val weeks = c.getWeeks()
    if (weeks.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var i = 0
    while (i < weeks.size) {
        var j = i
        while (j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
        parts += if (j > i) "${weeks[i]}-${weeks[j]}" else "${weeks[i]}"
        i = j + 1
    }
    return "第${parts.joinToString("、")}周 "
}

/** 调课记录那句话的挂法：没有就不挂。 */
fun String.withAgentChangeNote(note: String?): String = if (note.isNullOrBlank()) this else "$this\n$note"

/** 思源学堂活动类型的显示名。 */
fun agentLmsTypeName(t: LmsActivityType): String = when (t) {
    LmsActivityType.HOMEWORK -> "作业"
    LmsActivityType.MATERIAL -> "课件"
    LmsActivityType.LESSON -> "课程/回放"
    LmsActivityType.LECTURE_LIVE -> "直播/回放"
    LmsActivityType.PAGE -> "页面"
    LmsActivityType.FORUM -> "讨论区"
    LmsActivityType.QUESTIONNAIRE -> "问卷"
    LmsActivityType.ONLINE_VIDEO -> "在线视频"
    else -> "其他"
}

/** 活动的时间尾巴：作业报「截止」（优先上游 `deadline`，它与 `end_time` 不总相同），其余报「结束」。 */
fun agentLmsTimeSuffix(a: LmsActivity): String {
    val isHw = a.type == LmsActivityType.HOMEWORK
    val raw = if (isHw) (a.deadline ?: a.endTime) else a.endTime
    if (raw.isNullOrBlank()) return ""
    return if (isHw) "（截止 $raw）" else "（结束 $raw）"
}
