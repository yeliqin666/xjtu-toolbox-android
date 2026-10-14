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

/**
 * serve 契约 §5.7（付款码 / 反馈 / WebVPN）的**客户端那一侧**。
 *
 * 为什么值得写：这三族是 2026-10-14 接进 `:web` 的（`server/…/PayRoutes.kt` 等三个路由文件），屏是
 * `:core` 的共享屏、取数是这三个新 `Campus*Api` —— 它们只能靠「真 `:server` + 真会话」在浏览器里
 * 验证，而这里用 MockEngine 喂**服务端实现出来的形状**（那些路由的 DTO 就是这些字段），把四件事
 * 钉死：
 *
 *  1. **错误信封的读法**：`code != 0` → 显示 `message`（不读旧路的 `msg`/`error`，不拿 HTTP 状态码分支）；
 *  2. **字段形状**：`barCode` 是一串字符串、券的五格、`replies` 是**数组**而 `public-qa` 的
 *     `items` 三条原文、`POST /api/webvpn/convert` 的请求体就是 `{url, direction}` 两个字段；
 *  3. **缺字段 / 缺端点不编造**：空白 `barCode` 抛错（不画一张空卡）、没有 `showCardId` 的行不列、
 *     `serverFlag` 如实留空串、归不到工单上的回复不列、`ticket` 空白不列、只问到的那些才回；
 *     两个**写路径**（勾选同步 / 提交反馈）在端口上报 `false`、方法本身抛错；
 *  4. **两个方向的失败口径不一样**（webvpn）：`from_vpn` 的 `400` 如实翻成 `null`（屏上那句
 *     「无法解析此 WebVPN 网址」），而 `401` 抛错 —— 不把「没登录」说成「网址格式不对」。
 *
 * 默认形态（[ApiMode.CAMPUS_API]）不由这里细管：这三族在 campus-api 里**没有**端点，最后一条用例
 * 钉的就是「非 serve 分支抛错，而不是悄悄返回一份空数据」。
 */

/**
 * 一发请求的快照：`路径 to 参数表`（与 `ServeReadonlyFourDomainsApiTest` 同一套读法；
 * 名字不与别的测试文件共用 —— `private typealias` 在同一个包里**仍会重名冲突**）。
 *
 * ⚠️ 类型别名只能放文件层（Kotlin 不支持类里嵌套 typealias）。
 */
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

    /**
     * 付款码：`authenticate()` 在 serve 这一端是**本地什么都不做**（会话在服务端进程里，页面上没有
     * 可看的东西）⇒ 它一发请求都不打；`barCode` 就是服务端端出来的那一串（**字符串**，不是数字）。
     *
     * 加餐券那一半同一条用例：五格逐一投影、`amountFen` 是**分**、`serverFlag` **不投影** ⇒ 空串，
     * `showCardId` 为空的那一行不列；而勾选**只读**（写路径不端，契约 §5.7）。
     */
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
        val engine = MockEngine { request ->
            record(calls, request)
            val body = when {
                request.url.encodedPath.endsWith("/api/pay/code") -> codeBody
                request.url.encodedPath.endsWith("/api/pay/vouchers") -> vouchersBody
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

        assertFalse(api.canSelectVouchers, "勾选同步是写路径（契约 §5.7 不端）⇒ 屏上只读")
        assertFailsWith<UnsupportedOperationException> { api.updateVoucherStatus(setOf("SC-1")) }
    }

    /**
     * 付款码空了就**如实报错** —— `getBarCode()` 只在拿到非空那一串时才回来。
     *
     * 为什么这条要紧：屏拿到空串会画一张「只有框、没有码」的卡，用户对着它扫半天；而 §5.7 的
     * `data.barCode` 是必填那一格，空了说明服务端 / 上游不对劲，不是「这次没有码」。
     */
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

    /**
     * 失败时只读 `message`：`code != 0` 就是失败，`message` 是能直接给用户看的中文短句；
     * 旧路的 `msg` / `error` 在新契约里**不存在**（读它们等于永远拿到空串）—— 所以夹具里故意
     * 塞了一个 `msg`，断言它**没有**被读出来。
     */
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

    /**
     * 反馈回复：`?tickets=` 去空白、去重、逗号分隔（顺序 = 请求给的先后）；响应里 `replies` 是**数组**，
     * 本类把它收成 `工单号 → 回复`；**没人回过的工单不在响应里**（`count` = 真取到几条）⇒
     * 本类如实只回那一张**小表**，屏上那句「处理中」就是它。
     */
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
        assertFalse(api.canSubmit, "提交是写路径（契约 §5.7 不端）⇒ 屏上整块表单不画")
        assertFailsWith<UnsupportedOperationException> {
            api.submit(ticket = "T1", category = "问题", content = "内容", contact = "", anonId = "anon")
        }
    }

    /**
     * WebVPN 正向：请求体就是契约那**两个字段**（`direction=to_vpn`），响应里的地址原样交回；
     * 已经是网关地址的输入由服务端原样返回（`:data` 的老口径，本层不改）—— 本类不自己判形状。
     */
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

    /**
     * WebVPN 反向，两种失败**刻意分开**：
     *
     *  - `400`（不是网关地址 / 路径不成形）⇒ **如实返回 `null`**：契约 §5.7 明确这一档不是
     *    `data:null`，屏自己那句「无法解析此 WebVPN 网址，请确认格式正确」说的正是这件事；
     *  - `401`（令牌闸门）⇒ **抛错**，消息用服务端那句「请先登录」。把它也翻成 `null` 就是把
     *    「没登录」说成「网址格式不对」—— 一句假话；
     *  - 成功但 `data.url` 是空的 ⇒ 抛错（不编一个默认地址出来）。
     */
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

    /**
     * 这三族在 campus-api 里**没有端点**（付款码走 `:data` 的 okhttp 借校园卡会话、反馈直连飞书、
     * WebVPN 是本地的纯函数）⇒ 非 serve 分支抛错，**不许**悄悄返回一份空数据（那会把「形态不对」
     * 说成「真没有」）。
     */
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
        assertFailsWith<UnsupportedOperationException> { CampusFeedbackApi(client, "").publicQa() }
        assertFailsWith<UnsupportedOperationException> {
            CampusFeedbackApi(client, "").replies(listOf("T1A2B3C4D5"))
        }
        assertFailsWith<UnsupportedOperationException> { CampusWebVpnApi(client, "").toVpnUrl("https://a.b/") }
        assertFailsWith<UnsupportedOperationException> { CampusWebVpnApi(client, "").fromVpnUrl("https://a.b/") }
    }
}
