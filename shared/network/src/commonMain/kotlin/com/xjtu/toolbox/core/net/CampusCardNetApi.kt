package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.card.CampusCardSnapshot
import com.xjtu.toolbox.card.CampusCardSource
import com.xjtu.toolbox.card.CardInfo
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.card.merchantFromResume
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** 校园卡的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `AppCampusCardSource`）。 */
class CampusCardNetApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : CampusCardSource {

    override suspend fun card(): CardInfo {
        // 两个后端的形状不同：campus-api 给 `{card:{…}}`，serve 给**扁平**的一份（见 serveCard）
        if (mode == ApiMode.SERVE) return serveCard()
        val data = getData("/api/card/balance")
        val card = data["card"] as? JsonObject
            ?: error("campus-api 校园卡返回缺少 card")
        return CardInfo(
            account = card["account"].safeString(),
            name = card["name"].safeString(),
            studentNo = card["studentNo"].safeString(),
            balance = card["balance"].safeDouble(),
            pendingAmount = card["unsettled"].safeDouble(),
            lostFlag = card["lost"].safeBoolean(),
            frozenFlag = card["frozen"].safeBoolean(),
            expireDate = card["expireDate"].safeString(),
            // 见类 KDoc：显示用的是卡名，不是那个 "800" 编号
            cardType = card["cardName"].safeString(),
            // campus-api 不投影学院 ⇒ 留空（不编）
            department = "",
        )
    }

    override suspend fun transactions(
        from: LocalDate,
        to: LocalDate,
        page: Int,
        pageSize: Int,
    ): Pair<Int, List<Transaction>> {
        if (mode == ApiMode.SERVE) return serveTransactions(from, to, page, pageSize)
        val data = getData(
            "/api/card/transactions",
            "from" to from.toString(),
            "to" to to.toString(),
            "page" to page.toString(),
            "size" to pageSize.toString(),
        )
        val total = data["total"]?.safeInt() ?: 0
        val rows = data.arr("rows").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val channel = row["channel"].safeString()
            val merchant = row["merchant"].safeString().takeIf { it.isNotBlank() } ?: merchantFromResume(channel)
            Transaction(
                time = row["time"].safeString(),
                merchant = merchant,
                // 见类 KDoc：方向在 signed 里，amount 恒为正数
                amount = row["signed"].safeDouble(),
                balance = row["balance"].safeDouble(),
                type = row["type"].safeString(),
                description = channel,
            )
        }
        return total to rows
    }

    override suspend fun snapshot(
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ): CampusCardSnapshot? {
        val held = session ?: return null
        return held.copy(
            transactions = held.transactions.filter { tx ->
                val date = tx.time.take(10)
                date >= from.toString() && date <= to.toString()
            },
        )
    }

    /** Web 没有首页卡片与桌面小组件要读的那组 key ⇒ 空实现（见类 KDoc）。 */
    override suspend fun persistCard(card: CardInfo, accountId: String?) = Unit

    override suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ) {
        session = CampusCardSnapshot(
            cardInfo = card,
            transactions = transactions,
            rangeStart = from.toString(),
            rangeEnd = to.toString(),
            savedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    /** 本次会话里最后落盘的那一份；刷新页面即失效。 */
    private var session: CampusCardSnapshot? = null

    // ─── serve 模式（契约 §5.3）────────────────────────────────────────────

    /** serve 模式（契约 §5.3） */
    private suspend fun serveCard(): CardInfo {
        val data = getData("/api/card/balance")
        return CardInfo(
            account = "",
            name = "",
            studentNo = "",
            balance = data["balance"].safeDouble(),
            pendingAmount = data["pendingAmount"].safeDouble(),
            lostFlag = data["lostFlag"].safeBoolean(),
            frozenFlag = data["frozenFlag"].safeBoolean(),
            expireDate = data["expireDate"].safeString(),
            cardType = data["cardType"].safeString(),
            department = data["department"].safeString(),
        )
    }

    /** serve 模式（契约 §5.3） */
    private suspend fun serveTransactions(
        from: LocalDate,
        to: LocalDate,
        page: Int,
        pageSize: Int,
    ): Pair<Int, List<Transaction>> {
        val data = getData(
            "/api/card/transactions",
            "from" to from.toString(),
            "to" to to.toString(),
            "page" to page.toString(),
            "size" to pageSize.toString(),
        )
        val total = data["total"].safeInt()
        val rows = data.arr("transactions").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            Transaction(
                time = row["time"].safeString(),
                merchant = row["merchant"].safeString(),
                amount = row["amount"].safeDouble(),
                balance = row["balance"].safeDouble(),
                type = row["type"].safeString(),
                description = row["description"].safeString(),
            )
        }
        return total to rows
    }


    /** 拆 `{code,data}` 信封；`code!=0` 按体测/校历那套报法显式失败。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("校园卡", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 校园卡返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // need-login 也照实报错：Web 端没有 CAS 会话可重登，抛 SessionExpiredFailure 只会让屏白白退页。
            error("campus-api 校园卡失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 校园卡返回缺少 data：${text.take(120)}")
    }
}
