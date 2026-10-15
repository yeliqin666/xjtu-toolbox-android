package com.xjtu.toolbox.venue

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusVenueApi
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `CampusVenueApi` 的 serve 模式（`ApiMode.SERVE`，契约 §5.3.2）写路径测试。 */
class CampusVenueServeWriteTest {

    /** 一个把「打出去的请求体」记下来的假 serve 后端。 */
    private class ServeMock {
        val postedBodies = mutableListOf<String>()
        var bookRejected = false
        var cancelRejected = false

        val client: HttpClient = createToolboxClient(
            engine = MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/api/venue/captcha") && request.method == HttpMethod.Get -> captchaPayload
                    path.endsWith("/api/venue/book") && request.method == HttpMethod.Post -> {
                        postedBodies += (request.body as TextContent).text
                        if (bookRejected) bookRejectedPayload else bookOkPayload
                    }
                    path.endsWith("/api/venue/cancel") && request.method == HttpMethod.Post -> {
                        postedBodies += (request.body as TextContent).text
                        if (cancelRejected) cancelRejectedPayload else cancelOkPayload
                    }
                    else -> error("测试没有为 ${request.method} $path 准备响应")
                }
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )
            },
        )
    }

    @Test
    fun serveModeTurnsBothSwitchesOnAndReadsTheChallenge() = runTest {
        val mock = ServeMock()
        val api = CampusVenueApi(mock.client, mode = ApiMode.SERVE)

        // 两个开关：serve 的两个写端点都已落地 ⇒ 与 `/api/venue/status` 的 canBook/canCancel 一致
        assertTrue(api.canBook, "写端点已落地（SERVE_WRITE_ENDPOINTS_READY=true）⇒ 屏上真能下单")
        assertTrue(api.canCancel, "取消是一条普通 POST ⇒ 同样如实 true")

        // 挑战：七格逐字段（captchaId ← 上游验证码号）
        val captcha = api.captcha(serviceId = 101)
        assertEquals("cap-1", captcha.id)
        assertEquals(260, captcha.bgWidth)
        assertEquals(160, captcha.bgHeight)
        assertEquals(50, captcha.sliderWidth)
        assertEquals(50, captcha.sliderHeight)
        assertTrue(captcha.backgroundImage.startsWith("data:image/jpeg;base64,"), "图是 data URI 原文")
        assertTrue(captcha.sliderImage.startsWith("data:image/png;base64,"))

        // 支付那一跳是 serve 自己的代跳地址（§5.3.2 第 6 条）；这里只钉地址形状。
        assertEquals("/api/venue/pay?orderId=VT20261013001", api.paymentUrl("VT20261013001"))
        // 订单号原样进 query（与 `:data` 拼支付页同一套 `java.net.URLEncoder` 编码：`/`→`%2F`、`+`→`%2B`）
        assertEquals("/api/venue/pay?orderId=A%2F1%2B2", api.paymentUrl("A/1+2"))
        // 给了 baseUrl 就是绝对地址（`:web` 是同源、留空那一档）
        assertEquals(
            "http://127.0.0.1:40961/api/venue/pay?orderId=VT20261013001",
            CampusVenueApi(mock.client, baseUrl = "http://127.0.0.1:40961", mode = ApiMode.SERVE)
                .paymentUrl("VT20261013001"),
        )
        // 登录入口那一格在 serve 形态下仍是空串：浏览器里两跳串不成一条链，登录由支付页自己那次跳转去要
        assertEquals("", api.browserLoginUrl)
    }

    @Test
    fun bookingSendsSelectionsCaptchaIdAndRawTrack() = runTest {
        val mock = ServeMock()
        val api = CampusVenueApi(mock.client, mode = ApiMode.SERVE)

        val selections = listOf(
            slot(areaDetailId = 5101, areaName = "场地1", stockId = 9101, timeSlot = "08:00-09:00", price = 20.0),
            slot(areaDetailId = 5102, areaName = "场地2", stockId = 9101, timeSlot = "08:00-09:00", price = 20.0),
        )
        // 两段式流程与两端一致：先备订单（serve 模式只是把选中项编码进 PendingOrder，不发请求），再要验证码
        val pending = api.prepareOrder(101, selections)
        val captcha = api.captcha(101)
        val result = api.submitBooking(101, pending, captcha.id, TRACK_JSON)

        assertTrue(result.success)
        assertEquals("VT20261013001", result.orderId)
        assertEquals(20.0, result.price)
        assertEquals("预订成功", result.message)

        val body = AppJson.parseToJsonElement(mock.postedBodies.single()).jsonObject
        assertEquals("101", body["serviceId"]!!.jsonPrimitive.content)
        assertEquals("cap-1", body["captchaId"]!!.jsonPrimitive.content)
        // 轨迹是**字符串**原文（服务端不解析、直接交给 :data 拼 yzm）
        assertEquals(TRACK_JSON, body["sliderTrack"]!!.jsonPrimitive.content)
        // selections 只说「哪个库存下的哪一条场地明细」：其余字段（名字/时段/价格）一个都不发
        val rows = body["selections"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals(
            setOf("stockId", "areaDetailId"),
            rows[0].jsonObject.keys,
            "上行只带写路径用得到的两个数（契约 §5.3.2）",
        )
        assertEquals("9101", rows[0].jsonObject["stockId"]!!.jsonPrimitive.content)
        assertEquals("5101", rows[0].jsonObject["areaDetailId"]!!.jsonPrimitive.content)
        assertEquals("5102", rows[1].jsonObject["areaDetailId"]!!.jsonPrimitive.content)
    }

    @Test
    fun businessRejectionBecomesFalseResultsNotExceptions() = runTest {
        val mock = ServeMock()
        val api = CampusVenueApi(mock.client, mode = ApiMode.SERVE)

        // 验证码有误 / 场地被抢（409 + 中文短句）：BookingResult(false, 原文)，屏上的横幅只认它
        mock.bookRejected = true
        val rejected = api.submitBooking(101, api.prepareOrder(101, listOf(slot())), "cap-1", TRACK_JSON)
        assertFalse(rejected.success)
        assertEquals("验证码有误，请重试", rejected.message)
        assertNull(rejected.orderId, "失败时没有订单号（不是编一个空串）")

        // 取消失败同理：OrderActionResult(false, 服务端那句)
        mock.cancelRejected = true
        val cancelFailed = api.cancelOrder("VT20261013001")
        assertFalse(cancelFailed.success)
        assertEquals("订单不存在", cancelFailed.message)

        // 成功那一侧：取消也是一条普通 POST（body 里就是订单号）
        mock.cancelRejected = false
        val cancelled = api.cancelOrder("VT20261013001")
        assertTrue(cancelled.success)
        assertEquals("取消成功", cancelled.message)
        assertEquals(
            "VT20261013001",
            AppJson.parseToJsonElement(mock.postedBodies.last()).jsonObject["orderId"]!!.jsonPrimitive.content,
        )
    }

    private fun slot(
        areaDetailId: Long = 5101,
        areaName: String = "场地1",
        stockId: Long = 9101,
        timeSlot: String = "08:00-09:00",
        price: Double = 20.0,
    ) = AreaSlot(
        areaDetailId = areaDetailId,
        areaName = areaName,
        stockId = stockId,
        timeSlot = timeSlot,
        price = price,
        date = "2026-10-12",
        allCount = 10,
        usingNum = 3,
        surplus = 7,
        serviceid = "101",
    )

    private companion object {
        /** 一条与 :core 的 `SliderResult.toJson()` 同形的轨迹原文（这里只当字符串使）。 */
        const val TRACK_JSON =
            """{"bgImageWidth":260,"bgImageHeight":0,"sliderImageWidth":0,"sliderImageHeight":50,""" +
                """"startSlidingTime":"2026-10-13T08:05:00Z","entSlidingTime":"2026-10-13T08:05:01Z",""" +
                """"trackList":[{"x":0,"y":0,"type":"down","t":900},{"x":141,"y":0,"type":"up","t":1500}]}"""

        val captchaPayload = """
            {"code":0,"data":{
              "captchaId":"cap-1",
              "backgroundImage":"data:image/jpeg;base64,ZmFrZS1iZw==",
              "sliderImage":"data:image/png;base64,ZmFrZS1zbGlkZXI=",
              "bgWidth":260,"bgHeight":160,"sliderWidth":50,"sliderHeight":50}}
        """.trimIndent()

        val bookOkPayload =
            """{"code":0,"data":{"ok":true,"orderId":"VT20261013001","price":20.0,"message":"预订成功"}}"""
        val bookRejectedPayload = """{"code":409,"message":"验证码有误，请重试"}"""
        val cancelOkPayload = """{"code":0,"data":{"ok":true,"message":"取消成功"}}"""
        val cancelRejectedPayload = """{"code":409,"message":"订单不存在"}"""
    }
}
