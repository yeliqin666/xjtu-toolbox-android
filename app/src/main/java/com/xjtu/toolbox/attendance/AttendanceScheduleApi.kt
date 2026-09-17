package com.xjtu.toolbox.attendance

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

private const val TAG = "AttendanceSchedule"

/**
 * 从考勤系统拉课表。
 *
 * 考勤系统排课是老师刷卡签到的依据，调课、停课当天就会改，所以它是教务之外
 * 另一条可信的当前学期课表来源。代价与移动教务一样：`getWeekSchedule2`
 * **一次只给一周**，整学期要从第 1 周查到 `weeks` 周再累成 [CourseItem.weekBits]。
 *
 * 字段比教务少：没有课程性质，节次只有 `accountJtNo`（形如 `"5-6"`）这一个字符串。
 */
class AttendanceScheduleApi(site: SiteSession) {

    private val api = AttendanceApi(site)

    fun nearTerm(): TermInfo = api.getNearTerm()

    /**
     * 整学期课表。
     *
     * @param termBh 学期编号（[TermInfo.bh]），与教务的学期代码不是一套编号
     * @param weeks 学期总周数
     */
    fun getSchedule(termBh: String, weeks: Int): List<CourseItem> {
        require(weeks in 1..MAX_REASONABLE_WEEKS) { "考勤系统给的学期周数不可信：$weeks" }

        // 整学期十几到二十个请求，串着发要等好几秒。小批量并发拉；
        // AttendanceApi.post 自带有界重试，单周瞬时 5xx 不会拖垮整轮。
        val weekly = runBlocking {
            (1..weeks).chunked(WEEK_FETCH_CONCURRENCY).flatMap { chunk ->
                chunk.map { week -> async(Dispatchers.IO) { week to queryWeek(week, termBh) } }.awaitAll()
            }
        }

        val bits = LinkedHashMap<String, CharArray>()
        val sample = LinkedHashMap<String, Occurrence>()
        for ((week, list) in weekly) {
            for (occurrence in list) {
                val key = occurrence.identity()
                sample.getOrPut(key) { occurrence }
                bits.getOrPut(key) { CharArray(weeks) { '0' } }[week - 1] = '1'
            }
        }

        return sample.map { (key, occurrence) ->
            CourseItem(
                courseName = occurrence.courseName,
                teacher = occurrence.teacher,
                location = occurrence.location,
                weekBits = String(bits.getValue(key)),
                dayOfWeek = occurrence.dayOfWeek,
                startSection = occurrence.startSection,
                endSection = occurrence.endSection,
                courseCode = occurrence.courseCode,
                courseType = "",
            )
        }
    }

    private fun queryWeek(week: Int, termBh: String): List<Occurrence> {
        val body = """{"week":$week,"termNo":${termBh.toIntOrNull() ?: "\"$termBh\""}}"""
        val result = api.post("/attendance-student/rankClass/getWeekSchedule2", body)
        val json = result.safeParseJsonObject()
        val dataEl = json.get("data")
        if (dataEl == null || dataEl.isJsonNull || !dataEl.isJsonArray) return emptyList()

        return dataEl.asJsonArray.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = obj.get("subjectSName").safeStr
            if (name.isBlank()) return@mapNotNull null
            // accountJtNo 形如 "5-6"；拆不出起止节次的行没法排进格子里，丢掉。
            val sections = obj.get("accountJtNo").safeStr.split('-')
            val start = sections.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val end = sections.getOrNull(1)?.trim()?.toIntOrNull() ?: start
            val day = obj.get("accountWeeknum").safeStr.trim().toIntOrNull() ?: return@mapNotNull null
            if (day !in 1..7 || start !in 1..MAX_REASONABLE_SECTION || end !in start..MAX_REASONABLE_SECTION) {
                Log.w(TAG, "第${week}周有一行节次越界，跳过：day=$day $start-$end")
                return@mapNotNull null
            }
            Occurrence(
                courseName = name,
                teacher = obj.get("teachNameList").safeStr,
                location = "${obj.get("buildName").safeStr}-${obj.get("roomRoomnum").safeStr}".trim('-'),
                dayOfWeek = day,
                startSection = start,
                endSection = end,
                courseCode = obj.get("subjectSCode").safeStr,
            )
        }
    }

    /** 身份不含教室，与 `ScheduleDiff` 口径一致：换教室是同一节课换了地方。 */
    private data class Occurrence(
        val courseName: String,
        val teacher: String,
        val location: String,
        val dayOfWeek: Int,
        val startSection: Int,
        val endSection: Int,
        val courseCode: String,
    ) {
        fun identity() = "${courseCode.ifBlank { courseName }}|$dayOfWeek|$startSection|$endSection"
    }

    private companion object {
        const val MAX_REASONABLE_WEEKS = 30
        const val MAX_REASONABLE_SECTION = 20
        const val WEEK_FETCH_CONCURRENCY = 4
    }
}
