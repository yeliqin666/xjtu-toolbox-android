package com.xjtu.toolbox.score

import com.xjtu.toolbox.auth.SiteSession
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 成绩报表数据
 */
@kotlinx.serialization.Serializable
data class ReportedGrade(
    val courseName: String = "",
    val coursePoint: Double = 0.0,
    val score: String = "",      // 可能是数字或等级（如 "优秀"）
    val gpa: Double? = null,
    val term: String = "",       // 学期代码 "2024-2025-1"
)

/**
 * 教务系统成绩报表查询 (FR Report)
 * ⚠️ 可绕过强制评教限制，在未评教时查看成绩
 * 移植自 XJTUToolBox Python 的 score.py -> reported_grade()
 *
 * 原理：通过帆软报表 (FineReport) 接口获取成绩单 HTML，解析表格
 */
class ScoreReportApi(private val site: SiteSession) {

    companion object {
        private const val FR_REPORT_URL = "https://jwxt.xjtu.edu.cn/jwapp/sys/frReport2/show.do"

        /**
         * 学期标题 → 学期代码 `2024-2025-N`。不是学期标题返回 null。
         *
         * 以前只认「1 学期」「第一学期」：小学期的标题写的是「夏季学期」「暑期学期」「小学期」
         * 这类，一个数字都没有，整行被跳过、学期没切换，小学期的课就混进了前一个（春季）学期。
         * 现在季节字也认；实在认不出的写法原样留作代码，至少单独成组，不再并进上一学期。
         */
        internal fun termCodeFromHeading(text: String): String? {
            // 暑假重修、补考也会出成绩，标题可能只写「暑假」不带「学期」两个字。
            // 和成绩页（教务接口）的学期代码对齐：暑假 = 4（见 XjtuTime.displayTerm）
            Regex("""(\d{4})\s*-\s*(\d{4})\s*学年\s*暑假""").find(text)?.let {
                return "${it.groupValues[1]}-${it.groupValues[2]}-4"
            }
            val m = Regex("""(\d{4})\s*-\s*(\d{4})\s*学年\s*(.*?)\s*学期""").find(text) ?: return null
            val y1 = m.groupValues[1]
            val y2 = m.groupValues[2]
            val label = m.groupValues[3]
            val cnNumMap = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4)
            val no = label.toIntOrNull()
                ?: Regex("第\\s*(\\d)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("第\\s*([一二三四])").find(label)?.groupValues?.get(1)?.let { cnNumMap[it] }
                ?: when {
                    "秋" in label -> 1
                    "春" in label -> 2
                    "夏" in label || "暑" in label || "小" in label || "短" in label -> 3
                    else -> null
                }
            return when {
                no != null -> "$y1-$y2-$no"
                label.isNotBlank() -> "$y1-$y2-$label"
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

        // 查找所有 tbody 中的行
        val rows = doc.select("tbody tr")
        if (rows.isEmpty()) return emptyList()

        for (tr in rows) {
            val tds = tr.select("td")
            if (tds.isEmpty()) continue

            // 单列行 → 学期标题
            if (tds.size == 1) {
                val text = tds[0].text().trim().replace("\u3000", " ")
                termCodeFromHeading(text)?.let { currentTerm = it }
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
            val gpa = com.xjtu.toolbox.score.ScoreCalculator.scoreToGpa(scoreText)

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
    suspend fun getReportedGrade(studentId: String, filterTerms: List<String>? = null): List<ReportedGrade> {
        // 第1步：获取帆软报表初始页面
        val initUrl = "$FR_REPORT_URL?reportlet=bkdsglxjtu/XAJTDX_BDS_CJ.cpt&xh=$studentId"
        val initRequest = Request.Builder().url(initUrl).get().build()
        val initHtml = site.executeWithReAuth(initRequest).use { it.body?.string() ?: "" }

        // 第2步：提取 FR Session ID
        val sessionId = extractFrSessionId(initHtml)

        // 第3步：获取第一页内容
        val firstPageUrl = "$FR_REPORT_URL?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=1"
        val firstPageRequest = Request.Builder().url(firstPageUrl).get().build()
        val firstPageHtml = site.executeWithReAuth(firstPageRequest).use { it.body?.string() ?: "" }

        val totalPages = extractTotalPages(firstPageHtml)

        // 第4步：解析所有页面
        val allCourses = mutableListOf<ReportedGrade>()
        allCourses.addAll(parseCoursesFromHtml(firstPageHtml))

        for (pn in 2..totalPages) {
            val pageUrl = "$FR_REPORT_URL?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=$pn"
            val pageRequest = Request.Builder().url(pageUrl).get().build()
            val pageHtml = site.executeWithReAuth(pageRequest).use { it.body?.string() ?: "" }
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
