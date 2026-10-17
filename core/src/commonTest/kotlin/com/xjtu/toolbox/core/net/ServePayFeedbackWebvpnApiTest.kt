package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.feedback.FeedbackReply
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** serve 契约 §5.7（付款码 / 反馈 / WebVPN）的客户端那一侧。 */

/** 一发请求的快照：`路径 to 参数表`（与 `ServeReadonlyFourDomainsApiTest` 同一套读法）。 */
private typealias ServeCall = Pair<String, Map<String, String>>

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType to listOf("application/json"))

class ServePayFeedbackWebvpnApiTest {

    /** 一个只按路径答夹具的 MockEngine。 */
    private fun engineFor(vararg bodies: Pair<String, String>): MockEngine {
        val byPath = bodies.toMap()
        return MockEngine { request ->
            val body = byPath.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
            respond(content = body, status = HttpStatusCode.OK, headers = JSON_HEADERS)
        }
    }

    /** 记一发请求（路径 + 全部查询参数）。 */
    private fun record(calls: MutableList<ServeCall>, request: io.ktor.client.request.HttpRequestData) {
        calls += request.url.encodedPath to
            request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
    }

    /** 付款码：`authenticate()` 在 serve 这一端是本地什么都不做（会话在服务端进程里，页面上没有可看的东西）⇒ 它一发请求都不打。 */
    @Test
    fun servePayCodeAndVouchersFollowContract() = runTest {
        val codeBody = """{"code":0,"data":{"barCode":"62123456789012345678"}}"""
        val vouchersBody = """
            {"code":0,"data":{"count":2,"vouchers":[
              {"showCardId":"SC-1","voucherName":"早餐券","typeName":"加餐券","amountFen":500,
               "endDate":"2026-12-31"},
              {"showCardId":"","voucherName":"没有句柄的那张","typeName":"加餐券","amountFen":100,
               "endDate":"2026-12-31"}]}}
        """.trimIndent()
        val calls = mutableListOf<ServeCall>()
        val selectBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            record(calls, request)
            val body = when {
                request.url.encodedPath.endsWith("/api/pay/code") -> codeBody
                request.url.encodedPath.endsWith("/api/pay/vouchers") -> vouchersBody
                request.url.encodedPath.endsWith("/api/pay/vouchers/select") -> {
                    selectBodies += (request.body as io.ktor.content.TextContent).text
                    """{"code":0,"data":{"ok":true,"message":"✓ 已同步加餐券勾选"}}"""
                }
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, JSON_HEADERS)
        }
        val api = CampusPayApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        api.authenticate()
        assertTrue(calls.isEmpty(), "authenticate() 是本地那一枪：serve 这一端不发请求也不改状态")

        assertEquals("62123456789012345678", api.getBarCode(), "码就是服务端端出来的那一串（字符串）")
        assertEquals("/api/pay/code", calls[0].first)
        assertTrue(calls[0].second.isEmpty(), "付款码两个端点都不收参数")

        val vouchers = api.getVouchers()
        assertEquals("/api/pay/vouchers", calls[1].first)
        assertEquals(1, vouchers.size, "showCardId 为空的那一行不列（它是勾选与后续动作的句柄）")
        assertEquals("SC-1", vouchers[0].showCardId)
        assertEquals("早餐券", vouchers[0].voucherName)
        assertEquals("加餐券", vouchers[0].typeName)
        assertEquals(500L, vouchers[0].amountFen, "amountFen 是**分**（除 100 是屏的事）")
        assertEquals("2026-12-31", vouchers[0].endDate, "上游原文的「到哪天为止」，不是 ISO-8601 的时刻")
        assertEquals("", vouchers[0].serverFlag, "serverFlag 刻意不投影 ⇒ 空串，不是编一个默认值")

        // 勾选同步：端子在 ⇒ 屏上那几行券照旧可勾；这一发真打出去（`{showCardIds}`）
        assertTrue(api.canSelectVouchers, "勾选同步端了（POST /api/pay/vouchers/select）⇒ 屏上照旧可勾")
        api.updateVoucherStatus(listOf(" SC-1 ", "SC-2", "SC-1", "  "))
        assertEquals("/api/pay/vouchers/select", calls.last().first)
        assertEquals(1, selectBodies.size, "勾选同步真发了一枪")
        assertEquals(
            """{"showCardIds":["SC-1","SC-2"]}""",
            selectBodies.single(),
            "body 只有契约那一格；去空白、丢空串、去重，顺序取调用方给的先后",
        )
    }

    /** 付款码空了就报错。 */
    @Test
    fun servePayCodeRefusesToDrawAnEmptyCode() = runTest {
        val api = CampusPayApi(
            createToolboxClient(engine = engineFor("/api/pay/code" to """{"code":0,"data":{"barCode":"  "}}""")),
            "",
            ApiMode.SERVE,
        )
        val failure = assertFailsWith<IllegalStateException> { api.getBarCode() }
        assertTrue(
            failure.message!!.contains("barCode"),
            "错误里要指名道姓说是哪一格（便于排障）：${failure.message}",
        )
    }

    /** 失败时只读 `message`：`code != 0` 就是失败，`message` 是能直接给用户看的中文短句。 */
    @Test
    fun serveErrorsReadMessageOnly() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":401,"message":"请先登录：POST /api/session/login","msg":"旧路的字段，别读它"}""",
                HttpStatusCode.Unauthorized,
                JSON_HEADERS,
            )
        }
        val pay = CampusPayApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)
        val failure = assertFailsWith<IllegalStateException> { pay.getBarCode() }
        assertTrue(failure.message!!.contains("请先登录"), "message 要原样读出来：${failure.message}")
        assertFalse(failure.message!!.contains("旧路"), "不许读旧路的 msg 字段：${failure.message}")
    }

    /** 反馈回复：`?tickets=` 去空白、去重、逗号分隔（顺序 = 请求给的先后） */
    @Test
    fun serveFeedbackRepliesFollowContract() = runTest {
        val repliesBody = """
            {"code":0,"data":{"tickets":["T1A2B3C4D5","T2E3F4A5B6"],"count":1,"replies":[
              {"ticket":"T1A2B3C4D5","text":"已在 1.2.3 修好","time":"2026-10-12 09:30"},
              {"ticket":"","text":"归不到任何工单上的那条","time":"2026-10-12 09:31"}]}}
        """.trimIndent()
        val publicBody = """
            {"code":0,"data":{"count":3,"items":[
              {"question":"为什么课表少了两门？","answer":"调停补课不在排课里。","category":"使用"},
              {"question":"","answer":"没有问题的行不显示","category":"使用"},
              {"question":"有问没答","answer":"   ","category":"使用"}]}}
        """.trimIndent()
        val calls = mutableListOf<ServeCall>()
        val engine = MockEngine { request ->
            record(calls, request)
            val body = when {
                request.url.encodedPath.endsWith("/api/feedback/replies") -> repliesBody
                request.url.encodedPath.endsWith("/api/feedback/public-qa") -> publicBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, JSON_HEADERS)
        }
        val api = CampusFeedbackApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val replies = api.replies(listOf(" T1A2B3C4D5 ", "T2E3F4A5B6", "", "T1A2B3C4D5"))
        assertEquals("/api/feedback/replies", calls[0].first)
        assertEquals(
            mapOf("tickets" to "T1A2B3C4D5,T2E3F4A5B6"),
            calls[0].second,
            "去空白、去重、逗号分隔；顺序 = 请求给的先后",
        )
        assertEquals(
            mapOf("T1A2B3C4D5" to FeedbackReply("已在 1.2.3 修好", "2026-10-12 09:30")),
            replies,
            "ticket 空白的那些不列；没人回过的工单不在响应里 ⇒ 也不在本类回的这张表里",
        )
        assertNull(replies["T2E3F4A5B6"], "没人回过 ⇒ 屏上显示「处理中」，不是拿空回复顶上")

        val qa = api.publicQa()
        assertEquals("/api/feedback/public-qa", calls[1].first)
        assertTrue(calls[1].second.isEmpty(), "公开问答不收参数")
        assertEquals(1, qa.size, "少了问题或答案的那两条不显示（与 FeedbackStore.cachedPublicQa 同一判据）")
        assertEquals("为什么课表少了两门？", qa[0].question)
        assertEquals("调停补课不在排课里。", qa[0].answer)
        assertEquals("使用", qa[0].category)

        assertTrue(api.isConfigured, "端点在这份契约里是有的（凭据配没配是服务端运行期的事）")
        assertTrue(api.canSubmit, "提交端了（POST /api/feedback/submit）⇒ 屏上那块表单照旧画")
    }

    /** WebVPN 正向：请求体就是契约那两个字段（`direction=to_vpn`），响应里的地址原样交回。 */
    @Test
    fun serveWebVpnConvertsToVpnOverHttp() = runTest {
        val vpnUrl = "https://webvpn.xjtu.edu.cn/https/77726476706e69737468656265737421" +
            "fae05988692862446b468ca88d1b203b/"
        val posted = mutableListOf<String>()
        val engine = MockEngine { request ->
            posted += (request.body as io.ktor.content.TextContent).text
            respond(
                """{"code":0,"data":{"direction":"to_vpn","url":"$vpnUrl"}}""",
                HttpStatusCode.OK,
                JSON_HEADERS,
            )
        }
        val api = CampusWebVpnApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        assertEquals(vpnUrl, api.toVpnUrl("https://jwxt.xjtu.edu.cn/"))
        assertEquals(
            """{"url":"https://jwxt.xjtu.edu.cn/","direction":"to_vpn"}""",
            posted.single(),
            "请求体只有契约那两个字段（端点是同一发，靠 direction 分向）",
        )
    }

    /** WebVPN 反向，两种失败刻意分开。 */
    @Test
    fun serveWebVpnFromVpnKeepsFourHundredAndUnauthorizedApart() = runTest {
        val notGateway = """{"code":400,"message":"url 不是 WebVPN 网关地址：形如 https://webvpn.xjtu.edu.cn/..."}"""
        val unauthenticated = """{"code":401,"message":"请先登录：POST /api/session/login"}"""
        val emptyUrl = """{"code":0,"data":{"direction":"from_vpn","url":""}}"""
        val engine = MockEngine { request ->
            val url = (request.body as io.ktor.content.TextContent).text
            val body = when {
                url.contains("not-a-gateway") -> notGateway
                url.contains("expired") -> unauthenticated
                url.contains("empty") -> emptyUrl
                else -> """{"code":0,"data":{"direction":"from_vpn","url":"https://rg.lib.xjtu.edu.cn:8086/seat/?a=1#top"}}"""
            }
            respond(body, HttpStatusCode.OK, JSON_HEADERS)
        }
        val api = CampusWebVpnApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        assertEquals(
            "https://rg.lib.xjtu.edu.cn:8086/seat/?a=1#top",
            api.fromVpnUrl("https://webvpn.xjtu.edu.cn/https/7772…/seat"),
            "端口 / 路径 / 查询 / 片段一个不丢（服务端那一份实现说了算）",
        )
        assertNull(api.fromVpnUrl("not-a-gateway"), "400 ⇒ null（屏上那句「无法解析」正好说的是这件事）")

        val failure = assertFailsWith<IllegalStateException> { api.fromVpnUrl("expired") }
        assertTrue(failure.message!!.contains("请先登录"), "401 要说「没登录」，不能说「网址不对」：${failure.message}")

        assertFailsWith<IllegalStateException> { api.fromVpnUrl("empty") }
    }

    /** 这三族在 campus-api 里没有端点（付款码走 `:data` 的 okhttp 借校园卡会话、反馈直连飞书、 WebVPN 是本地的纯函数） */
    @Test
    fun campusApiModeThrowsInsteadOfFakingEmptyData() = runTest {
        val engine = engineFor(
            "/api/pay/code" to """{"code":0,"data":{"barCode":"62123456789012345678"}}""",
            "/api/pay/vouchers" to """{"code":0,"data":{"count":0,"vouchers":[]}}""",
            "/api/feedback/replies" to """{"code":0,"data":{"tickets":[],"count":0,"replies":[]}}""",
            "/api/feedback/public-qa" to """{"code":0,"data":{"count":0,"items":[]}}""",
            "/api/webvpn/convert" to """{"code":0,"data":{"direction":"to_vpn","url":"x"}}""",
        )
        val client = createToolboxClient(engine = engine)
        // 注意：不传 mode（默认 = CAMPUS_API）
        assertFailsWith<UnsupportedOperationException> { CampusPayApi(client, "").getBarCode() }
        assertFailsWith<UnsupportedOperationException> { CampusPayApi(client, "").getVouchers() }
        assertFailsWith<UnsupportedOperationException> {
            CampusPayApi(client, "").updateVoucherStatus(listOf("SC-1"))
        }
        assertFailsWith<UnsupportedOperationException> { CampusFeedbackApi(client, "").publicQa() }
        assertFailsWith<UnsupportedOperationException> {
            CampusFeedbackApi(client, "").submit(
                ticket = "T1A2B3C4D5",
                category = "使用",
                content = "内容",
                contact = "",
                anonId = "a",
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            CampusFeedbackApi(client, "").replies(listOf("T1A2B3C4D5"))
        }
        assertFailsWith<UnsupportedOperationException> { CampusWebVpnApi(client, "").toVpnUrl("https://a.b/") }
        assertFailsWith<UnsupportedOperationException> { CampusWebVpnApi(client, "").fromVpnUrl("https://a.b/") }
    }
    /** 反馈提交（写）：请求体就是契约那五格（`ticket` / `category` / `content` / `contact` / `anonId`），`version` 不进请求体（服务端拿自己那份 `Config` 顶）；`data.ticket` 是服务端收下的那个号。 */
    @Test
    fun serveFeedbackSubmitPostsFiveFieldsAndKeepsFailuresLoud() = runTest {
        val posted = mutableListOf<String>()
        val okBody = """{"code":0,"data":{"ticket":"T1A2B3C4D5"}}"""
        val engine = MockEngine { request ->
            posted += (request.body as io.ktor.content.TextContent).text
            respond(okBody, HttpStatusCode.OK, JSON_HEADERS)
        }
        val api = CampusFeedbackApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        api.submit(
            ticket = "T1A2B3C4D5",
            category = "使用",
            content = "课表少了两门",
            contact = "",
            anonId = "anonfake01",
        )
        assertEquals(
            """{"ticket":"T1A2B3C4D5","category":"使用","content":"课表少了两门","contact":"","anonId":"anonfake01"}""",
            posted.single(),
            "请求体只有契约那五格（version 不进请求体；contact 空着也照发）",
        )

        // `400`：形状不对 ⇒ 抛，而且把服务端那句中文带上（不吞成一句笼统的失败）
        val badRequest = CampusFeedbackApi(
            createToolboxClient(
                engine = MockEngine {
                    respond(
                        """{"code":400,"message":"ticket 需要形如 T 加 8~16 位大写字母或数字"}""",
                        HttpStatusCode.BadRequest,
                        JSON_HEADERS,
                    )
                },
            ),
            "",
            ApiMode.SERVE,
        )
        val four = assertFailsWith<IllegalStateException> {
            badRequest.submit(ticket = "nope", category = "使用", content = "内容", contact = "", anonId = "a")
        }
        assertTrue(four.message!!.contains("ticket"), "400 要指名道姓说哪一格：${four.message}")

        // `502`：凭据没配全 / 上游故障 ⇒ 同样是抛（与两端同一条降级路径）
        val gateway = CampusFeedbackApi(
            createToolboxClient(
                engine = MockEngine {
                    respond(
                        """{"code":502,"message":"反馈后端凭据没配全：启动 serve 前设 FEEDBACK_APP_ID …"}""",
                        HttpStatusCode.BadGateway,
                        JSON_HEADERS,
                    )
                },
            ),
            "",
            ApiMode.SERVE,
        )
        val five = assertFailsWith<IllegalStateException> {
            gateway.submit(ticket = "T1A2B3C4D5", category = "使用", content = "内容", contact = "", anonId = "a")
        }
        assertTrue(five.message!!.contains("FEEDBACK_APP_ID"), "502 要原样带出「去哪儿配」：${five.message}")
    }

}
