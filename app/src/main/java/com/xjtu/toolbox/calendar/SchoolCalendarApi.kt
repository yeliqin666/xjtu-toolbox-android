package com.xjtu.toolbox.calendar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.network.HttpClients
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.DayOfWeek
import java.time.LocalDate

private const val CALENDAR_URL = "https://workflow.xjtu.edu.cn/selectpage/site/calendar/getData"

/** 校历事件（假期/重要节点） */
data class CalendarEvent(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val name: String,
    val remark: String,
    val days: Int,
    val colorHex: String
)

/** 学期校历数据 */
data class SchoolTerm(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val termName: String,    // e.g. "2025-2026学年第一学期"
    val yearName: String,    // e.g. "2025-2026"
    val totalWeeks: Int,
    val workDays: Int,
    val events: List<CalendarEvent>
) {
    /** 计算今天是第几学习周（1-based），不在学期内返回 0 */
    fun currentWeek(today: LocalDate = LocalDate.now()): Int {
        if (today < startDate || today > endDate) return 0
        return ((today.toEpochDay() - startDate.toEpochDay()) / 7 + 1).toInt()
    }

    /** 计算今天是本学期第几天 */
    fun currentDay(today: LocalDate = LocalDate.now()): Int {
        if (today < startDate) return 0
        return (today.toEpochDay() - startDate.toEpochDay() + 1).toInt()
    }

    /** 学期总天数 */
    fun totalDays(): Int = (endDate.toEpochDay() - startDate.toEpochDay() + 1).toInt()

    /** 剩余天数 */
    fun daysRemaining(today: LocalDate = LocalDate.now()): Int {
        if (today > endDate) return 0
        val from = if (today < startDate) startDate else today
        return (endDate.toEpochDay() - from.toEpochDay()).toInt()
    }

    /** 学期进度 (0f ~ 1f) */
    fun progress(today: LocalDate = LocalDate.now()): Float {
        if (today <= startDate) return 0f
        if (today >= endDate) return 1f
        val total = totalDays().toFloat()
        val elapsed = currentDay(today).toFloat()
        return (elapsed / total).coerceIn(0f, 1f)
    }

    /** 今天所在的事件（假期/节日/考试周等），可能为 null */
    fun todayEvent(today: LocalDate = LocalDate.now()): CalendarEvent? {
        return events.firstOrNull { today >= it.startDate && today <= it.endDate }
    }
}

/**
 * 校历数据源：`workflow.xjtu.edu.cn` 的工作流门户首页小组件用的公开接口，不需要登录、
 * 不需要 CAS/SSO——这也是它被选中的原因：原来那套走 EIP 门户（one2020.xjtu.edu.cn）
 * 的方案，实测证明 jwxt 的登录态没法 SSO 到 EIP，接口没会话时也照样答 code=200 data=[]，
 * 逼用户重登完全没用（详见 git log 里这个文件的历史）。这个接口从抓包直接对上：
 * `GET /selectpage/site/calendar/getData`，响应外层是 `{e, d, m}`（e=0 成功），
 * `d.semesters[]` 每项一个学期，`holidays[]` 是有起止日期的假期/节点，`specialEvents[]`
 * 是按标题对应的详细说明文字（不是所有 holiday 都有对应的 specialEvent）。
 */
class SchoolCalendarApi {
    /** 默认配置即可，直接用共享基础客户端，见 [HttpClients]。 */
    private val client: OkHttpClient = HttpClients.base

    fun getTerms(): List<SchoolTerm> {
        val request = Request.Builder().url(CALENDAR_URL).get().build()
        val body = client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: throw RuntimeException("校历接口无响应")
        }
        val json = JsonParser.parseString(body).asJsonObject
        val code = json.get("e")?.asInt ?: -1
        if (code != 0) throw RuntimeException("校历接口返回异常：${json.get("m")?.asString}")
        val data = json.getAsJsonObject("d") ?: throw RuntimeException("校历接口缺少数据")
        val semesters = data.getAsJsonArray("semesters") ?: return emptyList()
        return semesters.mapNotNull { runCatching { parseSemester(it.asJsonObject) }.getOrNull() }
            .sortedBy { it.startDate }
    }

    private fun parseSemester(obj: JsonObject): SchoolTerm? {
        val start = obj.get("start_date")?.asString?.let { parseDateOrNull(it) } ?: return null
        // 学期"结束"取考试周结束日（含教学+考试），没有就退到教学结束日，
        // 再没有才用 end_date（那个其实是到下学期开学前，含整个寒暑假，会把"进度条"拉得没意义）。
        val end = firstValidDate(obj, "exam_end", "term_end_date", "end_date") ?: return null

        val specialByTitle = obj.getAsJsonArray("specialEvents")?.associate { el ->
            val e = el.asJsonObject
            e.get("title")?.asString.orEmpty() to e.get("content")?.asString.orEmpty()
        }.orEmpty()

        val events = obj.getAsJsonArray("holidays")?.mapNotNull { el ->
            val h = el.asJsonObject
            val hStart = h.get("start_date")?.asString?.let { parseDateOrNull(it) } ?: return@mapNotNull null
            val hEnd = h.get("end_date")?.asString?.let { parseDateOrNull(it) } ?: hStart
            val title = h.get("title")?.asString.orEmpty()
            CalendarEvent(
                id = "$title-$hStart",
                startDate = hStart,
                endDate = hEnd,
                name = title,
                remark = specialByTitle[title].orEmpty(),
                days = (hEnd.toEpochDay() - hStart.toEpochDay() + 1).toInt(),
                colorHex = "#196dd0",
            )
        }.orEmpty().sortedBy { it.startDate }

        val totalWeeks = ((end.toEpochDay() - start.toEpochDay()) / 7).toInt().coerceAtLeast(0)
        val workDays = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .count { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

        val year = obj.get("year")?.asString.orEmpty()
        val semesterName = obj.get("name")?.asString.orEmpty()
        return SchoolTerm(
            id = obj.get("id")?.asString.orEmpty(),
            startDate = start,
            endDate = end,
            termName = "${year}学年$semesterName",
            yearName = year,
            totalWeeks = totalWeeks,
            workDays = workDays,
            events = events,
        )
    }

    private fun firstValidDate(obj: JsonObject, vararg keys: String): LocalDate? {
        for (key in keys) {
            obj.get(key)?.asString?.let { parseDateOrNull(it) }?.let { return it }
        }
        return null
    }

    /** 接口用 "0000-00-00" 表示字段没填，不是合法日期。 */
    private fun parseDateOrNull(value: String): LocalDate? {
        if (value.isBlank() || value == "0000-00-00") return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }
}
