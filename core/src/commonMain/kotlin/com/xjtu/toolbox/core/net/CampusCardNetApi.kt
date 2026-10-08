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

/**
 * 校园卡的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `AppCampusCardSource`）。
 *
 * 端点与 :app 是同一份上游数据（campus-api 自己登 ncard、抓 `/berserker-app/ykt/tsm/queryCard` 与
 * `/berserker-search/search/personal/turnover`），只是**归一层**：字段名不再是上游的原始键。
 * 逐字段映射如下（左边是 [CardInfo] / [Transaction]，右边是 campus-api 的键，2026-10-08 实测形状）：
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `CardInfo.account` | `card.account` | 与 :app 的 `site.localToken["card_account"]` 同义（一卡通号） |
 * | `CardInfo.name` | `card.name` | :app 从 `user_name` 拿；campus-api 白名单里给 `name` |
 * | `CardInfo.studentNo` | `card.studentNo` | ⚠️ **已掩码**（只留首位与末位）；:app 那份是完整的，Web 只能给掩码 |
 * | `CardInfo.balance` | `card.balance` | campus-api 已经把「分」换成「元」（:app 是自己 `elec_accamt/100`） |
 * | `CardInfo.pendingAmount` | `card.unsettled` | ← 上游 `unsettle_amount` |
 * | `CardInfo.lostFlag` | `card.lost` | ← 上游 `lostflag`。⚠️ :app 的 `lostFlag` 取的是 `barflag`（它的注释写「是否挂失」），campus-api 的 `lost` 取 `lostflag`、`barred` 取 `barflag` —— 正常卡上两者都是 false，只在异常卡上可能不同口径 |
 * | `CardInfo.frozenFlag` | `card.frozen` | ← 上游 `freezeflag` |
 * | `CardInfo.expireDate` | `card.expireDate` | 两边都是 `YYYYMMDD` → `YYYY-MM-DD` 之后的形状 |
 * | `CardInfo.cardType` | `card.cardName` | ⚠️ **不是 `cardType`**：上游 `cardType:"800"` 是编号，屏上要显示的是 `cardName:"正式卡"`（:app 也是取 `cardname`） |
 * | `CardInfo.department` | —— | campus-api 不投影它，**留空**（不编一个值出来） |
 * | `Transaction.time` | `time` | ← 上游 `jndatetimeStr` |
 * | `Transaction.merchant` | `merchant` | ← 上游 `toMerchant`；为空时按 :app 的老规矩从 `channel` 里取一个能看的名字（见下） |
 * | `Transaction.amount` | **`signed`** | ⚠️ **不是 `amount`**：`amount` 恒为正数（上游 `tranamt` 是无符号绝对值），方向在 `signed` 里；用错会让所有支出显示成正数 |
 * | `Transaction.balance` | `balance` | ← 上游 `cardBalance` |
 * | `Transaction.type` | `type` | ← 上游 `turnoverType` |
 * | `Transaction.description` | **`channel`** | ⚠️ 上游这个字段就是 :app 解析的 `resume`（形如「东区浴室-电子账户消费」）；:app 的 `merchantFromResume(description)` 正是解析它 |
 *
 * 两处**如实标注的口径差异**（不是 bug，是两端的上游投影本来就不同）：
 *  1. `signed` 只在 `typeFrom` 缺失时才和 :app 不同：campus-api 那时原样给正数，而 :app 的
 *     `signedAmountCents` 还会用关键词 / `toAccount` 再推一次方向。实测那批缺失极少，且 campus-api
 *     没有投影 `typeFrom`/`toAccount`/`icon`，这里推不出来 —— 不猜。
 *  2. `merchant` 为空时这里补 `merchantFromResume(channel)`，与 :app 解析时那一步（`toMerchant`
 *     空串兜底）逐字一致；不补的话扫码点餐/充值那批会在流水里显示成没有名字的行。
 *
 * ## Web 端的落盘：只到「本次会话」为止
 *
 * 浏览器里没有校园卡那份落盘缓存（`localStorage` 不放这种大块数据，也不该把个人流水写进页面存储），
 * 所以 [persist] 只把这一份**留在内存里**（刷新页面即失效），[snapshot] 只回这一份，
 * [persistCard] 是空实现（Web 没有首页卡片与桌面小组件）。这是**如实降级**：刷新页面后没有首屏秒开，
 * 而不是造一个"缓存"。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusCardNetApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : CampusCardSource {

    override suspend fun card(): CardInfo {
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
        // 与 :app 的 `CampusCardCache.load` + 屏自己裁剪同一件事：只给这一段（区间元信息与落盘时刻
        // 仍取那一份的，屏靠 rangeStart 判断"缓存覆盖没覆盖住这次要的范围"）
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

    /** 本次会话里最后落盘的那一份（[persist] 写、[snapshot] 读）。刷新页面即失效，见类 KDoc。 */
    private var session: CampusCardSnapshot? = null

    /** 拆 `{code,data}` 信封；`code!=0` 按体测/校历那套报法显式失败。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 校园卡返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // need-login（campus-api 自己的 ncard 令牌失效）也走这一支：Web 端没有 CAS 会话可重登，
            // 照实报错，不抛 SessionExpiredFailure（那会让屏幕白白退页，与体测/通知同口径）。
            error("campus-api 校园卡失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 校园卡返回缺少 data：${text.take(120)}")
    }
}
