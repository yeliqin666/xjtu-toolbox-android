package com.xjtu.toolbox.jwapp

import android.util.Log
import com.google.gson.Gson
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.runBlocking
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "JwappGPA"

// ── 数据类 ──────────────────────────────

enum class ScoreSource { JWAPP, REPORT }

enum class CourseGroup(val label: String, val shortLabel: String) {
    GEN_CORE("通核", "通核"),
    GEN_ELECTIVE("通选", "通选");
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
        gpa = com.xjtu.toolbox.util.ScoreCalculator.courseGpa(this) ?: 0.0,
        passFlag = com.xjtu.toolbox.util.ScoreCalculator.isPassed(this),
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

class JwappApi(private val site: SiteSession) {

    // [关键] 必须 https。OkHttp 在 http→https 跨协议重定向时**自动剥离 Authorization header**（防 token leak），
    // 校园网直连模式下 jwapp 把 http 请求 302 到 https → token 丢失 → 服务端返 401 "Authentication error"。
    // WebVPN 模式下因为请求经 webvpn.xjtu.edu.cn（https 一跳到位）而能正常工作。
    private val baseUrl = "https://jwapp.xjtu.edu.cn"
    private val gson = Gson()
    private val browserUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun authenticatedRequest(url: String): okhttp3.Request.Builder =
        okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", browserUa)

    private fun execute(request: okhttp3.Request.Builder): String =
        runBlocking { site.executeWithReAuth(request.build()) }.use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }

    // [J1] TimeTableBasis 内存缓存（学期内不变，避免重复网络请求）
    // TTL 1小时：防止 App 长时间运行跨学期后返回旧数据
    private var cachedBasis: TimeTableBasis? = null
    private var cachedBasisTime: Long = 0L
    private val BASIS_TTL_MS = 60L * 60 * 1000L  // 1 小时

    fun getGrade(termCode: String? = null): List<TermScore> {
        val code = termCode ?: "*"
        val json = gson.toJson(mapOf("termCode" to code))
        val body = json.toRequestBody("application/json".toMediaType())

        val request = authenticatedRequest("$baseUrl/api/biz/v410/score/termScore")
            .post(body)

        val responseBody = execute(request)
        val root = responseBody.safeParseJsonObject()

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

        val request = authenticatedRequest("$baseUrl/api/biz/v410/score/scoreDetail")
            .post(body)

        val responseBody = execute(request)
        Log.d(TAG, "scoreDetail id=$courseId body=${responseBody.take(240)}")
        val root = responseBody.safeParseJsonObject()

        val resultCode = root.get("code").safeInt(-1)
        val msg = root.get("msg").safeString("服务器错误 ($resultCode)")
        val dataEl = root.get("data")
        if (resultCode != 200 || dataEl == null || dataEl.isJsonNull || !dataEl.isJsonObject) {
            Log.w(TAG, "scoreDetail empty/fail code=$resultCode msg=$msg data=${dataEl}")
            if (resultCode == 200 || resultCode == 401 || resultCode == 404 || isNoScoreDetailMessage(msg)) {
                throw NoScoreDetailException(msg.ifBlank { "该课程暂无分项成绩" })
            }
            throw RuntimeException(msg)
        }

        val data = dataEl.asJsonObject

        val itemEl = data.get("itemList")
        val items = if (itemEl == null || itemEl.isJsonNull || !itemEl.isJsonArray) {
            emptyList()
        } else itemEl.asJsonArray.map { el ->
            val item = el.asJsonObject
            val percentStr = item.get("itemPercent").safeString("0")
            val percent = percentStr.trimEnd('%').toDoubleOrNull()?.let { it / 100.0 } ?: 0.0
            ScoreDetailItem(
                itemName = item.get("itemName").safeString(),
                itemPercent = percent,
                itemScore = item.get("itemScore").safeString(),
                itemScoreValue = item.get("itemScore").safeString().toDoubleOrNull()
            )
        }

        val rawScore = data.get("score").safeString()
        val serverGpa = data.get("gpa").safeDouble()
        // 如果服务器 GPA 为 0 但课程已通过，用本地映射兜底
        val effectiveGpa = if (serverGpa > 0.0) serverGpa
            else com.xjtu.toolbox.util.ScoreCalculator.scoreToGpa(rawScore) ?: 0.0

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

        val request = authenticatedRequest("$baseUrl/api/biz/v410/score/scoreAnalyze")
            .post(body)

        val responseBody = execute(request)
        val root = responseBody.safeParseJsonObject()

        val resultCode = root.get("code").asInt
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.asString ?: "服务器错误 ($resultCode)")
        }

        val data = root.getAsJsonObject("data")

        val distEl = data.get("scoreDist")
        val dist = if (distEl == null || distEl.isJsonNull || !distEl.isJsonArray) emptyList()
        else distEl.asJsonArray.map { el ->
            val d = el.asJsonObject
            ScoreDistRange(
                range = d.get("range").safeString(),
                num = d.get("num").safeInt()
            )
        }

        return ScoreRank(
            defeatPercent = data.get("defeatPercent").safeDoubleOrNull(),
            scoreHigh = data.get("scoreHigh").safeDoubleOrNull(),
            scoreAvg = data.get("scoreAvg").safeDoubleOrNull(),
            scoreLow = data.get("scoreLow").safeDoubleOrNull(),
            scoreDist = dist
        )
    }

    fun getTimeTableBasis(): TimeTableBasis {
        // [J1] 优先返回缓存（1h TTL，防跨学期过期）
        cachedBasis?.let {
            if (System.currentTimeMillis() - cachedBasisTime < BASIS_TTL_MS) return it
            cachedBasis = null  // 已过期，清除
        }

        val request = authenticatedRequest("https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time")
            .get()

        val body = execute(request)
        val root = body.safeParseJsonObject()

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
        ).also { cachedBasis = it; cachedBasisTime = System.currentTimeMillis() }
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
    fun calculateGpaForCourses(courses: List<ScoreItem>): GpaInfo =
        com.xjtu.toolbox.util.ScoreCalculator.calculateGpaForCourses(courses)
}

internal fun isNoScoreDetailMessage(msg: String?): Boolean {
    if (msg.isNullOrBlank()) return false
    return listOf("无分项", "没有分项", "暂无", "不存在", "未查询", "无明细", "无细则", "没有明细", "无成绩").any { it in msg }
}
