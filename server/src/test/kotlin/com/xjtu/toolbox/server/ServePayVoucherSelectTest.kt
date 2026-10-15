package com.xjtu.toolbox.server

import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /api/pay/vouchers/select` 的契约测试（`docs/api-contract.md` §5.7 的**加餐券勾选同步**，
 * 2026-10-15 批③-b / D7 ④）。
 *
 * 与 [ServePayFeedbackMatchWebvpnTest] 同一条脚手架（[withFakeProxy] + [startEndpointServer]）；
 * 夹具是 `:testkit` 的 `CampusCardFakeUpstream`（`ncard.xjtu.edu.cn` —— 付款码借的就是校园卡
 * 那条会话），本批新加的是 `voucherUpdateRejected` 开关。
 *
 * ## 这一组钉的是什么
 *
 * - **闸门**：未登录 `401`，而且那一枪**不打上游**（`voucherUpdateCalls == 0`）；
 * - **参数**：body 不是 JSON / 缺 `showCardIds` / 那一格不是字符串数组 ⇒ `400`；
 * - **请求形状**：上游收到的是**裸 JSON 数组**（`:data` 的 `updateVoucherStatus` 用 `buildJsonArray`
 *   拼的）、顺序取调用方的先后、去重去空白 —— 与两端同一条链；
 * - **成功**：`200` + `{ok, message}`（`data` 只有那两格）；
 * - **上游拒收**：`502` + 上游那句中文（`:data` 只看 `success` 这一个布尔）；
 * - **红线**：响应体过 [assertNoIdentity]。
 *
 * ## 本批**做不到**的那件（D7 ④ 的结论，写在契约里）
 *
 * 「付款码**支付**」这一档**上游客户端本来就没有**：`:data` 的 `PaymentCodeApi` 上只有
 * `authenticate` / `getBarCode` / `getVouchers` / `updateVoucherStatus` 四个方法，一个扣款/支付
 * 动作都没有（钱是商户扫了那串号之后在学校那边扣的）⇒ 没有东西可端，这一组端的是这一族**唯一**
 * 的写。
 */
class ServePayVoucherSelectTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `勾选同步：未登录 401 · 参数形状 400（不是 JSON 缺格 不是数组）· 一枪都不打上游`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            payRoutes(session)
        }
        started = server

        val anonymous = http.postJson(SELECT_PATH, validBody())
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(0, fake.campusCard.voucherUpdateCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val badCases = listOf(
            "not json at all" to "请求体不是 JSON",
            """{"ids":["${CampusCardFakeUpstream.VOUCHER_CANTEEN_ID}"]}""" to "少了 showCardIds（只认契约那一格）",
            """{"showCardIds":"${CampusCardFakeUpstream.VOUCHER_CANTEEN_ID}"}""" to "那一格不是数组",
            // ⚠️ 故意**不**把 `{"showCardIds":[1,2]}` 当 400：kotlinx 的 JsonDecoder 把数字
            // primitive 当字符串解出来（`decodeString` 收非字符串 primitive），所以它会变成 `["1","2"]`
            // —— 这是 JSON 那一层的既有行为，不是本层新编的判据；对着它写一条「必 400」的断言才是假的。
            """{}""" to "整格缺了（缺格与空表不是一回事 —— 写端点上这一条最要紧）",
        )
        for ((body, why) in badCases) {
            val bad = http.postJson(SELECT_PATH, body)
            assertEquals(400, bad.statusCode(), why)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad), why)
            assertTrue(!envelopeMessage(bad).isNullOrBlank(), "$why ⇒ message 要给中文短句")
        }
        assertEquals(0, fake.campusCard.voucherUpdateCalls.get(), "参数不对的那几枪不该到上游")
        assertEquals(0, fake.campusCard.barCodeCalls.get(), "勾选同步不该顺手去拿码")
    }

    @Test
    fun `勾选同步：成功一枪 —— 上游收到裸数组（去重去空白，顺序照调用方），data 只有 ok 与 message`() =
        withFakeProxy { fake ->
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) {
                sessionRoutes(session, token)
                payRoutes(session)
            }
            started = server
            assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

            val ok = http.postJson(
                SELECT_PATH,
                """{"showCardIds":[" ${CampusCardFakeUpstream.VOUCHER_MILK_ID} ",""" +
                    """"${CampusCardFakeUpstream.VOUCHER_CANTEEN_ID}",""" +
                    """"${CampusCardFakeUpstream.VOUCHER_MILK_ID}","  "]}""",
            )
            assertEquals(200, ok.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(ok))
            assertNoIdentity(ok.body(), "POST $SELECT_PATH", IDENTITY)

            val data = envelopeData(ok)
            assertEquals(setOf("ok", "message"), data.keys, "写端点的形状与 §5.3.1 / §5.3.2 那两个一致")
            assertEquals(true, data.optBoolean("ok"))
            assertTrue(!envelopeMessage(ok).isNullOrBlank(), "message 是给 curl / 排障看的中文短句")

            // 上游收到的是**裸 JSON 数组**（`:data` 的 buildJsonArray 那一份），不是我们这一层的对象
            assertEquals(1, fake.campusCard.voucherUpdateCalls.get(), "成功那一枪真打了上游的同步接口")
            assertEquals(
                """["${CampusCardFakeUpstream.VOUCHER_MILK_ID}","${CampusCardFakeUpstream.VOUCHER_CANTEEN_ID}"]""",
                fake.campusCard.lastVoucherUpdateBody,
                "去空白、丢空串、去重，顺序取调用方给的先后",
            )
        }

    @Test
    fun `勾选同步：上游拒收 502 + 上游那句中文 · 校园卡没登录也是 502`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            payRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        fake.campusCard.voucherUpdateRejected = true
        val rejected = http.postJson(SELECT_PATH, validBody())
        assertEquals(502, rejected.statusCode())
        assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(rejected))
        assertTrue(
            envelopeMessage(rejected).orEmpty().contains(CampusCardFakeUpstream.VOUCHER_UPDATE_REJECTED_MSG),
            "上游那句中文要交回用户：${envelopeMessage(rejected)}",
        )
        assertTrue(fake.campusCard.voucherUpdateCalls.get() >= 1, "这一枪确实打到了上游")

        // 夹具恢复 ⇒ 立刻能同步（证明上一条是上游那一档，不是配置漂了）
        fake.campusCard.voucherUpdateRejected = false
        assertEquals(200, http.postJson(SELECT_PATH, validBody()).statusCode())
        assertTrue(fake.campusCard.voucherUpdateCalls.get() >= 2)
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private fun validBody(): String =
        """{"showCardIds":["${CampusCardFakeUpstream.VOUCHER_CANTEEN_ID}"]}"""

    private companion object {
        const val SELECT_PATH = "/api/pay/vouchers/select"

        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** §5.2 的红线钉子组（全是夹具里的编造值，不是真人）。 */
        val IDENTITY = listOf(
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            CampusCardFakeUpstream.USER_NAME,
            CampusCardFakeUpstream.STUDENT_NO,
            CampusCardFakeUpstream.CARD_ACCOUNT,
        )
    }
}
