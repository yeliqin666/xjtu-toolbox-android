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

/**
 * 校历的 **campus-api 版取数**：给 Web 端用（Android 端直连学校，见 `:app` 的 `SchoolCalendarApi`）。
 *
 * 为什么 Web 不能直连学校：浏览器对 `workflow.xjtu.edu.cn` 的响应没有
 * `Access-Control-Allow-Origin`，campus-api 也不给零鉴权端点发 CORS 头。所以 Web 的唯一数据路径是
 * 同源反代到 campus-api —— 与黄页（[CampusYellowPageApi]）同一条理由、同一个形状。
 *
 * **上游是同一个门户接口**（campus-api 的 `source` 字段就是那个 URL），所以这里做的是
 * 「信封拆包 + 口径对齐」，不是重新实现一套解析：
 *  - 学期结束日同样取 `examEnd → termEndDate → endDate` 第一个有效的（`:core` 里
 *    [com.xjtu.toolbox.calendar.parseUpstreamSchoolCalendar] 对学校响应也是这个次序）；
 *  - `termName` = `"${year}学年${name}"`；
 *  - 假期备注同样按标题在 `specialEvents[]` 里找（上游就是这么关联的）；
 *  - 事件/学期的排序同样按 `startDate` 升序 —— **顺序是屏幕「学期切换」的前提**。
 *
 * campus-api 一次只回一个学期（`semester`），但它同时给出 `availableTerms[]`，所以这里把每个
 * 可查学期各拉一次拼成完整列表；取不到的学期（上游还没发布）按校历模块自己的口径
 * `code:1 / msg:"not-found"` 返回，直接跳过、不影响别的学期。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusSchoolCalendarApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : SchoolCalendarSource {

    override suspend fun terms(): List<SchoolTerm> {
        val first = fetch(term = null)
        val current = first.semester ?: error("campus-api 校历返回缺少 semester")
        val others = first.availableTerms
            .filter { it != first.term }
            .mapNotNull { runCatching { fetch(it).semester }.getOrNull() }
        return (listOf(current) + others).distinctBy { it.id }.sortedBy { it.startDate }
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

    /**
     * 把 campus-api 的 `semester` 映射成共享模型。字段名与黄页那套一样，**不做重命名**：
     * 上游怎么叫就怎么读，映射关系写在 [SchoolTerm] 的构造里，一眼能对回去。
     */
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
