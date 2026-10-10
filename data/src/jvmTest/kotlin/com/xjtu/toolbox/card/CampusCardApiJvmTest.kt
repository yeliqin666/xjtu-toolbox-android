package com.xjtu.toolbox.card

import com.xjtu.toolbox.auth.CampusCardLogin
import com.xjtu.toolbox.auth.withCampusCardLogin
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * 校园卡取数的**字段级口径**：`CampusCardApi` 对着 `:testkit` 的 `CampusCardFakeUpstream` 逐字段读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把校园卡的取数与会话从 `:app` 搬进了 `:data`（桌面端第 9 条真数据路由）。搬之前先用夹具把
 * **搬之前**的口径钉住：`:app` 那边一行逻辑没改，只是文件换了地方 —— 但「没改」这句话得有证据，
 * 这些断言就是那个证据。手法与教务那两条（`SchoolCourseApiJvmTest` / `JudgeApiJvmTest`）逐条对齐：
 * 走**真登录链**（[withCampusCardLogin]：CAS 表单 POST → TGC → 签 ticket → 回跳 ncard →
 * ticket 换 JWT），URL 一个字符都不改。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（`CampusCardFakeUpstream` 里那些 JSON 常量）与 `:core` 已钉过的口径
 * （`CampusCardContract.signedAmountCents` 的收支方向、`merchantFromResume` 的商户兜底、
 * `requireLong` 的字符串分支、`CampusCardApi.formatExpDate` 的日期写法），**不是从跑通的实现里
 * 抄回来的** —— 夹具刻意摆了七条各不相同的流水（`typeFrom` 权威判据 / 关键词兜底 / `toAccount`
 * 兜底 / 服务端已给负数 / 空 `toMerchant`），「方向与商户名给什么」因此写在明面上。
 */
class CampusCardApiJvmTest {

    /** 屏上的默认区间就是「近一个月」，这里取一段把它盖住。 */
    private val from = LocalDate(2026, 10, 1)
    private val to = LocalDate(2026, 10, 10)

    /** 夹具与生产必须指同一个站点：URL 漂了的话，下面这些断言测的就不是真协议。 */
    @Test
    fun `夹具与生产都指向同一个校园卡入口与基址`() {
        assertEquals(CampusCardLogin.LOGIN_URL, CampusCardFakeUpstream.HOME_URL)
        assertEquals(CampusCardLogin.BASE_URL, CampusCardFakeUpstream.ORIGIN)
    }

    /**
     * 登录链本身：`CampusCardLogin` 的流程与别站不同 —— CAS 回跳的落地 URL 上带 ticket，
     * `postLogin` 拿它去换 JWT，再拉一次用户资料。三样都落在同一份站点快照里。
     */
    @Test
    fun `真登录：CAS 回跳那一跳上换出 JWT，四个 token 都落进站点快照`() {
        withCampusCardLogin { site, fake ->
            assertTrue(site.hasLogin, "假上游上的真登录应当成功")
            assertEquals(1, fake.campusCard.ticketLandings.get(), "CAS 回跳只该落在 ncard 入口一次")
            assertTrue(fake.campusCard.casRedirects.get() >= 1, "没带 ticket 时应当被交给统一认证")
            assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")
            assertEquals(1, fake.campusCard.tokenCalls.get(), "ticket 只该换一次 JWT")
            assertTrue(fake.campusCard.userInfoCalls.get() >= 1, "postLogin 里那一枪用户资料应当打到夹具")
            assertTrue(
                fake.campusCard.lastTokenForm?.contains("username=ST-") == true,
                "换 JWT 时该把 CAS 签的 ticket 当凭据发出去：${fake.campusCard.lastTokenForm}",
            )

            // 快照里的四项：业务取数（Synjones-Auth 头、CardInfo 的三个身份字段）全靠它们
            assertEquals(CampusCardFakeUpstream.ACCESS_TOKEN, site.localToken["access_token"])
            assertEquals(CampusCardFakeUpstream.CARD_ACCOUNT, site.localToken["card_account"])
            assertEquals(CampusCardFakeUpstream.USER_NAME, site.localToken["user_name"])
            assertEquals(CampusCardFakeUpstream.STUDENT_NO, site.localToken["student_no"])
        }
    }

    /**
     * 卡面：分 → 元、待入账、空格 trim、8 位日期格式化、以及**做不到**的那一格（学院留空 ——
     * `department` 是 `:app` 从 HTML 里抠的，ncard 的 JSON 里没有，不该编一个）。
     */
    @Test
    fun `卡面：分转元、待入账、空格 trim、8 位日期格式化`() {
        withCampusCardLogin { site, fake ->
            val info = runBlocking { CampusCardApi(site).getCardInfo() }

            assertEquals(CampusCardFakeUpstream.CARD_ACCOUNT, info.account)
            assertEquals(CampusCardFakeUpstream.USER_NAME, info.name)
            assertEquals(CampusCardFakeUpstream.STUDENT_NO, info.studentNo)
            // 夹具给的是分（余额是数字、待入账刻意是字符串 "5000"，钉 requireLong 的两条分支）
            assertEquals(CampusCardFakeUpstream.BALANCE_CENTS / 100.0, info.balance)
            assertEquals(CampusCardFakeUpstream.PENDING_CENTS / 100.0, info.pendingAmount)
            assertEquals(false, info.lostFlag, "barflag=0 ⇒ 没挂失")
            assertEquals(false, info.frozenFlag, "freezeflag=0 ⇒ 没冻结")
            assertEquals(CampusCardFakeUpstream.EXPIRE_DATE, info.expireDate, "8 位 expdate 该换成 yyyy-MM-dd")
            assertEquals(CampusCardFakeUpstream.CARD_TYPE, info.cardType, "cardname 两侧的空格该被 trim 掉")
            assertEquals("", info.department, "ncard 的 JSON 里没有学院，不该编一个")
            assertTrue(fake.campusCard.cardCalls.get() >= 1, "卡面那一枪应当打到夹具")
        }
    }

    /**
     * 流水：七条的金额方向、商户名、余额、类型与描述逐字段对；请求那一侧的分页与日期区间也一并对。
     *
     * 七条各自钉住一个分支（表见夹具的常量区）：`typeFrom` 权威判据（收/支各一条）、关键词兜底
     * （二维码支付）、`toAccount` 兜底（钱转出去/留在自己账上各一条）、服务端已经给了负数（不再翻正）、
     * 以及 `toMerchant` 是空串/缺字段时退回 `resume`（商户名兜底）。
     */
    @Test
    fun `流水：七条把收支方向与商户兜底的每条分支各钉一条`() {
        withCampusCardLogin { site, fake ->
            val (total, txs) = runBlocking {
                CampusCardApi(site).getTransactions(
                    startDate = from,
                    endDate = to,
                    page = 1,
                    pageSize = 50,
                )
            }

            // 服务端总数与行序：行序就是夹具给的序（上游按入账时间倒序）
            assertEquals(CampusCardFakeUpstream.TOTAL, total)
            assertEquals(CampusCardFakeUpstream.TOTAL, txs.size, "夹具只有一页，总数到齐")

            assertEquals(
                listOf(
                    CampusCardFakeUpstream.TX_LUNCH,
                    CampusCardFakeUpstream.TX_BREAKFAST,
                    CampusCardFakeUpstream.TX_RECHARGE,
                    CampusCardFakeUpstream.TX_QRCODE,
                    CampusCardFakeUpstream.TX_UNKNOWN_OUT,
                    CampusCardFakeUpstream.TX_UNKNOWN_IN,
                    CampusCardFakeUpstream.TX_REFUND,
                ),
                txs.map { it.time },
            )
            assertEquals(
                listOf(
                    -CampusCardFakeUpstream.TX_LUNCH_AMOUNT_CENTS / 100.0,
                    -CampusCardFakeUpstream.TX_BREAKFAST_AMOUNT_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_RECHARGE_AMOUNT_CENTS / 100.0,
                    -CampusCardFakeUpstream.TX_QRCODE_AMOUNT_CENTS / 100.0,
                    -CampusCardFakeUpstream.TX_UNKNOWN_OUT_AMOUNT_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_UNKNOWN_IN_AMOUNT_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_REFUND_AMOUNT_CENTS / 100.0,
                ),
                txs.map { it.amount },
            )
            assertEquals(
                listOf(
                    CampusCardFakeUpstream.MERCHANT_CANTEEN,
                    CampusCardFakeUpstream.MERCHANT_CANTEEN,
                    // `toMerchant` 是空串 ⇒ 退回 resume 的第一段（「充值-支付宝转账」→「充值」）
                    "充值",
                    // 同上：「珍念水饺-电子账户消费」→「珍念水饺」
                    CampusCardFakeUpstream.MERCHANT_DUMPLING,
                    // `toMerchant` 缺字段、`resume` 也是空串 ⇒ 「未知商户」
                    CampusCardFakeUpstream.MERCHANT_UNKNOWN,
                    CampusCardFakeUpstream.MERCHANT_CARD_CENTER,
                    CampusCardFakeUpstream.MERCHANT_REFUND,
                ),
                txs.map { it.displayMerchant },
            )
            assertEquals(
                listOf(
                    CampusCardFakeUpstream.TX_LUNCH_BALANCE_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_BREAKFAST_BALANCE_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_RECHARGE_BALANCE_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_QRCODE_BALANCE_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_UNKNOWN_OUT_BALANCE_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_UNKNOWN_IN_BALANCE_CENTS / 100.0,
                    CampusCardFakeUpstream.TX_REFUND_BALANCE_CENTS / 100.0,
                ),
                txs.map { it.balance },
            )
            assertEquals(
                listOf("消费", "消费", "充值", "二维码支付", "校内转账", "校内转账", "退款"),
                txs.map { it.type },
            )
            assertEquals(
                listOf(
                    "${CampusCardFakeUpstream.MERCHANT_CANTEEN}-电子账户消费",
                    "${CampusCardFakeUpstream.MERCHANT_CANTEEN}-电子账户消费",
                    "充值-支付宝转账",
                    "${CampusCardFakeUpstream.MERCHANT_DUMPLING}-电子账户消费",
                    "",
                    "",
                    "${CampusCardFakeUpstream.MERCHANT_REFUND}-退款",
                ),
                txs.map { it.description },
            )
            // 去重键（缓存合并、翻页去重全靠它）：七条互不相同
            assertEquals(CampusCardFakeUpstream.TOTAL, txs.map { it.uniqueKey() }.toSet().size)

            // ── 请求那一侧：分页与日期区间真发出去了 ──
            val query = queryFields(fake.campusCard.lastTurnoverQuery.orEmpty())
            assertEquals("1", query["current"])
            assertEquals("50", query["size"])
            assertEquals(from.toString(), query["timeFrom"])
            assertEquals(to.toString(), query["timeTo"])
            assertEquals("h5", query["synAccessSource"])
        }
    }

    /**
     * 分页编排与**空缓存**语义：桌面端给的就是 `store = null`（没有宿主存储），
     * 所以「读回 null、写入是空实现」在这里钉住 —— 桌面那条链靠的就是它。
     */
    @Test
    fun `分页编排：总数到齐就不再多打一页；桌面端的 store = null 是空缓存`() {
        withCampusCardLogin { site, fake ->
            val source = AppCampusCardSource(site)

            val all = runBlocking { source.allTransactions(from, to) }
            assertEquals(CampusCardFakeUpstream.TOTAL, all.size)
            assertEquals(1, fake.campusCard.turnoverCalls.get(), "总数到齐 ⇒ 不该再翻第二页")

            assertNull(runBlocking { source.snapshot(from, to) }, "没有宿主存储 ⇒ 读回 null（不是空快照）")

            val card = runBlocking { source.card() }
            assertEquals(CampusCardFakeUpstream.BALANCE_CENTS / 100.0, card.balance)
            val before = fake.campusCard.cardCalls.get()
            runBlocking {
                source.persistCard(card)
                source.persist(card, all, from, to)
            }
            assertEquals(before, fake.campusCard.cardCalls.get(), "落盘不该顺带再打一枪网络")
        }
    }

    /** `k=v&k=v` 的查询串 → 字段表（与夹具里那份解表单的写法同一条口径）。 */
    private fun queryFields(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate { part ->
            val value = part.substringAfter('=', "")
            part.substringBefore('=') to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
}
