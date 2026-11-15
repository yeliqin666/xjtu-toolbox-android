package com.xjtu.toolbox.bus

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CampusBusApiTest {
    private val asked = mutableListOf<String>()

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK) = CampusBusApi(
        HttpClient(MockEngine { request ->
            asked += request.url.encodedPathAndQuery
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        baseUrl = "http://serve.test",
    )

    @Test fun readsServeShape() = runTest {
        val body = """{"code":0,"data":{"date":"2026-11-16","runs":[
            {"lineName":"早班","time":"06:20","category":"兴庆班车","seats":35,"usedSeats":1,"plate":"陕A2",
             "from":"思源南广场","to":"医学行政楼","stops":[
               {"name":"思源南广场","time":"06:20","remark":""},{"name":"医学行政楼","time":null,"remark":"东门"}]}]}}"""
        val runs = api(body).runs(LocalDate(2026, 11, 16))
        assertEquals(listOf("/api/bus/runs?date=2026-11-16"), asked)
        assertEquals(1, runs.size)
        assertEquals(34, runs[0].seatsLeft)
        assertNull(runs[0].stops[1].time)
        assertEquals("东门", runs[0].stops[1].remark)
    }

    @Test fun envelopeErrorThrows() = runTest {
        assertFailsWith<Exception> {
            api("""{"code":50201,"message":"加载校车班次失败"}""", HttpStatusCode.BadGateway).runs(LocalDate(2026, 11, 16))
        }
    }
}
