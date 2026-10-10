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

/**
 * campus-api 的评教行 → 共享 [JudgeSource] 能吃的模型。
 *
 * 字段名来自 `/api/jwxt/evaluations` 的归一投影（`wjdm`/`jxbid`/`courseName`/`teacher`/`pgType`/
 * `finished`…），与 :app 直接用的上游键（`WJDM`/`KCM`/`BPJS`/`PGLXDM`）不同 —— 这是本文件存在的理由。
 */
data class CampusQuestionnaire(
    val wjdm: String,
    val jxbid: String,
    val courseName: String,
    val teacher: String,
    val pgType: String,
    val finished: Boolean,
)

/**
 * 评教的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `JudgeApi`）。
 *
 * ## 只读（[canSubmit] = false）
 *
 * campus-api 的 evaluations 模块**只读**：它自己的 `/api/jwxt/evaluations/status` 就写着
 * `readOnly: true, canSubmit: false, note: '只读：本服务**永不**实现提交/撤销评教'`。
 * 所以本类不实现 [JudgeSource.judge]、[JudgeSource.undo] 保持 null ⇒ 屏上不出现
 * 「一键全部好评」与撤回按钮（见 [JudgeSource.canSubmit]）。
 *
 * ## 学期口径**与 :app 一致**：都用上游的评教学期 `CSZA`
 *
 * `:app` 的 `JudgeApi` 从 `cxxtcs.do` 读 `CSZA` 当学期；campus-api 不传 `term` 时查的也是它。
 * ⚠️ 实测 `CSZA` **可能整学期为空**（2026-10-07：`CSZA=2025-2026-3` 全空，而 `2025-2026-1`
 * 有 14 条未评）—— 那时两端都显示「暂无待评课程」，这是**一致的**。
 * 不要为了「看起来有数据」去换学期：那就变成 Web 比 App 多显示一批东西了。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusJudgeApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
    /**
     * 查哪个学期；null（默认）= 上游评教学期 `CSZA`，与 :app 同一个口径。
     *
     * 这个参数**不是给屏用的**（屏一律不传，保持与 App 一致），而是给真数据探针
     * `CampusJudgeApiLiveTest` 用的：`CSZA` 常常整学期为空，探针要能指定一个**确实有数据**的
     * 学期来验证映射（实测 `2025-2026-1` 有 14 条未评）。
     *
     * ⚠️ serve 模式下这个参数换成契约 §5.3 的 `terms`（`/api/jwxt/evaluations` 收的是
     * `terms` 而不是 `term`），见 [fetchRows]。
     */
    private val term: String? = null,
) : JudgeSource<CampusQuestionnaire> {

    /**
     * 只读（见类 KDoc）。serve 的 `/api/jwxt/evaluations` 里 `canSubmit` 也是 `false`
     * （契约 §5.3 明文「只看不提交仍是默认」）⇒ 两端的答案一致。
     *
     * 它是一个普通属性（不是挂起调用）⇒ 不为了读一个恒为 `false` 的字段去联网；
     * P1 的提交/撤销端点落地时，这一条与 [judge] 要一起改。
     */
    override val canSubmit: Boolean get() = false

    override val confirmText: String get() = "，确定继续？"

    override suspend fun load(): Pair<List<CampusQuestionnaire>, List<CampusQuestionnaire>> {
        // 不传 term ⇒ campus-api 用上游评教学期 CSZA（与 :app 同一个口径，见类 KDoc）
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
        // 两个后端的**请求参数与行形状都不同**：
        //  - campus-api：`term`（单个学期）+ `rows[]`，行里是 `wjdm`/`courseName`/`pgType`；
        //  - serve（§5.3）：`terms`（逗号分隔、最多 4 个）+ `items[]`，行里是
        //    `wjdm`/`jxbid`/`course`/`teacher`/`type`/`finished`（`:data` 的问卷本体投影）——
        //    `parse` 那边按同一个 mode 读对应的键名。
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
        // serve 的行叫 `course` / `type`，旧路叫 `courseName` / `pgType`（同一件事的两个键名）
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
