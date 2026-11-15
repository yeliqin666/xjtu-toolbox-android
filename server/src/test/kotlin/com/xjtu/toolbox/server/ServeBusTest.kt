package com.xjtu.toolbox.server

import com.xjtu.toolbox.bus.BusApi
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.util.todayOnCampus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 校车（`/api/bus/runs`，契约 §5.18）的契约测试：假上游照 `BusApi` 的请求形状写。 */
class ServeBusTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    // 字段照抓包写，人名是编的
    private val upstreamBody = """
        {"success":true,"code":200,"data":[
          {"lineName":"晚班","runTime":"18:15:00","busLabelIdLabel":"创新港班车","seatCount":49,"usedSeatCount":49,
           "departureStation":"创新港","arrivalStation":"思源中心","busNumber":"陕A1",
           "wayStationTimes":[{"wayStation":"创新港","wayStationTime":"00:00","remark":""},
                              {"wayStation":"思源中心","wayStationTime":null,"remark":"思源中心：东门"}],
           "stationUserMap":{"创新港":["测试乘客甲"]}},
          {"lineName":"早班","runTime":"06:20:00","busLabelIdLabel":"兴庆班车","seatCount":35,"usedSeatCount":1,
           "departureStation":"思源南广场","arrivalStation":"医学行政楼","busNumber":"陕A2",
           "wayStationTimes":[{"wayStation":"思源南广场","wayStationTime":"00:00","remark":""},
                              {"wayStation":"医学行政楼","wayStationTime":"06:50","remark":""}]}
        ]}
    """

    private val askedDates = mutableListOf<String?>()

    private fun busApi(status: HttpStatusCode = HttpStatusCode.OK) = BusApi(
        createToolboxClient(
            engine = MockEngine { request ->
                assertEquals("/api/school/bus/line/bus4driver", request.url.encodedPath)
                askedDates += request.url.parameters["date"]
                respond(upstreamBody, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    private fun start(api: BusApi): EndpointHarness {
        val (server, http) = startEndpointServer(AccessToken.newToken()) { busRoutes(api) }
        started = server
        return http
    }

    @Test
    fun `班次逐字段，按发车时间排序，乘客名单不出现`() {
        val http = start(busApi())
        val response = http.get("/api/bus/runs?date=2026-11-16")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertEquals(listOf<String?>("2026-11-16"), askedDates)
        assertFalse("测试乘客甲" in response.body(), "乘客名单漏到了响应里")
        assertFalse("stationUserMap" in response.body())

        val data = envelopeData(response)
        assertEquals("2026-11-16", data.getValue("date").jsonPrimitive.content)
        val runs = data.getValue("runs").jsonArray.map { it.jsonObject }
        assertEquals(listOf("早班", "晚班"), runs.map { it.str("lineName") })

        val early = runs[0]
        assertEquals("06:20", early.str("time"))
        assertEquals("兴庆班车", early.str("category"))
        assertEquals(35, early.int("seats"))
        assertEquals(1, early.int("usedSeats"))
        assertEquals("陕A2", early.str("plate"))
        assertEquals("思源南广场", early.str("from"))
        assertEquals("医学行政楼", early.str("to"))
        val stops = early.getValue("stops").jsonArray.map { it.jsonObject }
        assertEquals("06:20", stops[0].str("time"), "发车站的 00:00 换成发车时间")
        assertEquals("06:50", stops[1].str("time"))

        val late = runs[1].getValue("stops").jsonArray.map { it.jsonObject }
        assertEquals(JsonNull, late[1]["time"], "没时间的站点给 null")
        assertEquals("东门", late[1].str("remark"))
    }

    @Test
    fun `不带 date 查校园时区的今天`() {
        val http = start(busApi())
        val response = http.get("/api/bus/runs")
        assertEquals(200, response.statusCode())
        assertEquals(listOf<String?>(todayOnCampus().toString()), askedDates)
        assertEquals(todayOnCampus().toString(), envelopeData(response).getValue("date").jsonPrimitive.content)
    }

    @Test
    fun `date 形状不对 400，不打上游`() {
        val http = start(busApi())
        val response = http.get("/api/bus/runs?date=11-16")
        assertEquals(400, response.statusCode())
        assertEquals(emptyList<String?>(), askedDates)
    }

    @Test
    fun `上游失败 502，免登录也不要令牌以外的会话`() {
        val http = start(busApi(HttpStatusCode.InternalServerError))
        val response = http.get("/api/bus/runs?date=2026-11-16")
        assertEquals(502, response.statusCode())
        assertEquals(401, http.get("/api/bus/runs", bearer = null).statusCode())
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()
}
