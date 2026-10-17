package com.xjtu.toolbox.judge

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.serveData
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** campus-api 的评教行 → 共享 [JudgeSource] 能吃的模型。 */
data class CampusQuestionnaire(
    val wjdm: String,
    val jxbid: String,
    val courseName: String,
    val teacher: String,
    val pgType: String,
    val finished: Boolean,
)

/** 评教的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `JudgeApi`）。 */
class CampusJudgeApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
    /** 查哪个学期；null = 上游评教学期 `CSZA`。 */
    private val term: String? = null,
) : JudgeSource<CampusQuestionnaire> {

    /** 只读（见类 KDoc）。 */
    override val canSubmit: Boolean get() = false

    override val confirmText: String get() = "，确定继续？"

    override suspend fun load(): Pair<List<CampusQuestionnaire>, List<CampusQuestionnaire>> {
        val all = fetchRows(term = term).map { parse(it) }
        return all.filter { !it.finished } to all.filter { it.finished }
    }

    override fun card(q: CampusQuestionnaire): JudgeCard = JudgeCard(
        key = "${q.wjdm}_${q.jxbid}",
        course = q.courseName,
        teacher = q.teacher,
        // 与 :app 的 `UndergraduateJudgeSource.card` 用同一套文案（campus-api 的 pgTypeLabel 措辞不同）
        tag = when (q.pgType) { "01" -> "期末评教"; "05" -> "过程评教"; else -> "评教" },
    )

    /** 只读：见类 KDoc。不实现 [JudgeSource.judge] 就等于「这一端交不了」。 */
    override suspend fun judge(q: CampusQuestionnaire): Unit = error("Web 端评教是只读的（campus-api 永不实现提交）")

    private suspend fun fetchRows(term: String?): List<JsonObject> {
        if (mode == ApiMode.SERVE) {
            val data = getData(
                "/api/jwxt/evaluations",
                "terms" to term.orEmpty(),
                "finished" to "all",
            )
            return data.arr("items").orEmpty().mapNotNull { if (it.isObject) it.jsonObject else null }
        }
        val data = getData(
            "/api/jwxt/evaluations",
            "finished" to "all",
            "term" to term.orEmpty(),
        )
        return data.arr("rows").orEmpty().mapNotNull { if (it.isObject) it.jsonObject else null }
    }

    private fun parse(item: JsonObject): CampusQuestionnaire = CampusQuestionnaire(
        wjdm = item["wjdm"].safeString().trim(),
        jxbid = item["jxbid"].safeString().trim(),
        courseName = item[if (mode == ApiMode.SERVE) "course" else "courseName"].safeString().trim(),
        teacher = item["teacher"].safeString().trim(),
        pgType = item[if (mode == ApiMode.SERVE) "type" else "pgType"].safeString().trim(),
        finished = item["finished"].safeBoolean(),
    )

    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("评教", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> if (v.isNotEmpty()) parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 评教返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            error("campus-api 评教失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 评教返回缺少 data：${text.take(120)}")
    }
}
