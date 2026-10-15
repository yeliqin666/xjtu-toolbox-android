package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.fitness.FITNESS_ITEM_DEFS
import com.xjtu.toolbox.fitness.FitnessItem
import com.xjtu.toolbox.fitness.FitnessScore
import com.xjtu.toolbox.fitness.FitnessSource
import com.xjtu.toolbox.fitness.FitnessYear
import com.xjtu.toolbox.fitness.fitnessItemName
import com.xjtu.toolbox.fitness.formatFitnessScore
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

/** 体测的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `FitnessApi`）。 */
class CampusFitnessApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.2 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : FitnessSource {

    override suspend fun years(): List<FitnessYear> {
        val data = getData("/api/fitness/years")
        val list = data.arr("years") ?: return emptyList()
        return list.mapNotNull { element ->
            if (!element.isObject) return@mapNotNull null
            val item = element.jsonObject
            val yearNum = item["yearNum"].safeString().trim()
            if (yearNum.isEmpty()) return@mapNotNull null
            FitnessYear(
                yearNum = yearNum,
                name = item["name"].safeString().ifBlank { yearNum },
                checked = item["checked"].safeBoolean(),
            )
        }
    }

    override suspend fun score(yearNum: String): FitnessScore {
        if (mode == ApiMode.SERVE) return serveScore(yearNum)
        val data = getData("/api/fitness/score", "year" to yearNum)
        if (!data["hasScore"].safeBoolean()) {
            // 与 :app 的 `fetchData` 同一句话：优先用上游 info，没有就给一句通用的。
            val info = data["info"].safeString()
            throw RuntimeException(
                info.takeIf { it.isNotBlank() && it != "查询成功" } ?: "该学年暂无体测数据"
            )
        }
        val score = data["score"] as? JsonObject
        val sex = score?.get("sex").safeString().orEmpty()

        // campus-api 已按 ITEM_DEFS 顺序给 items，这里仍按共享表定序并重新定名（见 KDoc 第 2 条）。
        val byKey = data.arr("items")?.mapNotNull { element ->
            if (!element.isObject) return@mapNotNull null
            val item = element.jsonObject
            val key = item["key"].safeString()
            if (key.isBlank()) return@mapNotNull null
            key to item
        }.orEmpty().toMap()

        val items = FITNESS_ITEM_DEFS.map { (key, _) ->
            val item = byKey[key]
            FitnessItem(
                name = fitnessItemName(key, sex),
                value = formatFitnessScore(item?.get("value").safeString()).ifBlank { "未测" },
                grade = item?.get("grade").safeString().ifBlank { "缺项" },
                tone = item?.get("gradeClass").safeString().orEmpty(),
            )
        }

        return FitnessScore(
            studentNumber = "",
            studentName = "",
            totalScore = formatFitnessScore(score?.get("total").safeString()).ifBlank { "--" },
            totalGrade = score?.get("totalGrade").safeString().ifBlank { "未测" },
            reportType = score?.get("reportType").safeString().orEmpty(),
            reportStatus = score?.get("reportStatus").safeString().orEmpty(),
            sex = sex,
            grade = score?.get("grade").safeString(),
            items = items,
        )
    }

    /** serve 模式（契约 §5.2） */
    private suspend fun serveScore(yearNum: String): FitnessScore {
        val data = client.serveData("查询体测成绩", baseUrl, "/api/fitness/score", listOf("year" to yearNum))
        val items = data.arr("items").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            FitnessItem(
                name = item["name"].safeString(),
                value = formatFitnessScore(item["value"].safeString()).ifBlank { "未测" },
                grade = item["grade"].safeString().ifBlank { "缺项" },
                tone = item["tone"].safeString(),
            )
        }
        return FitnessScore(
            studentNumber = data["studentNumber"].safeString(),
            studentName = data["studentName"].safeString(),
            totalScore = formatFitnessScore(data["totalScore"].safeString()).ifBlank { "--" },
            totalGrade = data["totalGrade"].safeString().ifBlank { "未测" },
            reportType = data["reportType"].safeString(),
            reportStatus = data["reportStatus"].safeString(),
            sex = data["sex"].safeString(),
            grade = data["grade"].safeString(),
            items = items,
        )
    }

    /** 拆 `{code,data}` 信封；`data` 缺失（含 `code!=0`）按校历/黄页那套报法显式失败。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("加载体测", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 体测返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // need-login 也在这一支里：Web 端没有 CAS 会话可重登，所以照实报错，
            // 不抛 SessionExpiredFailure（那会让屏幕白白退页，见 auth/AuthExpiry.kt）。
            error("campus-api 体测失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 体测返回缺少 data：${text.take(120)}")
    }
}
