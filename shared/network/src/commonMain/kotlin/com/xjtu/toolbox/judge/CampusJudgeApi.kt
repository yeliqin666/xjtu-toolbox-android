package com.xjtu.toolbox.judge

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.serveAction
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 评教行 → 共享 [JudgeSource] 能吃的模型。campus-api 形态填 wjdm / jxbid / pgType，serve 形态填 responseId / tag。 */
data class CampusQuestionnaire(
    val wjdm: String,
    val jxbid: String,
    val courseName: String,
    val teacher: String,
    val pgType: String,
    val finished: Boolean,
    /** QMS 答卷 id（serve 形态）。 */
    val responseId: String = "",
    /** serve 端算好的标签（期中 / 期末 / 过程评教）。 */
    val tag: String = "",
)

/** Web 端评教取数：serve 形态走 QMS，campus-api 形态只读。 */
class CampusJudgeApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读 serve 契约的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
    /** 查哪个学期；null = 上游评教学期 `CSZA`。 */
    private val term: String? = null,
) : JudgeSource<CampusQuestionnaire> {

    /** serve 形态能提交（`POST /api/jwxt/evaluations/submit`）；campus-api 没有提交端点。屏上的「一键评教」块按它画不画。 */
    override val canSubmit: Boolean get() = mode == ApiMode.SERVE

    /** serve 列表带回的主观题评语（没加载过就是空）。 */
    private var serveComment: String = ""

    override val confirmText: String
        get() = if (serveComment.isNotBlank()) "（主观题统一填写「$serveComment」），确定继续？" else "，确定继续？"

    override suspend fun load(): Pair<List<CampusQuestionnaire>, List<CampusQuestionnaire>> {
        val all = fetchRows(term = term).map { parse(it) }
        return all.filter { !it.finished } to all.filter { it.finished }
    }

    override fun card(q: CampusQuestionnaire): JudgeCard =
        if (mode == ApiMode.SERVE) {
            JudgeCard(key = q.responseId, course = q.courseName, teacher = q.teacher, tag = q.tag.ifBlank { "评教" })
        } else {
            JudgeCard(
                key = "${q.wjdm}_${q.jxbid}",
                course = q.courseName,
                teacher = q.teacher,
                tag = when (q.pgType) { "01" -> "期末评教"; "05" -> "过程评教"; else -> "评教" },
            )
        }

    /**
     * 一键评教：一份答卷一发 `POST /api/jwxt/evaluations/submit`。
     *
     * `submitted:false` 不是失败：已评过 ⇒ 200 + `submitted:false`（幂等），屏上照样从「未评」里移走。
     */
    override suspend fun judge(q: CampusQuestionnaire) {
        if (mode != ApiMode.SERVE) error("Web 端评教是只读的（campus-api 永不实现提交）")
        val body = buildJsonObject { put("responseId", q.responseId) }.toString()
        val outcome = client.serveAction(baseUrl, SUBMIT_PATH, body)
        if (!outcome.ok) error("提交评教：${outcome.message}")
    }

    private suspend fun fetchRows(term: String?): List<JsonObject> {
        if (mode == ApiMode.SERVE) {
            val data = getData("/api/jwxt/evaluations", "finished" to "all")
            serveComment = data["comment"].safeString().trim()
            return data.arr("items").orEmpty().mapNotNull { if (it.isObject) it.jsonObject else null }
        }
        val data = getData(
            "/api/jwxt/evaluations",
            "finished" to "all",
            "term" to term.orEmpty(),
        )
        return data.arr("rows").orEmpty().mapNotNull { if (it.isObject) it.jsonObject else null }
    }

    private fun parse(item: JsonObject): CampusQuestionnaire =
        if (mode == ApiMode.SERVE) {
            CampusQuestionnaire(
                wjdm = "",
                jxbid = "",
                courseName = item["course"].safeString().trim(),
                teacher = item["teacher"].safeString().trim(),
                pgType = "",
                finished = item["finished"].safeBoolean(),
                responseId = item["responseId"].safeString().trim(),
                tag = item["tag"].safeString().trim(),
            )
        } else {
            CampusQuestionnaire(
                wjdm = item["wjdm"].safeString().trim(),
                jxbid = item["jxbid"].safeString().trim(),
                courseName = item["courseName"].safeString().trim(),
                teacher = item["teacher"].safeString().trim(),
                pgType = item["pgType"].safeString().trim(),
                finished = item["finished"].safeBoolean(),
            )
        }

    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
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

/** 提交评教：请求体 `{responseId}`，serve 端按最好一档填答后提交。 */
private const val SUBMIT_PATH = "/api/jwxt/evaluations/submit"

