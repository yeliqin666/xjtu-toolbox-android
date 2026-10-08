package com.xjtu.toolbox.card

import com.xjtu.toolbox.core.net.CampusCardNetApi
import com.xjtu.toolbox.core.net.createToolboxClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/**
 * `CampusCardNetApi`（Web 端的校园卡取数）的**字段映射测试**。
 *
 * 为什么值得写：campus-api 是**白名单投影**，字段名跟 ncard 的原始键对不上（`amount` 是绝对值、
 * 方向在 `signed` 里；App 解析的 `resume` 在这里叫 `channel`；`cardType` 是编号 `800`，
 * 屏上要显示的是 `cardName`）。这些映射在浏览器里跑一次只能看出"有余额、有流水"，
 * 看不出"支出是不是负的、商户名对不对"。这里用 MockEngine 喂 campus-api 的**实测形状**
 *（2026-10-08 实测 `/api/card/balance` 与 `/api/card/transactions?page=1&size=3`），值自己编，键名照抄。
 */
class CampusCardMappingTest {

    /** `/api/card/balance` 的实测形状（身份字段一律是编的）。 */
    private val balancePayload = """
        {"code":0,"error":null,"data":{"source":"https://ncard.xjtu.edu.cn/berserker-app/ykt/tsm/queryCard?synAccessSource=h5",
          "card":{"account":"100000","name":"同学甲","studentNo":"9********1",
            "balanceCents":2254,"balance":22.54,"unsettledCents":500,"unsettled":5.0,"balanceCentsTotal":2754,
            "cardName":"正式卡","cardType":"800","expireDate":"2033-01-21",
            "lost":false,"frozen":true,"barred":false,"accStatus":"0",
            "autoTransfer":{"enabled":true,"amountCents":5000,"limitCents":2000},
            "dailyLimitCents":0,"singleLimitCents":0,"voucherStatus":"0"}}}
    """.trimIndent()

    /** `/api/card/transactions` 的实测形状：第一笔支出、第二笔充值（`merchant` 为 null，正是 :app 要兜底的那批）。 */
    private val transactionsPayload = """
        {"code":0,"error":null,"data":{"source":"https://ncard.xjtu.edu.cn/berserker-search/search/personal/turnover",
          "window":{"from":"2026-07-10","to":"2026-10-08","source":"default"},
          "page":1,"size":50,"total":96,"pages":2,"count":2,
          "rows":[
            {"time":"2026-10-07 23:51:00","type":"消费","consumeType":null,"merchant":"东区浴室",
             "channel":"东区浴室-电子账户消费","location":"4-068","payName":"电子账户消费",
             "amountCents":2,"signedCents":-2,"amount":0.02,"signed":-0.02,
             "direction":"out","directionSource":"typeFrom","balanceCents":2254,"balance":22.54,
             "refund":null,"remark":"posno:1169","orderId":"1791396399000271239100003768673"},
            {"time":"2026-10-06 18:30:22","type":"充值","consumeType":null,"merchant":null,
             "channel":"充值-支付宝转账","location":null,"payName":"支付宝转账",
             "amountCents":10000,"signedCents":10000,"amount":100.0,"signed":100.0,
             "direction":"in","directionSource":"typeFrom","balanceCents":12254,"balance":122.54,
             "refund":null,"remark":"","orderId":"1791282766000271239100135121628948"}
          ]}}
    """.trimIndent()

    private fun apiFor(
        balance: String = balancePayload,
        transactions: String = transactionsPayload,
        recorder: MutableList<String> = mutableListOf(),
    ): CampusCardNetApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            recorder += path
            val body = when {
                path.endsWith("/api/card/balance") -> balance
                path.endsWith("/api/card/transactions") -> transactions
                else -> error("不该请求 $path")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        return CampusCardNetApi(createToolboxClient(engine = engine))
    }

    @Test
    fun cardTypeComesFromCardNameNotTheNumber() = runTest {
        val card = apiFor().card()
        // ⚠️ 上游 cardType:"800" 是编号，屏上要显示的是 cardName:"正式卡"（:app 也是取 cardname）
        assertEquals("正式卡", card.cardType)
        assertEquals(22.54, card.balance, 0.0)
        // 待入账是 unsettled（不是 balanceCentsTotal —— 那是"余额 + 待入账"的合计）
        assertEquals(5.0, card.pendingAmount, 0.0)
        assertEquals(false, card.lostFlag)
        assertEquals(true, card.frozenFlag)
        assertEquals("2033-01-21", card.expireDate)
        assertEquals("100000", card.account)
        assertEquals("同学甲", card.name)
        // campus-api 的学生号是掩码的，如实照收；学院它不投影 ⇒ 留空，不编
        assertEquals("9********1", card.studentNo)
        assertEquals("", card.department)
    }

    @Test
    fun amountComesFromSignedNotAmount() = runTest {
        val (total, rows) = apiFor().transactions(LocalDate(2026, 7, 10), LocalDate(2026, 10, 8), 1, 50)
        assertEquals(96, total)
        assertEquals(2, rows.size)
        // ⚠️ **为什么不是 `amount`**：campus-api 的 `amount` 是上游 `tranamt` 的绝对值（恒为正数），
        // 方向在 `signed` 里（上游 `typeFrom` 推出来的）。取 `amount` 的话这一笔 ¥0.02 的浴室消费
        // 会显示成收入 +0.02，整个屏的"总支出 / 分类占比 / 今日三餐"全反。
        assertEquals(-0.02, rows[0].amount, 0.0)
        assertEquals(100.0, rows[1].amount, 0.0)
        // 展示与统计一律走 displayMerchant / amount，与 :app 同一个口径
        assertTrue(rows[0].amount < 0)
    }

    @Test
    fun descriptionComesFromChannelWhichIsTheAppResume() = runTest {
        val (_, rows) = apiFor().transactions(LocalDate(2026, 7, 10), LocalDate(2026, 10, 8), 1, 50)
        // ⚠️ campus-api 的 `channel` 就是 :app 解析的 `resume`（形如「商户-渠道」）——
        // :app 的 `merchantFromResume(description)` 解析的正是它，所以这一列必须落在 description 上
        assertEquals("东区浴室-电子账户消费", rows[0].description)
        assertEquals("东区浴室", rows[0].merchant)
        assertEquals("东区浴室", rows[0].displayMerchant)
        assertEquals("消费", rows[0].type)
        assertEquals(22.54, rows[0].balance, 0.0)
        assertEquals("2026-10-07 23:51:00", rows[0].time)
    }

    @Test
    fun blankMerchantFallsBackToTheChannelLikeTheApp() = runTest {
        val (_, rows) = apiFor().transactions(LocalDate(2026, 7, 10), LocalDate(2026, 10, 8), 1, 50)
        // 扫码点餐/充值那批的 toMerchant 是空串 ⇒ :app 在解析时就兜了一次（merchantFromResume）；
        // 这里照做，否则流水里会出现没有名字的行
        assertEquals("充值", rows[1].merchant)
        assertEquals("充值", rows[1].displayMerchant)
        assertEquals("充值-支付宝转账", rows[1].description)
    }

    @Test
    fun sessionSnapshotIsInMemoryOnlyAndClippedToTheRange() = runTest {
        val api = apiFor()
        val card = api.card()
        val (_, rows) = api.transactions(LocalDate(2026, 7, 10), LocalDate(2026, 10, 8), 1, 50)

        // 还没落过盘 ⇒ 没有"上次那份"（Web 没有磁盘缓存，如实返回 null 让屏走一次完整加载）
        assertNull(api.snapshot(LocalDate(2026, 7, 10), LocalDate(2026, 10, 8)))

        api.persist(card, rows, LocalDate(2026, 7, 10), LocalDate(2026, 10, 8))
        val held = api.snapshot(LocalDate(2026, 7, 10), LocalDate(2026, 10, 8))
        assertEquals(22.54, held?.cardInfo?.balance ?: 0.0, 0.0)
        assertEquals(2, held?.transactions?.size)
        // 区间元信息是"这份快照覆盖到哪儿"，屏靠它判断缓存覆盖没覆盖住这次要的范围
        assertEquals("2026-07-10", held?.rangeStart)
        assertEquals("2026-10-08", held?.rangeEnd)
        assertTrue((held?.savedAt ?: 0L) > 0L)

        // 要的区间比快照窄 ⇒ 裁到这一段（只有 10-07 那一笔）
        val narrow = api.snapshot(LocalDate(2026, 10, 7), LocalDate(2026, 10, 7))
        assertEquals(1, narrow?.transactions?.size)
        assertEquals("2026-10-07 23:51:00", narrow?.transactions?.single()?.time)
    }

    @Test
    fun persistCardIsANoOpBecauseWebHasNoHomeCache() = runTest {
        val recorder = mutableListOf<String>()
        val watched = apiFor(recorder = recorder)
        // Web 没有首页卡片与桌面小组件读的那组 key ⇒ 空实现（也不该因此多打一次请求）
        watched.persistCard(CardInfo(balance = 1.0), accountId = null)
        assertTrue(recorder.isEmpty())
    }
}
