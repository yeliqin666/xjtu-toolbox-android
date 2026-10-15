package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportSource
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 成绩的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `ScoreReportApi`，帆软报表）。 */
class CampusGradesApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : ScoreReportSource {

    override suspend fun grades(): List<ReportedGrade> {
        // 两个后端的上游**不是**同一张报表（见类 KDoc），投影也不同 ⇒ 各走各的解析
        if (mode == ApiMode.SERVE) return serveGrades()
        val text = client.get("$baseUrl/api/jwxt/grades") {
            parameter("all", "1")
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 成绩返回不是 JSON 对象")
        val data = envelope["data"] as? JsonObject
            ?: error("campus-api 成绩返回缺少 data：${text.take(120)}")
        val rows = data.arr("rows").orEmpty()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val course = row.obj("course") ?: return@mapNotNull null
            val name = course["name"].safeString().trim()
            if (name.isEmpty()) return@mapNotNull null
            val score = row.obj("score")
            val level = score?.get("level").safeString().trim()
            ReportedGrade(
                courseName = name,
                coursePoint = course["credit"].safeDouble(),
                // 等级制课程显示等级（与报表页一致），其余用精确总评
                score = level.ifBlank { score?.get("total").safeString().trim() },
                gpa = score?.get("gpa").safeDoubleOrNull(),
                term = row["term"].safeString(),
            )
        }
    }
    /** serve 模式（契约 §5.3）：`{grades:[{courseName,coursePoint,score,gpa,term}]}`。 */
    private suspend fun serveGrades(): List<ReportedGrade> {
        val data = client.serveData("加载成绩", baseUrl, "/api/jwxt/grades", listOf("all" to "1"))
        return data.arr("grades").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val name = row["courseName"].safeString().trim()
            if (name.isEmpty()) return@mapNotNull null
            ReportedGrade(
                courseName = name,
                coursePoint = row["coursePoint"].safeDouble(),
                score = row["score"].safeString().trim(),
                gpa = row["gpa"].safeDoubleOrNull(),
                term = row["term"].safeString(),
            )
        }
    }


}
