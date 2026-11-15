package com.xjtu.toolbox.bus

import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusApiTest {
    private fun api() = BusApi(HttpClient(MockEngine { error("本测试不应发请求") }))
    private fun parse(json: String) = api().parseRuns(AppJson.parseToJsonElement(json).jsonObject)

    // 字段形状照抓包写，人名是编的；真实响应别进仓库，里面有乘客姓名
    private val body = """
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

    @Test fun parsesAndSortsByTime() {
        val runs = parse(body)
        assertEquals(listOf("早班", "晚班"), runs.map { it.lineName })
        assertEquals("06:20", runs[0].time)
        assertEquals(34, runs[0].seatsLeft)
        assertTrue(runs[1].isFull)
    }

    @Test fun firstStopPlaceholderBecomesDepartureTime() {
        val stops = parse(body)[0].stops
        assertEquals("06:20", stops[0].time)
        assertEquals("06:50", stops[1].time)
    }

    @Test fun remarkDropsRepeatedStopName() {
        assertEquals("东门", parse(body)[1].stops[1].remark)
    }

    @Test fun passengerNamesNeverSurvive() {
        assertFalse(parse(body).toString().contains("测试乘客甲"))
    }

    @Test fun failureAndMissingDataThrow() {
        assertFailsWith<RuntimeException> { parse("""{"success":false,"data":[]}""") }
        assertFailsWith<RuntimeException> { parse("""{"success":true}""") }
    }

    @Test fun emptyDayIsNotAnError() {
        assertEquals(emptyList(), parse("""{"success":true,"data":[]}"""))
    }
}
