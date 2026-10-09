package com.xjtu.toolbox.jwapp

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
// ⚠️ 成绩/GPA 的模型（`ScoreSource` / `CourseGroup` / `ScoreItem` / `ScoreDetail` / `TermScore` /
// `GpaInfo` 与 `NoScoreDetailException`）已经搬进 **:core** 的 `jwapp/ScoreModels.kt` ——
// 因为 GPA 算法（`score/ScoreCalculator`）现在在那边，模型留在这里两边都编不了。
// 本文件只剩 okhttp 取数。


class JwappApi(private val site: SiteSession) {

    // [关键] 必须 https。OkHttp 在 http→https 跨协议重定向时**自动剥离 Authorization header**（防 token leak），
    // 校园网直连模式下 jwapp 把 http 请求 302 到 https → token 丢失 → 服务端返 401 "Authentication error"。
    // WebVPN 模式下因为请求经 webvpn.xjtu.edu.cn（https 一跳到位）而能正常工作。
    private val baseUrl = "https://jwapp.xjtu.edu.cn"

    internal fun authenticatedRequest(url: String): okhttp3.Request.Builder =
        okhttp3.Request.Builder().url(url)

    internal suspend fun execute(request: okhttp3.Request.Builder): String =
        site.executeWithReAuth(request.build()).use { response ->
            response.body.string()
        }

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

    /** 移动教务认的当前学期代码，首页成绩卡只报本学期用。 */
    suspend fun getCurrentTerm(): String {
        val root = execute(authenticatedRequest("$baseUrl/api/biz/v410/common/school/time").get()).safeParseJsonObject()
        val resultCode = root.get("code").intValue
        if (resultCode != 200) throw RuntimeException(root.get("msg")?.stringValue ?: "服务器错误 ($resultCode)")
        // 可能是 {code, data:{...}}，也可能直接平铺字段
        return (root.obj("data") ?: root).get("xnxqdm").safeString()
    }

    suspend fun getTermList(): List<Pair<String, String>> {
        val allGrades = getGrade(null)
        return allGrades.map { it.termCode to it.termName }
    }
}

internal fun isNoScoreDetailMessage(msg: String?): Boolean {
    if (msg.isNullOrBlank()) return false
    return listOf("无分项", "没有分项", "暂无", "不存在", "未查询", "无明细", "无细则", "没有明细", "无成绩").any { it in msg }
}
