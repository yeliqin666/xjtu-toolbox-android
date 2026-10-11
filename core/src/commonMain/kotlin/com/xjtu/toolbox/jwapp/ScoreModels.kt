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

// ── 教务精确成绩（`CjcxApi` 那一半的模型，从 `:app` 的 `CjcxApi.kt` 里搬来）──────────
//
// 与上面那几个模型同一条理由：`JwappScoreViewModel`（现在在 `:core`）要按「学期 + 课程名」
// 把教务 `xscjcx.do` 的精确总评/绩点替换到 jwapp 的学期成绩上 —— 替换逻辑是**编排**，留在这边，
// 而 `CjcxScore` 是它的输入模型。`CjcxApi`（okhttp 抓 `jwxt.xjtu.edu.cn/jwapp/sys/cjcx`）
// 搬进了 `:data`，仍然构造这个类型。

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

/**
 * 标准化课程名（空格/全角/罗马数字/符号统一）。
 *
 * 从 `CjcxApi.Companion.normalizeName` 原样搬来（同一个函数体、同一个语义）。它现在只有一个
 * 消费方 —— `:core` 的 `JwappScoreViewModel` 里那两处「按课程名对上就替换精确成绩」的查表 ——
 * 而这条判定必须与 jwapp 那边（`JwappApi` 抽课程号的正则）用**同一份**归一化：两份写法一旦漂开，
 * 事故会以「有些课显示的是 jwapp 的粗分」的形式出现。
 */
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
 * 「该课程暂无分项成绩」的文案判据 —— 从 `:app` 的 `JwappApi.kt` 搬来。
 *
 * 两个消费方：`:data` 的 `JwappApi.getDetail`（决定抛不抛 [NoScoreDetailException]）与
 * `:core` 的 `JwappScoreViewModel.loadDetail`（异常里的同一句话再判一次）。搬进共享层之前这两处
 * 用的是同一个 `internal fun`；分居两个模块后必须放在这里，[NoScoreDetailException] 在 :core。
 */
fun isNoScoreDetailMessage(msg: String?): Boolean {
    if (msg.isNullOrBlank()) return false
    return listOf("无分项", "没有分项", "暂无", "不存在", "未查询", "无明细", "无细则", "没有明细", "无成绩").any { it in msg }
}
