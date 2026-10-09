package com.xjtu.toolbox.jwapp

import kotlinx.serialization.Serializable

/**
 * 成绩与 GPA 的**模型**（从 :app 的 `jwapp/JwappApi.kt` 里切出来的那一段：数据类 + 两个枚举 +
 * 一个异常）。
 *
 * 为什么值得切：`ScoreCalculator`（西交 4.3 绩点制映射、加权 GPA / 加权均分）已经搬进 :core ——
 * 它只认这几个模型。模型留在 :app 的话，算法搬过去也编不了；而 GPA 算法正是「成绩页 / 报表 /
 * 屁岱工具 / 将来的 Web 端」都要用同一份的东西（不同入口算出不同 GPA 是这类项目里最常见的事故）。
 *
 * `JwappApi`（okhttp 取数）继续留在 :app。`ScoreItem.asEmptyDetail()` 会调
 * [com.xjtu.toolbox.score.ScoreCalculator]，两边在同一层，方向是「模型 → 算法」。
 */
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
