package com.xjtu.toolbox.schedule

import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.JsLogin
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 智慧教室平台（js.xjtu.edu.cn）的课表，接口照搬 XJTUToolBox 的 `JsSession.get_schedule_lessons`：
 *
 * `POST /server/onlineSchedule/dataList`，body
 * `{"jasmc":"","kcm":"","checkDate":"","xnxqdm":"2025-2026-2","skzc":"3"}`，
 * 教室名、课程名留空就是当前登录人自己的课表。一次一周：
 * `data.dataList` 是周一到周日 7 项，每项 `classInfo` 是逐节的格子
 * `{"classJc":节次,"classData":{"kcm":课程,"xm":教师,"jasmc":教室}|null}`。
 * 业务 code 0 = 有数据，400 = 这周没数据（上游的探活也是这么判的）。
 *
 * 没有抓到过这个接口的真实响应（抓包里只有空闲教室），字段全按上游代码取。
 * 所以解析上宁可保守：任何一周格式对不上就整体放弃，由 [ScheduleSourceRouter] 退回教务。
 *
 * 和上游的一处不同：上游把"同一天同一门课同一教室"的所有节次取 min/max 合成一段，
 * 一天里上下午各一次的课会被并成一整天。这里按连续节次切段。
 */
class JsScheduleApi(private val site: SiteSession) {

    private val jsonType = "application/json;charset=UTF-8".toMediaType()

    /** 整学期课表。一周一个请求，限 4 路并发，免得一次刷新对平台打出二十个并发。 */
    suspend fun getSchedule(termCode: String): List<CourseItem> = coroutineScope {
        val gate = Semaphore(4)
        suspend fun weeks(range: IntRange) = range.map { week ->
            async { gate.withPermit { week to queryWeek(termCode, week) } }
        }.awaitAll()

        val first = weeks(1..DEFAULT_WEEKS)
        // 学期偶尔超过 20 周（有补课周），最后两周还有课就往后多看几周
        val tail = if (first.takeLast(2).any { (_, cells) -> cells.isNotEmpty() }) {
            weeks(DEFAULT_WEEKS + 1..MAX_WEEKS)
        } else emptyList()
        val all = first + tail
        if (all.all { (_, cells) -> cells.isEmpty() }) return@coroutineScope emptyList()
        mergeJsWeeks(all)
    }

    private suspend fun queryWeek(termCode: String, week: Int): List<JsCell> {
        val payload = JsonObject().apply {
            addProperty("jasmc", "")
            addProperty("kcm", "")
            addProperty("checkDate", "")
            addProperty("xnxqdm", termCode)
            addProperty("skzc", week.toString())
        }.toString()
        val request = Request.Builder()
            .url("${JsLogin.BASE_URL}/server/onlineSchedule/dataList")
            .post(payload.toRequestBody(jsonType))
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", JsLogin.BASE_URL)
            .header("Referer", JsLogin.SERVICE_URL)
            .build()
        val body = site.executeWithReAuth(request).use { resp ->
            withContext(Dispatchers.IO) {
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw java.io.IOException("智慧教室课表第${week}周：HTTP ${resp.code}")
                text
            }
        }
        return parseJsWeek(body, week)
    }

    private companion object {
        const val DEFAULT_WEEKS = 20
        const val MAX_WEEKS = 26
    }
}

/** 一格：某周某天某节上的一门课。 */
internal data class JsCell(val day: Int, val section: Int, val name: String, val teacher: String, val location: String)

private const val MAX_SECTION = 14

/** 解析一周的 dataList 响应。格式对不上就抛，让路由器退回教务。 */
internal fun parseJsWeek(body: String, week: Int): List<JsCell> {
    val root = body.safeParseJsonObject()
    when (val code = root.get("code")?.takeIf { !it.isJsonNull }?.runCatching { asInt }?.getOrNull()) {
        0 -> Unit
        400 -> return emptyList()
        else -> throw java.io.IOException("智慧教室课表第${week}周：code=$code")
    }
    val days = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        ?.get("dataList")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: return emptyList()
    if (days.size() > 7) throw java.io.IOException("智慧教室课表第${week}周：一周给了 ${days.size()} 天")
    val cells = mutableListOf<JsCell>()
    days.forEachIndexed { index, dayEl ->
        val classInfo = dayEl.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("classInfo")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEachIndexed
        for (slotEl in classInfo) {
            val slot = slotEl.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val data = slot.get("classData")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val section = slot.str("classJc")?.toIntOrNull()
                ?: throw java.io.IOException("智慧教室课表第${week}周：节次缺失")
            if (section !in 1..MAX_SECTION) throw java.io.IOException("智慧教室课表第${week}周：节次 $section 不合理")
            val name = data.str("kcm")?.takeIf { it.isNotBlank() } ?: continue
            cells += JsCell(
                day = index + 1,
                section = section,
                name = name,
                teacher = data.str("xm").orEmpty(),
                location = data.str("jasmc").orEmpty(),
            )
        }
    }
    return cells
}

/**
 * 逐周的格子 → [CourseItem]。先在每周每天里把同一门课的连续节次切成段，
 * 再把"同课同教室同一天同一段"在哪些周出现过累成 weekBits。
 */
internal fun mergeJsWeeks(weekly: List<Pair<Int, List<JsCell>>>): List<CourseItem> {
    data class Key(val name: String, val teacher: String, val location: String, val day: Int, val start: Int, val end: Int)

    val maxWeek = weekly.filter { it.second.isNotEmpty() }.maxOf { it.first }
    val bits = linkedMapOf<Key, CharArray>()
    for ((week, cells) in weekly) {
        cells.groupBy { listOf(it.name, it.teacher, it.location, it.day.toString()) }
            .forEach { (_, group) ->
                val c = group.first()
                val sections = group.map { it.section }.distinct().sorted()
                var runStart = sections.first()
                var prev = runStart
                fun close(end: Int) {
                    val key = Key(c.name, c.teacher, c.location, c.day, runStart, end)
                    bits.getOrPut(key) { CharArray(maxWeek) { '0' } }[week - 1] = '1'
                }
                for (s in sections.drop(1)) {
                    if (s != prev + 1) {
                        close(prev)
                        runStart = s
                    }
                    prev = s
                }
                close(prev)
            }
    }
    return bits.map { (k, b) ->
        CourseItem(
            courseName = k.name,
            teacher = k.teacher,
            location = k.location,
            weekBits = String(b),
            dayOfWeek = k.day,
            startSection = k.start,
            endSection = k.end,
            courseCode = "",
            courseType = "",
        )
    }
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()
