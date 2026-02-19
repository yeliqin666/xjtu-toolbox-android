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

// ── 数据类 ──────────────────────────────

enum class ScoreSource { JWAPP, REPORT }

enum class CourseGroup(val label: String, val shortLabel: String) {
    GEN_CORE("通核", "通核"),
    GEN_ELECTIVE("通选", "通选"),
    MAJOR_ELECTIVE("专选", "专选"),
    CORE("核心", "核心"),
    OUT_OF_PLAN("方案外", "外");
}

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
    val source: ScoreSource = ScoreSource.JWAPP,
    val courseCategory: String? = null,
    val courseCode: String? = null,
    val courseGroup: CourseGroup? = null,
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

// ── API ──────────────────────────────

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

                val courseName = s.get("courseName").safeString()

                // 从 "课程名(课程号)" 提取 courseCode（CjcxApi enrichment 会覆盖）
                val extractedCode = Regex("\\(([A-Z]{2,}\\d{4,}\\w*)\\)$")
                    .find(courseName.trim())?.groupValues?.get(1)

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
                    gpa = s.get("gpa").safeDoubleOrNull(),
                    courseCode = extractedCode
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

    fun calculateGpaFromGrades(termScores: List<TermScore>): GpaInfo =
        calculateGpaForCourses(termScores.flatMap { it.scoreList })

    /**
     * GPA 计算：二等级制不参与，优先 xscjcx.do 精确值，fallback 本地映射。
     * passFlag 对等级制课程可能错误返回 false，需 GPA/分数二次兜底。
     */
    fun calculateGpaForCourses(courses: List<ScoreItem>): GpaInfo {
        var totalCredits = 0.0
        var weightedGpa = 0.0
        var weightedScore = 0.0
        var courseCount = 0

        for (score in courses) {
            val raw = score.score.trim()
            if (raw == "通过" || raw == "不通过") continue

            val courseGpa = score.gpa?.takeIf { it > 0.0 }
                ?: com.xjtu.toolbox.score.ScoreReportApi.scoreToGpa(raw)
                ?: 0.0
            val numeric = score.scoreValue ?: 0.0
            val passed = score.passFlag || courseGpa > 0.0 || numeric >= 60.0

            if (!passed && score.examProp == "初修") continue

            totalCredits += score.coursePoint
            weightedGpa += courseGpa * score.coursePoint
            weightedScore += numeric * score.coursePoint
            courseCount++
        }

        val gpa = if (totalCredits > 0) weightedGpa / totalCredits else 0.0
        val avg = if (totalCredits > 0) weightedScore / totalCredits else 0.0
        Log.d(TAG, "GPA=${"%.4f".format(gpa)}, 均分=${"%.2f".format(avg)}, $courseCount/${courses.size}门, ${totalCredits}学分")
        return GpaInfo(gpa, avg, totalCredits, courseCount)
    }
}
