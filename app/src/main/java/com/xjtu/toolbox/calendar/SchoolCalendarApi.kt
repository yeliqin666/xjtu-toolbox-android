package com.xjtu.toolbox.calendar

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

private const val TAG = "SchoolCalendarApi"
private const val BASE_URL = "http://one2020.xjtu.edu.cn"

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

class SchoolCalendarApi(private val site: SiteSession? = null) {
    private val client: OkHttpClient = site?.client ?: OkHttpClient()

    /**
     * 获取全部学期校历列表
     * 先访问 showCalendar.htm 建立 EIP 门户 Session（利用 CAS TGC 自动完成 SSO），
     * 再调用 terms.htm 获取结构化数据
     */
    fun getTerms(): List<SchoolTerm> {
        // 建立 EIP portal JSESSIONID（CAS SSO 自动走）
        val initReq = Request.Builder()
            .url("$BASE_URL/EIP/edu/education/schoolcalendar/showCalendar.htm")
            .get()
            .build()
        try {
            execute(initReq).use { resp ->
                Log.d(TAG, "init page: ${resp.code} -> ${resp.request.url}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "init page failed (continuing): ${e.message}")
        }

        val request = Request.Builder()
            .url("$BASE_URL/EIP/schoolcalendar/terms.htm")
            .post("".toRequestBody())
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/EIP/edu/education/schoolcalendar/showCalendar.htm")
            .build()

        val body = execute(request).use { resp ->
            resp.body?.string() ?: throw RuntimeException("校历接口无响应")
        }
        Log.d(TAG, "terms response (${body.length}): ${body.take(200)}")

        val json = JsonParser.parseString(body).asJsonObject
        val code = json.get("code")?.asInt ?: -1
        if (code != 200) throw RuntimeException("校历接口返回 code=$code: ${json.get("msg")?.asString}")

        return json.getAsJsonArray("data")
            .map { parseTerm(it.asJsonObject) }
            .sortedBy { it.startDate }
    }

    private fun execute(request: Request) =
        site?.let { runBlocking { it.executeWithReAuth(request) } }
            ?: client.newCall(request).execute()

    private fun parseTerm(obj: JsonObject): SchoolTerm {
        val events = obj.getAsJsonArray("holidays")
            ?.mapNotNull { runCatching { parseEvent(it.asJsonObject) }.getOrNull() }
            ?.sortedBy { it.startDate }
            ?: emptyList()
        return SchoolTerm(
            id = obj.get("id")?.asString ?: "",
            startDate = LocalDate.parse(obj.get("start_date").asString),
            endDate = LocalDate.parse(obj.get("end_date").asString),
            termName = obj.get("term_num")?.asString ?: "",
            yearName = obj.get("year_num")?.asString ?: "",
            totalWeeks = obj.get("week_number")?.asString?.toIntOrNull() ?: 0,
            workDays = obj.get("work_days")?.asInt ?: 0,
            events = events
        )
    }

    private fun parseEvent(obj: JsonObject) = CalendarEvent(
        id = obj.get("id")?.asString ?: "",
        startDate = LocalDate.parse(obj.get("start_date").asString),
        endDate = LocalDate.parse(obj.get("end_date").asString),
        name = obj.get("holiday_name")?.asString ?: "",
        remark = obj.get("holiday_remark")?.asString ?: "",
        days = obj.get("holiday_days")?.asString?.toIntOrNull() ?: 0,
        colorHex = obj.get("holiday_color")?.asString ?: "#196dd0"
    )
}
