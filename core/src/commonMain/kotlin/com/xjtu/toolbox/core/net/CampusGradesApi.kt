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

/**
 * 成绩的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `ScoreReportApi`，帆软报表）。
 *
 * 与黄页/校历/体测同一条理由：浏览器直连教务拿不到 CORS 头，Web 只能走同源反代。
 *
 * ## 上游**不是**同一个接口（这一点与前三屏不同，如实记下来）
 *
 * | 端 | 上游 | 形状 |
 * |---|---|---|
 * | `:app` | `jwxt` 帆软报表 `frReport2`（HTML 表格） | 一学期一张表，行 = 课程名 / 学分 / 成绩文本 |
 * | Web | campus-api `/api/jwxt/grades`（教务精确成绩 `cjcx/xscjcx`） | 每门课 `course` + `score` + `parts` + `meta` |
 *
 * 所以这里做的**不是**「换个信封」，而是**字段对齐**，逐条说明（改之前先看）：
 *  - `term` ← 行里的 `term`（campus-api 已按教务的学期代码归一，与帆软报表标题解析出的
 *    `2024-2025-1` 同一个口径）；
 *  - `courseName` ← `course.name`；`coursePoint` ← `course.credit`；
 *  - `score` ← **`score.level` 优先，空则 `score.total`**：成绩报表是给人看的那一份，
 *    等级制课程在那里显示等级；campus-api 两套都带出（`level` 是 `DJCJMC`、`total` 是 `ZCJ`），
 *    所以「有等级就用等级」才与报表页一致；
 *  - `gpa` ← `score.gpa`（上游的 `XFJD`，精确值；`:app` 侧是拿成绩文本过
 *    `ScoreCalculator.scoreToGpa` 本地映射 —— 两者在百分制课程上一致，在等级制课程上
 *    campus-api 更精确，这也是其文档点名的差异）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
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
    /**
     * serve 模式（契约 §5.3）：`{grades:[{courseName,coursePoint,score,gpa,term}]}`。
     *
     * 旧路是 campus-api 的嵌套投影（`rows[].course.name`、`score.level` 优先、`score.gpa`），
     * `:server` 给的是 `:data` 的 [ReportedGrade] 本体 —— 「有等级就用等级」与「`gpa` 可空」这两条
     * 口径**已经在 `:data` 里做过**（见 [ReportedGrade] 的 KDoc），所以这里只搬字段，
     * 不把那条判据再实现一遍（两份实现迟早会分叉）。
     *
     * `?all=1` 两个后端都认（serve 的默认本来也是全量，显式带上更不含糊）。
     */
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
