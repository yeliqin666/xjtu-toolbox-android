package com.xjtu.toolbox.jwapp

import kotlinx.serialization.Serializable

/** 成绩与 GPA 的模型。 */
enum class ScoreSource { JWAPP, REPORT }

enum class CourseGroup(val label: String, val shortLabel: String) {
    GEN_CORE("通核", "通核"),
    GEN_ELECTIVE("通选", "通选");
}

@Serializable
data class ScoreItem(
    val id: String = "",
    val termCode: String = "",
    val courseName: String = "",
    val score: String = "",
    val scoreValue: Double? = null,
    val passFlag: Boolean = false,
    val specificReason: String? = null,
    val coursePoint: Double = 0.0,
    val examType: String = "",
    val majorFlag: String? = null,
    val examProp: String = "",
    val replaceFlag: Boolean = false,
    val gpa: Double? = null,
    val source: ScoreSource = ScoreSource.JWAPP,
    val courseCategory: String? = null,
    val courseCode: String? = null,
    val courseGroup: CourseGroup? = null,
) {
    fun asEmptyDetail(): ScoreDetail = ScoreDetail(
        courseName = courseName,
        coursePoint = coursePoint,
        examType = examType,
        majorFlag = majorFlag,
        examProp = examProp,
        replaceFlag = replaceFlag,
        score = score,
        scoreValue = scoreValue,
        gpa = com.xjtu.toolbox.score.ScoreCalculator.courseGpa(this) ?: 0.0,
        passFlag = com.xjtu.toolbox.score.ScoreCalculator.isPassed(this),
        specificReason = specificReason,
        itemList = emptyList(),
    )
}

class NoScoreDetailException(message: String = "该课程暂无分项成绩") : RuntimeException(message)

data class ScoreDetailItem(
    val itemName: String,
    val itemPercent: Double,
    val itemScore: String,
    val itemScoreValue: Double?
)

data class ScoreDetail(
    val courseName: String,
    val coursePoint: Double,
    val examType: String,
    val majorFlag: String?,
    val examProp: String,
    val replaceFlag: Boolean,
    val score: String,
    val scoreValue: Double?,
    val gpa: Double,
    val passFlag: Boolean,
    val specificReason: String?,
    val itemList: List<ScoreDetailItem>
)

@Serializable
data class TermScore(
    val termCode: String = "",
    val termName: String = "",
    val scoreList: List<ScoreItem> = emptyList(),
)

data class GpaInfo(
    val gpa: Double,
    val averageScore: Double,
    val totalCredits: Double,
    val courseCount: Int
)


/** 教务 `xscjcx.do` 的一行：对所有课程（含等级制）都给精确总评与绩点。 */
data class CjcxScore(
    val courseName: String,
    val termCode: String,
    val zcj: Double,
    val xfjd: Double,
    val xf: Double,
    val djcjlxdm: String,
    val djcjmc: String?,
    val pscj: Double?,
    val pscjxs: String?,
    val qmcj: Double?,
    val qmcjxs: String?,
    val kch: String,
    val jxbid: String,
    val passFlag: Boolean,
    val examProp: String,
    val kclbdm: String,
)

/** 标准化课程名（空格/全角/罗马数字/符号统一）。 */
fun normalizeCjcxCourseName(name: String): String = name.trim()
    .replace("\u3000", " ").replace("\u00A0", " ")
    .replace(Regex("\\s+"), " ")
    .replace("（", "(").replace("）", ")")
    .replace("＋", "+").replace("－", "-")
    .replace("Ⅰ", "I").replace("Ⅱ", "II").replace("Ⅲ", "III")
    .replace("Ⅳ", "IV").replace("Ⅴ", "V").replace("Ⅵ", "VI")
    .replace("ⅰ", "I").replace("ⅱ", "II").replace("ⅲ", "III")
    .replace("ⅳ", "IV").replace("ⅴ", "V").replace("ⅵ", "VI")
    .replace(Regex("[◇◆◎○●★☆※▲△▼▽]"), "")
    .replace(Regex("\\([A-Z]{2,}\\d{4,}\\)$"), "")
    .trim().lowercase()

/**
 * 用教务 xscjcx 的精确总评与绩点替换 jwapp 的粗分（等级制课程也有具体分数），并标出通核 / 通选。
 * 按学期 + 课程名对，对不上再按课程号。成绩页和学期总结的补齐共用这一份，写进缓存的数据才一致。
 */
fun List<TermScore>.withPreciseScores(precise: List<CjcxScore>?): List<TermScore> {
    val lookup = precise?.associateBy { "${it.termCode}|${normalizeCjcxCourseName(it.courseName)}" }.orEmpty()
    val byCode = precise?.associateBy { it.kch }.orEmpty()
    return map { ts ->
        ts.copy(scoreList = ts.scoreList.map { score ->
            val p = lookup["${ts.termCode}|${normalizeCjcxCourseName(score.courseName)}"]
                ?: score.courseCode?.let { byCode[it] }
            val merged = if (p == null) score else score.copy(
                scoreValue = p.zcj,
                gpa = p.xfjd,
                courseCategory = p.kclbdm.ifBlank { null },
                courseCode = p.kch.ifBlank { score.courseCode },
            )
            merged.withCourseGroup()
        })
    }
}

/** 课程号前缀区分通核 / 通选。 */
private fun ScoreItem.withCourseGroup(): ScoreItem {
    val code = courseCode?.uppercase() ?: return this
    val group = when {
        code.startsWith("CORE") -> CourseGroup.GEN_CORE
        code.startsWith("GNED") -> CourseGroup.GEN_ELECTIVE
        else -> return this
    }
    return copy(courseGroup = group)
}

/** 「该课程暂无分项成绩」的文案判据。 */
fun isNoScoreDetailMessage(msg: String?): Boolean {
    if (msg.isNullOrBlank()) return false
    return listOf("无分项", "没有分项", "暂无", "不存在", "未查询", "无明细", "无细则", "没有明细", "无成绩").any { it in msg }
}
