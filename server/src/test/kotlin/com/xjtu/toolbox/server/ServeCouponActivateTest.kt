package com.xjtu.toolbox.server

import com.xjtu.toolbox.coupon.CouponFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `POST /api/coupon/activate` 的契约测试（`docs/api-contract.md` §5.5）。 */
class ServeCouponActivateTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `领券：未登录 401 · 参数形状 400（不是 JSON 缺格 空白）· 一枪都不打上游`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            couponRoutes(session)
        }
        started = server

        // 令牌对、但没登录 ⇒ 401 + 指路；这一枪不该到取数层
        val anonymous = http.postJson(ACTIVATE_PATH, validBody())
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(0, fake.coupon.activateBodies.size, "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val badCases = listOf(
            "not json at all" to "请求体不是 JSON",
            """{"cardId":"CARD-假-0002"}""" to "少了 showCardId（这一格的名字只认契约那一套）",
            """{"showCardId":"   "}""" to "showCardId 空白",
            """{}""" to "整个 body 是空对象",
        )
        for ((body, why) in badCases) {
            val bad = http.postJson(ACTIVATE_PATH, body)
            assertEquals(400, bad.statusCode(), why)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad), why)
            assertTrue(!envelopeMessage(bad).isNullOrBlank(), "$why ⇒ message 要给中文短句")
        }
        // 缺那一格的那一枪要说清是哪一格
        assertTrue(
            envelopeMessage(http.postJson(ACTIVATE_PATH, """{}""")).orEmpty().contains("showCardId"),
            "400 要说出缺什么",
        )
        assertEquals(0, fake.coupon.activateBodies.size, "参数不对的那几枪不该到上游")
    }

    @Test
    fun `领券：成功一枪 —— 上游收到 cardId，data 只有 ok 与 message`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            couponRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val ok = http.postJson(
            ACTIVATE_PATH,
            """{"showCardId":"${CouponFakeUpstream.AVAILABLE_CARD_ID}"}""",
        )
        assertEquals(200, ok.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(ok))
        assertNoIdentity(ok.body(), "POST $ACTIVATE_PATH", IDENTITY)

        val data = envelopeData(ok)
        assertEquals(setOf("ok", "message"), data.keys, "写端点的形状与 §5.3.1 / §5.3.2 那两个一致")
        assertEquals(true, data.optBoolean("ok"))
        assertTrue(!envelopeMessage(ok).isNullOrBlank(), "message 是给 curl / 排障看的中文短句")

        // 请求形状：`:data` 的 `CouponApi.activateCoupon` 把 showCardId 转成上游那一格 `cardId`
        assertEquals(1, fake.coupon.activateBodies.size, "成功那一枪真打了上游的领取接口")
        val body = fake.coupon.activateBodies.single()
        assertTrue(body.contains(""""cardId":"${CouponFakeUpstream.AVAILABLE_CARD_ID}""""), "上游那一格叫 cardId：$body")
        assertTrue(body.contains(""""json":true"""), "与两端同一条链（:data 那一份拼的）：$body")
    }

    @Test
    fun `领券：上游业务拒收与会话失效都是 502 —— 本层不编一个判据把两者分开`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            couponRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ① 到了上游、上游说不给领（券领完了那一档）：`:data` 只有「抛一句上游原文」这一种失败
        fake.coupon.activateRejected = true
        val rejected = http.postJson(ACTIVATE_PATH, validBody())
        assertEquals(502, rejected.statusCode())
        assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(rejected))
        assertTrue(
            envelopeMessage(rejected).orEmpty().contains(CouponFakeUpstream.ACTIVATE_REJECTED_MSG),
            "上游那句中文要交回用户：${envelopeMessage(rejected)}",
        )
        // ⚠️ 只断言「打了至少一枪」：`:data` 的 `executeWithReAuth` 自己会重试（重认证那一套），
        // 究竟几发是它那一层的策略 —— 这里要钉的是「到了上游」，不是那个数
        assertTrue(fake.coupon.activateBodies.size >= 1, "这一枪确实打到了上游")
        fake.coupon.activateRejected = false
        fake.coupon.activateExpired = true
        val expired = http.postJson(ACTIVATE_PATH, validBody())
        assertEquals(502, expired.statusCode())
        assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(expired))
        assertTrue(!envelopeMessage(expired).isNullOrBlank())
        assertTrue(fake.coupon.activateBodies.size >= 2, "第二枪也到了上游")

        // ③ 夹具恢复 ⇒ 立刻能领
        fake.coupon.activateExpired = false
        assertEquals(200, http.postJson(ACTIVATE_PATH, validBody()).statusCode())
        assertTrue(fake.coupon.activateBodies.size >= 3, "第三枪也到了上游，而且这次上游是好的")
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private fun validBody(): String = """{"showCardId":"${CouponFakeUpstream.AVAILABLE_CARD_ID}"}"""

    private companion object {
        const val ACTIVATE_PATH = "/api/coupon/activate"

        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** §5.2 的红线钉子组（全是夹具里的编造值，不是真人）。 */
        val IDENTITY = listOf(
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
        )
    }
}
