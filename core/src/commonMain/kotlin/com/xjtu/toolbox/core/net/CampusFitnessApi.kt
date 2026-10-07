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

/**
 * 体测的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `FitnessApi`）。
 *
 * 与黄页/校历同一条理由：浏览器直连 `tyxylp.xjtu.edu.cn` 拿不到 CORS 头，
 * Web 的唯一数据路径是同源反代到 campus-api。上游是同一个体测系统
 * （campus-api 响应里的 `source` 字段），所以这里是「信封拆包 + 口径对齐」。
 *
 * ## 与 `:app` 端的**两处刻意差异**（都写在 `FitnessSource` 的 KDoc 里，这里给出处）
 *
 * 1. **姓名/学号拿不到**：campus-api 按隐私口径不投影这两个字段
 *    （`modules/fitness.js` 的 `parseScore` 只回 `score.studentYear/sex/grade` 那几项），
 *    所以 `studentName` / `studentNumber` 一律是空串；英雄卡会落到兜底文案「体测成绩」。
 * 2. **分项名两端必须同支**：campus-api 在 `sex` 缺失时给的是中性名
 *    （「力量（引体向上/仰卧起坐）」），而 `:app` 老实现是 `if (sex == "女") … else …` ⇒
 *    这里**不用** campus-api 给的名字，一律用共享的 [fitnessItemName] 重新定名，
 *    保证同一个人的同一项在两端叫同一个名字。（数据本身照 campus-api 给的用。）
 *
 * 「没成绩」与「未开放」两态的区分：campus-api 已经把上游的 `status:-4` 归一成
 * `reason:"year-not-open"`，这里映射成与 `:app` 同一句话给用户。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusFitnessApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
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
            // campus-api 不投影这两个字段（隐私口径），见 KDoc。
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

    /** 拆 `{code,data}` 信封；`data` 缺失（含 `code!=0`）按校历/黄页那套报法显式失败。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
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
