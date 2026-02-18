package com.xjtu.toolbox.jwapp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.JwappLogin
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "JwappGPA"

// ==================== 数据类 ====================

/** 成绩数据来源 */
enum class ScoreSource { JWAPP, REPORT }

data class ScoreItem(
    val id: String,
    val termCode: String,
    val courseName: String,
    val score: String,
    val scoreValue: Double?,
    val passFlag: Boolean,
    val specificReason: String?,
    val coursePoint: Double,
    val examType: String,
    val majorFlag: String?,
    val examProp: String,
    val replaceFlag: Boolean,
    val gpa: Double? = null,
    val source: ScoreSource = ScoreSource.JWAPP
)

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

data class TermScore(
    val termCode: String,
    val termName: String,
    val scoreList: List<ScoreItem>
)

data class ScoreRank(
    val defeatPercent: Double?,
    val scoreHigh: Double?,
    val scoreAvg: Double?,
    val scoreLow: Double?,
    val scoreDist: List<ScoreDistRange>
)

data class ScoreDistRange(
    val range: String,
    val num: Int
)

data class TimeTableBasis(
    val termCode: String,
    val termName: String,
    val maxWeekNum: Int,
    val maxSection: Int,
    val todayWeekDay: Int,
    val todayWeekNum: Int
)

data class GpaInfo(
    val gpa: Double,
    val averageScore: Double,
    val totalCredits: Double,
    val courseCount: Int
)

// ==================== API 类 ====================

/**
 * 清理成绩字符串：去掉零宽字符、不可见控制字符等
 * 只保留字母、数字、中文、+、- 号
 * 关键：先将全角 ＋（U+FF0B）→ + 和 ＋（U+FF0D）→ - ，API 返回的是全角符号
 */
private fun cleanGradeString(raw: String): String {
    return raw.trim()
        .replace('＋', '+')   // 全角 plus  U+FF0B → ASCII +
        .replace('－', '-')   // 全角 minus U+FF0D → ASCII -
        .replace(Regex("[^a-zA-Z0-9+\\-\\u4e00-\\u9fff]"), "")
}

/**
 * 等级制成绩转数字分数
 * 映射依据：西安交通大学本科生学籍管理与学位授予规定（西交教〔2015〕87号）
 * XJTU 采用 11 级制（无 D+），英文/中文等级一一对应
 */
fun gradeToNumericScore(grade: String): Double? {
    val g = cleanGradeString(grade)
    // 依据《西安交通大学本科生学籍管理与学位授予规定（2017）》国（境）外成绩转换标准
    return when {
        // 英文等级制（11级）
        g.equals("A+", true) -> 98.0
        g.equals("A", true) && !g.contains("+") && !g.contains("-") -> 92.0
        g.equals("A-", true) -> 87.0
        g.equals("B+", true) -> 83.0
        g.equals("B", true) && !g.contains("+") && !g.contains("-") -> 79.0
        g.equals("B-", true) -> 76.0
        g.equals("C+", true) -> 73.0
        g.equals("C", true) && !g.contains("+") && !g.contains("-") -> 70.0
        g.equals("C-", true) -> 66.0
        g.equals("D", true) -> 62.0
        g.equals("F", true) -> 0.0
        // 中文等级制（11级，与英文一一对应）
        g == "优+" -> 98.0
        g == "优" -> 92.0
        g == "优-" -> 87.0
        g == "良+" -> 83.0
        g == "良" -> 79.0
        g == "良-" -> 76.0
        g == "中+" -> 73.0
        g == "中" -> 70.0
        g == "中-" -> 66.0
        g == "及格" -> 62.0
        g == "不及格" -> 0.0
        // 二等级制（通过/不通过）→ 不映射数字分数
        g == "通过" || g == "不通过" -> null
        else -> null
    }
}

class JwappApi(private val login: JwappLogin) {

    private val baseUrl = "http://jwapp.xjtu.edu.cn"
    private val gson = Gson()

    fun getGrade(termCode: String? = null): List<TermScore> {
        val code = termCode ?: "*"
        val json = gson.toJson(mapOf("termCode" to code))
        val body = json.toRequestBody("application/json".toMediaType())

        val request = login.authenticatedRequest("$baseUrl/api/biz/v410/score/termScore")
            .post(body)

        val responseBody = login.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject

        val resultCode = root.get("code").asInt
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.asString ?: "服务器错误 ($resultCode)")
        }

        val termScoreList = root.getAsJsonObject("data")
            .getAsJsonArray("termScoreList")

        return termScoreList.map { termElement ->
            val termObj = termElement.asJsonObject
            val scores = termObj.getAsJsonArray("scoreList").map { scoreEl ->
                val s = scoreEl.asJsonObject
                val rawScore = s.get("score").safeString()
                val numericScore = rawScore.toDoubleOrNull()
                val apiGpa = s.get("gpa").safeDoubleOrNull()
                val apiPassFlag = s.get("passFlag")

                // 诊断日志：记录API原始返回值（帮助定位等级制GPA=0问题）
                val courseName = s.get("courseName").safeString()
                if (numericScore == null && rawScore.isNotEmpty()) {
                    // 可能是等级制课程，记录完整原始数据
                    Log.d(TAG, "解析[等级制?] $courseName: score='$rawScore' " +
                            "gpa_raw=${s.get("gpa")} passFlag_raw=$apiPassFlag " +
                            "examProp=${s.get("examProp").safeString()}")
                }

                ScoreItem(
                    id = s.get("id").safeString(),
                    termCode = s.get("termCode").safeString(),
                    courseName = courseName,
                    score = rawScore,
                    scoreValue = numericScore,
                    passFlag = s.get("passFlag").safeBoolean(),
                    specificReason = s.get("specificReason").safeStringOrNull(),
                    coursePoint = s.get("coursePoint").safeDouble(),
                    examType = s.get("examType").safeString(),
                    majorFlag = s.get("majorFlag").safeStringOrNull(),
                    examProp = s.get("examProp").safeString(),
                    replaceFlag = s.get("replaceFlag").safeBoolean(),
                    gpa = s.get("gpa").safeDoubleOrNull()
                )
            }
            TermScore(
                termCode = termObj.get("termCode").safeString(),
                termName = termObj.get("termName").safeString(),
                scoreList = scores
            )
        }
    }

    fun getDetail(courseId: String): ScoreDetail {
        val json = gson.toJson(mapOf("id" to courseId))
        val body = json.toRequestBody("application/json".toMediaType())

        val request = login.authenticatedRequest("$baseUrl/api/biz/v410/score/scoreDetail")
            .post(body)

        val responseBody = login.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject

        val resultCode = root.get("code").asInt
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.asString ?: "服务器错误 ($resultCode)")
        }

        val data = root.getAsJsonObject("data")

        val items = data.getAsJsonArray("itemList")?.map { itemEl ->
            val item = itemEl.asJsonObject
            val percentStr = item.get("itemPercent").safeString("0")
            val percent = percentStr.trimEnd('%').toDoubleOrNull()?.let { it / 100.0 } ?: 0.0

            ScoreDetailItem(
                itemName = item.get("itemName").safeString(),
                itemPercent = percent,
                itemScore = item.get("itemScore").safeString(),
                itemScoreValue = item.get("itemScore").safeString().toDoubleOrNull()
            )
        } ?: emptyList()

        val rawScore = data.get("score").safeString()
        val serverGpa = data.get("gpa").safeDouble()
        // 如果服务器 GPA 为 0 但课程已通过，用本地映射兜底
        val effectiveGpa = if (serverGpa > 0.0) serverGpa
            else com.xjtu.toolbox.score.ScoreReportApi.scoreToGpa(rawScore) ?: 0.0

        return ScoreDetail(
            courseName = data.get("courseName").safeString(),
            coursePoint = data.get("coursePoint").safeDouble(),
            examType = data.get("examType").safeString(),
            majorFlag = data.get("majorFlag").safeStringOrNull(),
            examProp = data.get("examProp").safeString(),
            replaceFlag = data.get("replaceFlag").safeBoolean(),
            score = rawScore,
            scoreValue = rawScore.toDoubleOrNull(),
            gpa = effectiveGpa,
            passFlag = data.get("passFlag").safeBoolean(),
            specificReason = data.get("specificReason").safeStringOrNull(),
            itemList = items
        )
    }

    fun getRank(courseId: String): ScoreRank {
        val json = gson.toJson(mapOf("id" to courseId))
        val body = json.toRequestBody("application/json".toMediaType())

        val request = login.authenticatedRequest("$baseUrl/api/biz/v410/score/scoreAnalyze")
            .post(body)

        val responseBody = login.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject

        val resultCode = root.get("code").asInt
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.asString ?: "服务器错误 ($resultCode)")
        }

        val data = root.getAsJsonObject("data")

        val dist = data.getAsJsonArray("scoreDist")?.map { distEl ->
            val d = distEl.asJsonObject
            ScoreDistRange(
                range = d.get("range").safeString(),
                num = d.get("num").safeInt()
            )
        } ?: emptyList()

        return ScoreRank(
            defeatPercent = data.get("defeatPercent").safeDoubleOrNull(),
            scoreHigh = data.get("scoreHigh").safeDoubleOrNull(),
            scoreAvg = data.get("scoreAvg").safeDoubleOrNull(),
            scoreLow = data.get("scoreLow").safeDoubleOrNull(),
            scoreDist = dist
        )
    }

    fun getTimeTableBasis(): TimeTableBasis {
        val request = login.authenticatedRequest("https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time")
            .get()

        val body = login.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(body).asJsonObject

        val resultCode = root.get("code").asInt
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.asString ?: "服务器错误 ($resultCode)")
        }

        // API 可能返回 {code, data:{...}} 或直接平铺字段
        val obj = if (root.has("data") && root.get("data").isJsonObject) {
            root.getAsJsonObject("data")
        } else {
            root
        }

        return TimeTableBasis(
            termCode = obj.get("xnxqdm").safeString(),
            termName = obj.get("xnxqmc").safeString(),
            maxWeekNum = obj.get("maxWeekNum").safeInt(),
            maxSection = obj.get("maxSection").safeInt(),
            todayWeekDay = obj.get("todayWeekDay").safeInt(),
            todayWeekNum = obj.get("todayWeekNum").safeInt()
        )
    }

    fun getCurrentTerm(): String = getTimeTableBasis().termCode

    fun getCurrentWeek(): Int = getTimeTableBasis().todayWeekNum

    fun getTermList(): List<Pair<String, String>> {
        val allGrades = getGrade(null)
        return allGrades.map { it.termCode to it.termName }
    }

    /**
     * 从成绩列表本地计算 GPA（无需逐课程请求）
     * 优先使用服务器返回的 gpa 字段，否则用本地映射
     */
    fun calculateGpaFromGrades(termScores: List<TermScore>): GpaInfo {
        val allCourses = termScores.flatMap { it.scoreList }
        return calculateGpaForCourses(allCourses)
    }

    /**
     * 对指定课程列表计算 GPA（用于用户自选课程）
     *
     * 逻辑：
     *  1. 二等级制（通过/不通过）不参与 GPA
     *  2. 先算出 courseGpa / numericScore，再综合判断是否真正"未通过"
     *     （API 的 passFlag 对等级制课程可能返回 null/0 → safeBoolean 变 false，
     *      必须用 GPA/分数二次兜底，否则等级制课程全部被跳过→GPA=0）
     */
    fun calculateGpaForCourses(courses: List<ScoreItem>): GpaInfo {
        var totalCredits = 0.0
        var weightedGpa = 0.0
        var weightedScore = 0.0
        var courseCount = 0

        Log.d(TAG, "═══ GPA 计算开始 ═══ 共 ${courses.size} 门课程")

        for (score in courses) {
            // 清理成绩字符串（去掉零宽字符等）
            val cleanedScore = cleanGradeString(score.score)
            val rawScore = score.score.trim()

            // 二等级制（通过/不通过）不参与 GPA 计算（西交教〔2015〕87号）
            if (cleanedScore == "通过" || cleanedScore == "不通过") {
                Log.d(TAG, "  跳过[二等级制]: ${score.courseName} score='$rawScore'")
                continue
            }

            // ── 先计算 GPA & 均分 ──
            // 优先用 API 返回的 gpa（>0 时），其次本地映射
            val apiGpa = score.gpa?.takeIf { it > 0.0 }
            val localGpa = com.xjtu.toolbox.score.ScoreReportApi.scoreToGpa(cleanedScore)
            val courseGpa = apiGpa ?: localGpa ?: 0.0

            // 均分计算：优先用数字成绩，等级制用映射值
            val numericScore = score.scoreValue
                ?: gradeToNumericScore(rawScore)
                ?: 0.0

            // ── 判断是否真正通过 ──
            // 不能仅依赖 passFlag（API 对等级制课程可能返回 null/false）
            val isPassed = score.passFlag
                    || courseGpa > 0.0       // 本地映射得到正值 → 通过
                    || numericScore >= 60.0  // 数字分 ≥60 → 通过

            Log.d(TAG, "  [${score.courseName}] rawScore='$rawScore' cleaned='$cleanedScore' | " +
                    "apiGpa=${score.gpa} localGpa=$localGpa → courseGpa=$courseGpa | " +
                    "scoreValue=${score.scoreValue} numericScore=$numericScore | " +
                    "passFlag=${score.passFlag} isPassed=$isPassed examProp='${score.examProp}' | " +
                    "credit=${score.coursePoint}")

            // 仅跳过"确实未通过"的初修课程
            if (!isPassed && score.examProp == "初修") {
                Log.d(TAG, "    → 跳过: 未通过的初修课程")
                continue
            }

            totalCredits += score.coursePoint
            weightedGpa += courseGpa * score.coursePoint
            weightedScore += numericScore * score.coursePoint
            courseCount++
            Log.d(TAG, "    → 纳入计算: weighted += $courseGpa * ${score.coursePoint}")
        }

        val gpa = if (totalCredits > 0) weightedGpa / totalCredits else 0.0
        val avg = if (totalCredits > 0) weightedScore / totalCredits else 0.0
        Log.d(TAG, "═══ GPA 计算完毕 ═══ GPA=${"%.4f".format(gpa)}, 均分=${"%.2f".format(avg)}, " +
                "计入课程=$courseCount/${ courses.size}, 总学分=$totalCredits")
        return GpaInfo(gpa = gpa, averageScore = avg, totalCredits = totalCredits, courseCount = courseCount)
    }
}
