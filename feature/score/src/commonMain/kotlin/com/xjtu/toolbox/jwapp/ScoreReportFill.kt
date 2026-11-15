package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.score.ReportedGrade

/**
 * 最新学期里「课在、成绩看不到」（多半是没评教）的那几行，用成绩报表补上成绩。
 *
 * 只做最新学期：往后不评教也能看，补旧学期只会多出误配。匹配是软性的——课名去课程号、
 * 全半角、空格标点大小写都抹平后比，学分作佐证；拿不准（同分、同名课）就不填，
 * 留给页面上的报表抽屉兜底，宁缺勿错。
 */
object ScoreReportFill {

    /** jwapp 对未评教等课程不给分时，score 是空、null 或一句提示。 */
    fun isHidden(score: String): Boolean {
        val s = score.trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true) || s.all { it == '-' || it == '—' || it == '*' }) return true
        return HIDDEN_WORDS.any { it in s }
    }

    /** 成绩查询里学期代码最大的那个学期，补全和报表按钮都只对它。 */
    fun latestTerm(terms: List<TermScore>): String? = terms.map { it.termCode }.filter { it.isNotBlank() }.maxOrNull()

    /**
     * 给 [hidden] 里每一行找报表里的那门课，返回 行 id → 报表那门。
     * [visible] 是同学期已有成绩的行：报表里和它们对得上的课先排除，免得被借走。
     */
    fun match(hidden: List<ScoreItem>, visible: List<ScoreItem>, report: List<ReportedGrade>): Map<String, ReportedGrade> {
        val taken = visible.map { key(it.courseName) }.toSet()
        val pool = report.filter { key(it.courseName) !in taken }
        val scores = hidden.associateWith { item -> pool.map { it to score(item, it) }.filter { it.second >= THRESHOLD } }
        val result = mutableMapOf<String, ReportedGrade>()
        val used = mutableSetOf<ReportedGrade>()
        hidden.forEach { item ->
            val ranked = scores.getValue(item).sortedByDescending { it.second }
            val (best, top) = ranked.firstOrNull() ?: return@forEach
            // 本行两个候选分不开，或者别的看不到成绩的行也同样像这门：都不填
            if (ranked.getOrNull(1)?.let { it.second > top - MARGIN } == true) return@forEach
            val rival = hidden.any { other -> other !== item && scores.getValue(other).any { it.first == best && it.second > top - MARGIN } }
            if (rival || best in used) return@forEach
            used += best
            result[item.id] = best
        }
        return result
    }

    /** 补上报表成绩的那一行：成绩、绩点换成报表的，来源标成报表。 */
    fun fill(item: ScoreItem, report: ReportedGrade): ScoreItem = item.copy(
        score = report.score,
        scoreValue = report.score.toDoubleOrNull(),
        gpa = report.gpa,
        passFlag = report.gpa?.let { it > 0.0 } ?: (report.score.toDoubleOrNull()?.let { it >= 60.0 } ?: true),
        source = ScoreSource.REPORT,
    )

    private fun score(item: ScoreItem, report: ReportedGrade): Int {
        val a = item.courseName.replace(COURSE_CODE, "").trim()
        val b = report.courseName.trim()
        val ka = key(a)
        val kb = key(b)
        var s = when {
            a == b -> 100
            ka == kb -> 90
            ka.length >= 4 && kb.length >= 4 && (ka in kb || kb in ka) -> 60
            else -> 0
        }
        if (s == 0) return 0
        if (item.coursePoint > 0 && report.coursePoint > 0) s += if (item.coursePoint == report.coursePoint) 15 else -40
        return s
    }

    /** 抹平写法差异后的课名：去课程号、罗马数字与全角统一，只留字母数字和汉字。 */
    internal fun key(name: String): String =
        normalizeCjcxCourseName(name.replace(COURSE_CODE, "")).filter { it.isLetterOrDigit() }

    private val COURSE_CODE = Regex("""[(（]\s*[A-Za-z0-9]{2,}\d{3,}\w*\s*[)）]\s*$""")
    private val HIDDEN_WORDS = listOf("暂不", "不能", "未评", "评教", "查看", "未公布", "未发布", "未录入")
    private const val THRESHOLD = 60
    private const val MARGIN = 10
}
