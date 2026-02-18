package com.xjtu.toolbox.gmis

import com.xjtu.toolbox.auth.GmisLogin
import com.xjtu.toolbox.ui.ScheduleSlot
import okhttp3.Request
import org.jsoup.Jsoup

data class GmisScheduleItem(
    val name: String,
    val teacher: String,
    val classroom: String,
    val weeks: String,
    val dayOfWeek: Int,
    val periodStart: Int,
    val periodEnd: Int
) : ScheduleSlot {
    override val slotName get() = name
    override val slotLocation get() = classroom
    override val slotDayOfWeek get() = dayOfWeek
    override val slotStartSection get() = periodStart
    override val slotEndSection get() = periodEnd

    fun getWeekList(): List<Int> {
        val match = Regex("""(\d+)-(\d+)""").find(weeks) ?: return emptyList()
        val start = match.groupValues[1].toIntOrNull() ?: return emptyList()
        val end = match.groupValues[2].toIntOrNull() ?: return emptyList()
        return (start..end).toList()
    }

    fun isInWeek(week: Int): Boolean = week in getWeekList()
}

data class GmisScoreItem(
    val courseName: String,
    val coursePoint: Double,
    val score: Double,
    val type: String,
    val examDate: String,
    val gpa: Double
)

private val GPA_RULES = listOf(
    Triple(95.0, 100.0, 4.3), Triple(90.0, 94.99, 4.0), Triple(85.0, 89.99, 3.7),
    Triple(81.0, 84.99, 3.3), Triple(78.0, 80.99, 3.0), Triple(75.0, 77.99, 2.7),
    Triple(72.0, 74.99, 2.3), Triple(68.0, 71.99, 2.0), Triple(64.0, 67.99, 1.7),
    Triple(60.0, 63.99, 1.0), Triple(0.0, 59.99, 0.0)
)

private fun scoreToGpa(score: Double): Double {
    for ((low, high, gpa) in GPA_RULES) {
        if (score in low..high) return gpa
    }
    return 0.0
}

class GmisApi(private val login: GmisLogin) {

    private var schedulePageCache: String? = null
    private var lastModified: Long = 0
    private var termValueMap: Map<String, String>? = null

    private fun timestampToTerm(timestamp: String): String {
        val parts = timestamp.split("-")
        require(parts.size == 3) { "Invalid timestamp format" }
        val startYear = parts[0]
        val semester = parts[2]
        return if (semester == "1") "${startYear}秋" else "${startYear.toInt() + 1}春"
    }

    private fun termToTimestamp(term: String): String {
        val yearPart = term.dropLast(1).toInt()
        val semesterPart = term.last()
        return if (semesterPart == '秋') "$yearPart-${yearPart + 1}-1" else "${yearPart - 1}-$yearPart-2"
    }

    private fun parseSemesterOptions(html: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        val map = mutableMapOf<String, String>()
        for (option in doc.select("select#drpxq option")) {
            val value = option.attr("value").trim()
            val name = option.text().trim()
            if (value.isNotEmpty() && name.isNotEmpty()) map[name] = value
        }
        return map
    }

    private fun parseCurrentSemester(html: String): String? {
        return Jsoup.parse(html).select("select#drpxq option[selected]").first()?.text()?.trim()
    }

    private fun parseScheduleHtml(html: String): List<GmisScheduleItem> {
        val courses = mutableListOf<GmisScheduleItem>()
        val pattern = Regex(
            """document\.getElementById\("td_(\d+)_(\d+)"\);\s*if\s*\(td\.innerHTML!=""\)\s*td\.innerHTML\+="<br><br>";\s*td\.innerHTML\+="([^"]+)";""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in pattern.findAll(html)) {
            val dayOfWeek = match.groupValues[1].toInt()
            val courseText = match.groupValues[3]
            val name = Regex("""课程：([^<]+)""").find(courseText)?.groupValues?.get(1)?.trim() ?: continue
            val teacher = Regex("""教师：([^<]+)""").find(courseText)?.groupValues?.get(1)?.trim() ?: continue
            val classroom = Regex("""教室：([^<]+)""").find(courseText)?.groupValues?.get(1)?.trim() ?: ""
            val periods = Regex("""节次：([^<]+)""").find(courseText)?.groupValues?.get(1)?.trim() ?: continue
            val weeks = Regex("""周次：([^<]+)""").find(courseText)?.groupValues?.get(1)?.trim() ?: continue
            val (periodStart, periodEnd) = parsePeriods(periods)
            val item = GmisScheduleItem(name, teacher, classroom, weeks, dayOfWeek, periodStart, periodEnd)
            if (item !in courses) courses.add(item)
        }
        return courses
    }

    private fun parsePeriods(periodsStr: String): Pair<Int, Int> {
        return if ("-" in periodsStr) {
            val parts = periodsStr.split("-")
            (parts[0].trim().toIntOrNull() ?: 0) to (parts[1].trim().toIntOrNull() ?: 0)
        } else {
            val p = periodsStr.trim().toIntOrNull() ?: 0
            p to p
        }
    }

    private fun fetchSchedulePage(): String {
        val useCache = schedulePageCache != null && System.currentTimeMillis() - lastModified < 600_000
        return if (useCache) {
            schedulePageCache!!
        } else {
            val request = Request.Builder().url("https://gmis.xjtu.edu.cn/pyxx/pygl/xskbcx").get().build()
            val data = login.client.newCall(request).execute().use { response ->
                response.body?.string() ?: throw RuntimeException("空响应")
            }
            schedulePageCache = data
            lastModified = System.currentTimeMillis()
            data
        }
    }

    fun getCurrentTerm(): String {
        val data = fetchSchedulePage()
        if (termValueMap == null) termValueMap = parseSemesterOptions(data)
        val currentSemester = parseCurrentSemester(data) ?: throw RuntimeException("无法获取当前学期")
        return termToTimestamp(currentSemester)
    }

    fun getSchedule(timestamp: String? = null): List<GmisScheduleItem> {
        val data: String
        if (timestamp == null) {
            data = fetchSchedulePage()
        } else {
            val termCode = timestampToTerm(timestamp)
            if (termValueMap == null) { val page = fetchSchedulePage(); termValueMap = parseSemesterOptions(page) }
            val termValue = termValueMap!![termCode] ?: throw RuntimeException("未找到学期 $termCode 对应的选项")
            val request = Request.Builder().url("https://gmis.xjtu.edu.cn/pyxx/pygl/xskbcx/index/$termValue").get().build()
            data = login.client.newCall(request).execute().use { response ->
                response.body?.string() ?: throw RuntimeException("空响应")
            }
        }
        if (termValueMap == null) termValueMap = parseSemesterOptions(data)
        return parseScheduleHtml(data)
    }

    fun getScore(): List<GmisScoreItem> {
        val request = Request.Builder().url("https://gmis.xjtu.edu.cn/pyxx/pygl/xscjcx/index").get().build()
        val html = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        return parseScoreHtml(html)
    }

    private fun parseScoreHtml(html: String): List<GmisScoreItem> {
        val result = mutableListOf<GmisScoreItem>()
        val doc = Jsoup.parse(html)
        val tables = doc.select("table#sample-table-1")
        val tableTypes = listOf("学位课程", "选修课程", "必修环节")

        tables.forEachIndexed { idx, table ->
            val typeName = if (idx < tableTypes.size) tableTypes[idx] else "未知"
            val rows = table.select("tr").drop(1)
            for (row in rows) {
                val tds = row.select("td")
                if (tds.isEmpty()) continue
                val courseName: String; val coursePointText: String; val scoreText: String; val examDate: String
                if (typeName == "必修环节") {
                    courseName = tds.getOrNull(0)?.text()?.trim() ?: ""
                    coursePointText = tds.getOrNull(1)?.text()?.trim() ?: ""
                    scoreText = tds.getOrNull(2)?.text()?.trim() ?: ""
                    examDate = tds.getOrNull(3)?.text()?.trim() ?: ""
                } else {
                    courseName = tds.getOrNull(0)?.text()?.trim() ?: ""
                    coursePointText = tds.getOrNull(1)?.text()?.trim() ?: ""
                    scoreText = tds.getOrNull(3)?.text()?.trim() ?: ""
                    examDate = tds.getOrNull(4)?.text()?.trim() ?: ""
                }
                if (courseName.isNotEmpty() && scoreText.isNotEmpty()) {
                    val scoreVal = scoreText.toDoubleOrNull() ?: continue
                    val coursePoint = coursePointText.toDoubleOrNull() ?: 0.0
                    result.add(GmisScoreItem(courseName, coursePoint, scoreVal, typeName, examDate, scoreToGpa(scoreVal)))
                }
            }
        }
        return result
    }
}
