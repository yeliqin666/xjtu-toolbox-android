package com.xjtu.toolbox.calendar

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.stringValue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 校历上游（`workflow.xjtu.edu.cn/selectpage/site/calendar/getData`）的纯解析。 */
fun parseUpstreamSchoolCalendar(body: String): List<SchoolTerm> {
    val json = AppJson.parseToJsonElement(body).jsonObject
    val code = json.get("e")?.intValue ?: -1
    if (code != 0) throw IllegalStateException("校历接口返回异常：${json.get("m")?.stringValue}")
    val data = json.obj("d") ?: throw IllegalStateException("校历接口缺少数据")
    val semesters = data.arr("semesters") ?: return emptyList()
    return semesters.mapNotNull { runCatching { parseSemester(it.jsonObject) }.getOrNull() }
        .sortedBy { it.startDate }
}

private fun parseSemester(obj: JsonObject): SchoolTerm? {
    val start = obj.get("start_date")?.stringValue?.let { parseDateOrNull(it) } ?: return null
    // 学期"结束"取考试周结束日（含教学+考试），没有就退到教学结束日，
    // 再没有才用 end_date（那个其实是到下学期开学前，含整个寒暑假，会把"进度条"拉得没意义）。
    val end = firstValidDate(obj, "exam_end", "term_end_date", "end_date") ?: return null

    val specialByTitle = obj.arr("specialEvents")?.associate { el ->
        val e = el.jsonObject
        e.get("title")?.stringValue.orEmpty() to e.get("content")?.stringValue.orEmpty()
    }.orEmpty()

    val events = obj.arr("holidays")?.mapNotNull { el ->
        val h = el.jsonObject
        val hStart = h.get("start_date")?.stringValue?.let { parseDateOrNull(it) } ?: return@mapNotNull null
        val hEnd = h.get("end_date")?.stringValue?.let { parseDateOrNull(it) } ?: hStart
        val title = h.get("title")?.stringValue.orEmpty()
        CalendarEvent(
            id = "$title-$hStart",
            startDate = hStart,
            endDate = hEnd,
            name = title,
            remark = specialByTitle[title].orEmpty(),
            days = (hEnd.toEpochDays() - hStart.toEpochDays() + 1).toInt(),
            colorHex = "#196dd0",
        )
    }.orEmpty().sortedBy { it.startDate }

    val totalWeeks = ((end.toEpochDays() - start.toEpochDays()) / 7).toInt().coerceAtLeast(0)
    val workDays = countWorkDays(start, end)

    val year = obj.get("year")?.stringValue.orEmpty()
    val semesterName = obj.get("name")?.stringValue.orEmpty()
    return SchoolTerm(
        id = obj.get("id")?.stringValue.orEmpty(),
        startDate = start,
        endDate = end,
        termName = "${year}学年$semesterName",
        yearName = year,
        totalWeeks = totalWeeks,
        workDays = workDays,
        events = events,
    )
}

/** `start..end`（闭区间）里的工作日数。 */
fun countWorkDays(start: LocalDate, end: LocalDate): Int {
    var day = start
    var count = 0
    while (day <= end) {
        if (!day.isWeekend()) count++
        day = day.plusDays(1)
    }
    return count
}

private fun firstValidDate(obj: JsonObject, vararg keys: String): LocalDate? {
    for (key in keys) {
        obj.get(key)?.stringValue?.let { parseDateOrNull(it) }?.let { return it }
    }
    return null
}

/** 接口用 "0000-00-00" 表示字段没填，不是合法日期。 */
private fun parseDateOrNull(value: String): LocalDate? {
    if (value.isBlank() || value == "0000-00-00") return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
}
