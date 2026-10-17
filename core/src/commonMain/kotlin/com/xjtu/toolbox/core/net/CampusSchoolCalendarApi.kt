package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.calendar.CalendarEvent
import com.xjtu.toolbox.calendar.SchoolCalendarSource
import com.xjtu.toolbox.calendar.SchoolTerm
import com.xjtu.toolbox.calendar.countWorkDays
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 校历的 campus-api 版取数：给 Web 端用（Android 端直连学校，见 `:app` 的 `SchoolCalendarApi`）。 */
class CampusSchoolCalendarApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.2 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : SchoolCalendarSource {

    override suspend fun terms(): List<SchoolTerm> {
        if (mode == ApiMode.SERVE) return serveTerms()
        val first = fetch(term = null)
        val current = first.semester ?: error("campus-api 校历返回缺少 semester")
        val others = first.availableTerms
            .filter { it != first.term }
            .mapNotNull { runCatching { fetch(it).semester }.getOrNull() }
        return (listOf(current) + others).distinctBy { it.id }.sortedBy { it.startDate }
    }

    /** serve 模式（契约 §5.2） */
    private suspend fun serveTerms(): List<SchoolTerm> {
        val data = client.serveData("加载校历", baseUrl, "/api/calendar/school")
        return data.arr("terms").orEmpty().mapNotNull { element ->
            val term = element as? JsonObject ?: return@mapNotNull null
            val start = parseDateOrNull(term["startDate"].safeString()) ?: return@mapNotNull null
            val end = parseDateOrNull(term["endDate"].safeString()) ?: return@mapNotNull null
            SchoolTerm(
                id = term["id"].safeString(),
                startDate = start,
                endDate = end,
                termName = term["termName"].safeString(),
                yearName = term["yearName"].safeString(),
                totalWeeks = term["totalWeeks"].safeInt(),
                workDays = term["workDays"].safeInt(),
                events = term.arr("events").orEmpty().mapNotNull { eventElement ->
                    val event = eventElement as? JsonObject ?: return@mapNotNull null
                    val eventStart = parseDateOrNull(event["startDate"].safeString()) ?: return@mapNotNull null
                    CalendarEvent(
                        id = event["id"].safeString(),
                        startDate = eventStart,
                        endDate = parseDateOrNull(event["endDate"].safeString()) ?: eventStart,
                        name = event["name"].safeString(),
                        remark = event["remark"].safeString(),
                        days = event["days"].safeInt(),
                        colorHex = event["colorHex"].safeString(),
                    )
                },
            )
        }
    }


    private suspend fun fetch(term: String?): Payload {
        val text = client.get("$baseUrl/api/calendar/school") {
            if (term != null) parameter("term", term)
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 校历返回不是 JSON 对象")
        val data = envelope["data"] as? JsonObject
            ?: error("campus-api 校历返回缺少 data：${text.take(120)}")
        val semesterObj = data["semester"] as? JsonObject
            ?: error("campus-api 校历返回缺少 semester：${text.take(120)}")
        val available = data.arr("availableTerms")?.map { it.safeString() }.orEmpty()
        val semester = parseSemester(semesterObj)
            ?: error("campus-api 校历的学期缺少起止日期")
        return Payload(
            term = data["term"].safeString(),
            availableTerms = available,
            semester = semester,
        )
    }

    private data class Payload(val term: String, val availableTerms: List<String>, val semester: SchoolTerm)

    /** 把 campus-api 的 `semester` 映射成共享模型。 */
    private fun parseSemester(obj: JsonObject): SchoolTerm? {
        val start = parseDateOrNull(obj["startDate"].safeString()) ?: return null
        val end = firstValidDate(obj, "examEnd", "termEndDate", "endDate") ?: return null

        val specialByTitle = obj.arr("specialEvents")?.associate { el ->
            val e = el.jsonObject
            e["title"].safeString() to e["content"].safeString()
        }.orEmpty()

        val events = obj.arr("holidays")?.mapNotNull { el ->
            val h = el.jsonObject
            val hStart = parseDateOrNull(h["startDate"].safeString()) ?: return@mapNotNull null
            val hEnd = parseDateOrNull(h["endDate"].safeString()) ?: hStart
            val title = h["title"].safeString()
            val days = if (h["days"].isNull) {
                (hEnd.toEpochDays() - hStart.toEpochDays() + 1).toInt()
            } else {
                h["days"].intValue
            }
            CalendarEvent(
                id = "$title-$hStart",
                startDate = hStart,
                endDate = hEnd,
                name = title,
                remark = specialByTitle[title].orEmpty(),
                days = days,
                colorHex = "#196dd0",
            )
        }.orEmpty().sortedBy { it.startDate }

        val year = obj["year"].safeString()
        val totalWeeks = ((end.toEpochDays() - start.toEpochDays()) / 7).toInt().coerceAtLeast(0)
        return SchoolTerm(
            id = obj["id"].takeUnless { it.isNull }?.safeInt()?.toString().orEmpty(),
            startDate = start,
            endDate = end,
            termName = "${year}学年${obj["name"].safeString()}",
            yearName = year,
            totalWeeks = totalWeeks,
            workDays = countWorkDays(start, end),
            events = events,
        )
    }

    private fun firstValidDate(obj: JsonObject, vararg keys: String): LocalDate? {
        for (key in keys) {
            parseDateOrNull(obj[key].safeString())?.let { return it }
        }
        return null
    }

    /** campus-api 已把 `0000-00-00` 归一成 `null`，所以这里只需要挡住空串与畸形值。 */
    private fun parseDateOrNull(value: String): LocalDate? {
        if (value.isBlank()) return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }
}
