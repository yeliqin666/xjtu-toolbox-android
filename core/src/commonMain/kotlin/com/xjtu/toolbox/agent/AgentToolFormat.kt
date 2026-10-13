package com.xjtu.toolbox.agent

import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.formatTwoDigits
/**
 * 工具结果里那几段**纯格式化** —— 一行平台代码都没有，所以从 `:app` 的 `AgentToolRegistry`
 * 搬进 `:core`（逐字，只有一处接口适配：见 [agentCourseLine] 的 `isSummer`）。
 *
 * 与取数分开的理由很简单：这些是「拿到数据之后怎么写成给模型看的话」，四端的口径必须一致；
 * 而它们一旦留在 `:app`，将来桌面端点亮 Agent 时就要抄一份同样的文案。
 */

/**
 * 一条课一行。两处接口适配：原来的签名收 `java.time.LocalDate`（`:core` 没有这个类型），
 * 改收 `XjtuTime.isSummerTime` 已经判好的 `isSummer`（调用点那两处本来就是
 * `XjtuTime.isSummerTime(date.monthValue)`，换算一步都没多）；`"%02d".format(…)`（JVM 专属）
 * 换成 `:core` 既有的 [formatTwoDigits]，补零口径相同。
 */
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

/**
 * 活动的时间尾巴：作业报「截止」（优先上游 `deadline`，它才是老师设的那个时间，实测 4.6%
 * 与 `end_time` 不同，且都是 `end_time` 比它早），其余类型报「结束」（下课/回放结束，不是截止）。
 */
fun agentLmsTimeSuffix(a: LmsActivity): String {
    val isHw = a.type == LmsActivityType.HOMEWORK
    val raw = if (isHw) (a.deadline ?: a.endTime) else a.endTime
    if (raw.isNullOrBlank()) return ""
    return if (isHw) "（截止 $raw）" else "（结束 $raw）"
}
