package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.network.MOBILE_UA
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.requireObj
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.isArray
import com.xjtu.toolbox.util.obj
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import com.xjtu.toolbox.util.redactBody
import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "JwappGPA"

// ── 数据类 ──────────────────────────────

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

    internal fun authenticatedRequest(url: String): okhttp3.Request.Builder =
        okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)

    internal suspend fun execute(request: okhttp3.Request.Builder): String =
        site.executeWithReAuth(request.build()).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }

    // [J1] TimeTableBasis 内存缓存（学期内不变，避免重复网络请求）
    // TTL 1小时：防止 App 长时间运行跨学期后返回旧数据
    private var cachedBasis: TimeTableBasis? = null
    private var cachedBasisTime: Long = 0L
    private val BASIS_TTL_MS = 60L * 60 * 1000L  // 1 小时

    suspend fun getGrade(termCode: String? = null): List<TermScore> {
        val code = termCode ?: "*"
        val json = buildJsonObject { put("termCode", code) }.toString()
        val body = json.toRequestBody("application/json".toMediaType())

        val request = authenticatedRequest("$baseUrl/api/biz/v410/score/termScore")
            .post(body)

        val responseBody = execute(request)
        val root = responseBody.safeParseJsonObject()

        val resultCode = root.get("code").intValue
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.stringValue ?: "服务器错误 ($resultCode)")
        }

        val termScoreList = root.requireObj("data")
            .requireArr("termScoreList")

        return termScoreList.map { termElement ->
            val termObj = termElement.jsonObject
            val scores = termObj.requireArr("scoreList").map { scoreEl ->
                val s = scoreEl.jsonObject
                val rawScore = s.get("score").safeString()
                val numericScore = rawScore.toDoubleOrNull()

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

    suspend fun getDetail(courseId: String): ScoreDetail {
        val json = buildJsonObject { put("id", courseId) }.toString()
        val body = json.toRequestBody("application/json".toMediaType())

        val request = authenticatedRequest("$baseUrl/api/biz/v410/score/scoreDetail")
            .post(body)

        val responseBody = execute(request)
        Log.d(TAG, "scoreDetail id=$courseId body=${responseBody.redactBody(240)}")
        val root = responseBody.safeParseJsonObject()

        val resultCode = root.get("code").safeInt(-1)
        val msg = root.get("msg").safeString("服务器错误 ($resultCode)")
        val dataEl = root.get("data")
        if (resultCode != 200 || dataEl == null || dataEl.isNull || !dataEl.isObject) {
            Log.w(TAG, "scoreDetail empty/fail code=$resultCode msg=$msg data=${dataEl}")
            if (resultCode == 200 || resultCode == 401 || resultCode == 404 || isNoScoreDetailMessage(msg)) {
                throw NoScoreDetailException(msg.ifBlank { "该课程暂无分项成绩" })
            }
            throw RuntimeException(msg)
        }

        val data = dataEl.jsonObject

        val itemEl = data.get("itemList")
        val items = if (itemEl == null || itemEl.isNull || !itemEl.isArray) {
            emptyList()
        } else itemEl.jsonArray.map { el ->
            val item = el.jsonObject
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
            else com.xjtu.toolbox.score.ScoreCalculator.scoreToGpa(rawScore) ?: 0.0

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

    suspend fun getTimeTableBasis(): TimeTableBasis {
        // [J1] 优先返回缓存（1h TTL，防跨学期过期）
        cachedBasis?.let {
            if (System.currentTimeMillis() - cachedBasisTime < BASIS_TTL_MS) return it
            cachedBasis = null  // 已过期，清除
        }

        val request = authenticatedRequest("https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time")
            .get()

        val body = execute(request)
        val root = body.safeParseJsonObject()

        val resultCode = root.get("code").intValue
        if (resultCode != 200) {
            throw RuntimeException(root.get("msg")?.stringValue ?: "服务器错误 ($resultCode)")
        }

        // API 可能返回 {code, data:{...}} 或直接平铺字段
        val obj = root.obj("data") ?: root

        return TimeTableBasis(
            termCode = obj.get("xnxqdm").safeString(),
            termName = obj.get("xnxqmc").safeString(),
            maxWeekNum = obj.get("maxWeekNum").safeInt(),
            maxSection = obj.get("maxSection").safeInt(),
            todayWeekDay = obj.get("todayWeekDay").safeInt(),
            todayWeekNum = obj.get("todayWeekNum").safeInt()
        ).also { cachedBasis = it; cachedBasisTime = System.currentTimeMillis() }
    }

    suspend fun getCurrentTerm(): String = getTimeTableBasis().termCode

    suspend fun getTermList(): List<Pair<String, String>> {
        val allGrades = getGrade(null)
        return allGrades.map { it.termCode to it.termName }
    }
}

internal fun isNoScoreDetailMessage(msg: String?): Boolean {
    if (msg.isNullOrBlank()) return false
    return listOf("无分项", "没有分项", "暂无", "不存在", "未查询", "无明细", "无细则", "没有明细", "无成绩").any { it in msg }
}
