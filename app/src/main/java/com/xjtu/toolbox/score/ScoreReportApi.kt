package com.xjtu.toolbox.score

import com.xjtu.toolbox.auth.JwxtLogin
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 成绩报表数据
 */
data class ReportedGrade(
    val courseName: String,
    val coursePoint: Double,
    val score: String,      // 可能是数字或等级（如 "优秀"）
    val gpa: Double?,
    val term: String        // 学期代码 "2024-2025-1"
)

/**
 * 教务系统成绩报表查询 (FR Report)
 * ⚠️ 可绕过强制评教限制，在未评教时查看成绩
 * 移植自 XJTUToolBox Python 的 score.py -> reported_grade()
 *
 * 原理：通过帆软报表 (FineReport) 接口获取成绩单 HTML，解析表格
 */
class ScoreReportApi(private val login: JwxtLogin) {

    companion object {
        private const val FR_REPORT_URL = "https://jwxt.xjtu.edu.cn/jwapp/sys/frReport2/show.do"

        /** 成绩 → GPA 映射（西安交大 4.3 绩点制） */
        fun scoreToGpa(score: Any?): Double? {
            return when (score) {
                is Number -> {
                    val s = score.toDouble()
                    when {
                        s >= 95 -> 4.3
                        s >= 90 -> 4.0
                        s >= 85 -> 3.7
                        s >= 81 -> 3.3
                        s >= 78 -> 3.0
                        s >= 75 -> 2.7
                        s >= 72 -> 2.3
                        s >= 68 -> 2.0
                        s >= 64 -> 1.7
                        s >= 60 -> 1.3  // D = 1.3（西交教〔2015〕87号）
                        else -> 0.0
                    }
                }
                is String -> {
                    // 清理隐藏字符（零宽空格等），全角＋－转半角，只保留有效字符
                    val g = score
                        .replace('＋', '+')   // 全角 U+FF0B → ASCII +
                        .replace('－', '-')   // 全角 U+FF0D → ASCII -
                        .replace(Regex("[^a-zA-Z0-9+\\-\\u4e00-\\u9fff]"), "").uppercase()
                    when {
                        g.isEmpty() -> null
                        g.toDoubleOrNull() != null -> scoreToGpa(g.toDouble())
                        // 英文等级制（11级，西交教〔2015〕87号，无 D+）
                        g == "A+" -> 4.3
                        g == "A"  -> 4.0
                        g == "A-" -> 3.7
                        g == "B+" -> 3.3
                        g == "B"  -> 3.0
                        g == "B-" -> 2.7
                        g == "C+" -> 2.3
                        g == "C"  -> 2.0
                        g == "C-" -> 1.7
                        g == "D"  -> 1.3
                        g == "F"  -> 0.0
                        // 中文等级制（11级）
                        g == "优+" -> 4.3
                        g == "优"  -> 4.0
                        g == "优-" -> 3.7
                        g == "良+" -> 3.3
                        g == "良"  -> 3.0
                        g == "良-" -> 2.7
                        g == "中+" -> 2.3
                        g == "中"  -> 2.0
                        g == "中-" -> 1.7
                        g == "及格" -> 1.3
                        g == "不及格" -> 0.0
                        // 二等级制不参与 GPA
                        g == "通过" || g == "不通过" -> null
                        else -> null
                    }
                }
                else -> null
            }
        }
    }

    /**
     * 从 FR Report 的 HTML 中提取 Session ID
     * 查找 FR.SessionMgr.register('id', ...) 模式
     */
    private fun extractFrSessionId(html: String): String {
        // 优先匹配 FR.SessionMgr.register('sessionId', ...)
        val registerPattern = Regex("""FR\.SessionMgr\.register\(\s*['"](\d+)['"]""", RegexOption.IGNORE_CASE)
        registerPattern.find(html)?.let { return it.groupValues[1] }

        // 备选：匹配 sessionID=xxx
        val sessionIdPattern = Regex("""sessionID=(\d+)""", RegexOption.IGNORE_CASE)
        sessionIdPattern.find(html)?.let { return it.groupValues[1] }

        throw RuntimeException("FR Session ID 未找到")
    }

    /**
     * 从 FR Report 的 HTML 中提取总页数
     */
    private fun extractTotalPages(html: String): Int {
        val pattern = Regex("""FR\._p\.reportTotalPage\s*=\s*(\d+)""")
        pattern.find(html)?.let { return it.groupValues[1].toInt() }
        return 1
    }

    /**
     * 从 FR Report 的 HTML 页面中解析课程成绩
     */
    private fun parseCoursesFromHtml(html: String): List<ReportedGrade> {
        val doc = Jsoup.parse(html)
        val courses = mutableListOf<ReportedGrade>()
        var currentTerm: String? = null

        // 中文数字映射
        val cnNumMap = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6)

        // 查找所有 tbody 中的行
        val rows = doc.select("tbody tr")
        if (rows.isEmpty()) return emptyList()

        for (tr in rows) {
            val tds = tr.select("td")
            if (tds.isEmpty()) continue

            // 单列行 → 学期标题
            if (tds.size == 1) {
                val text = tds[0].text().trim().replace("\u3000", " ")
                val termMatch = Regex("""(\d{4})\s*-\s*(\d{4})\s*学年\s*(.+?)\s*学期""").find(text)
                if (termMatch != null) {
                    val y1 = termMatch.groupValues[1]
                    val y2 = termMatch.groupValues[2]
                    val termDisplay = termMatch.groupValues[3]
                    val termNo = termDisplay.toIntOrNull()
                        ?: cnNumMap[Regex("第(.)")?.find(termDisplay)?.groupValues?.get(1) ?: ""]
                        ?: continue
                    currentTerm = "$y1-$y2-$termNo"
                }
                continue
            }

            // 多列行 → 课程数据 (课程名, 学分, 成绩)
            if (tds.size < 3 || currentTerm == null) continue

            val courseName = tds[0].text().trim().replace("\u3000", " ")
            val creditText = tds[1].text().trim()
            val scoreText = tds[2].text().trim()
                .replace("＋", "+").replace("－", "-").replace("—", "-")

            // 跳过表头
            if (courseName in listOf("课程", "学分", "成绩") || creditText.toDoubleOrNull() == null) continue

            val credit = creditText.toDoubleOrNull() ?: continue
            val gpa = scoreToGpa(scoreText)

            courses.add(ReportedGrade(courseName, credit, scoreText, gpa, currentTerm))
        }

        return courses
    }

    /**
     * 获取成绩报表（绕过评教限制）
     * @param studentId 学号
     * @param filterTerms 可选的学期过滤列表
     * @return 课程成绩列表
     */
    fun getReportedGrade(studentId: String, filterTerms: List<String>? = null): List<ReportedGrade> {
        // 第1步：获取帆软报表初始页面
        val initUrl = "$FR_REPORT_URL?reportlet=bkdsglxjtu/XAJTDX_BDS_CJ.cpt&xh=$studentId"
        val initRequest = Request.Builder().url(initUrl).get().build()
        val initHtml = login.client.newCall(initRequest).execute().use { it.body?.string() ?: "" }

        // 第2步：提取 FR Session ID
        val sessionId = extractFrSessionId(initHtml)

        // 第3步：获取第一页内容
        val firstPageUrl = "$FR_REPORT_URL?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=1"
        val firstPageRequest = Request.Builder().url(firstPageUrl).get().build()
        val firstPageHtml = login.client.newCall(firstPageRequest).execute().use { it.body?.string() ?: "" }

        val totalPages = extractTotalPages(firstPageHtml)

        // 第4步：解析所有页面
        val allCourses = mutableListOf<ReportedGrade>()
        allCourses.addAll(parseCoursesFromHtml(firstPageHtml))

        for (pn in 2..totalPages) {
            val pageUrl = "$FR_REPORT_URL?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=$pn"
            val pageRequest = Request.Builder().url(pageUrl).get().build()
            val pageHtml = login.client.newCall(pageRequest).execute().use { it.body?.string() ?: "" }
            allCourses.addAll(parseCoursesFromHtml(pageHtml))
        }

        // 第5步：按学期过滤
        return if (filterTerms != null) {
            allCourses.filter { it.term in filterTerms }
        } else {
            allCourses
        }
    }
}
