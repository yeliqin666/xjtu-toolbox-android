package com.xjtu.toolbox.bus

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 校车时刻（`xjbus.xjtu.edu.cn` 的 H5 司机页用的接口，不用登录）。
 *
 * 响应里的 `stationUserMap` 是按站点列的乘客姓名：这里只按字段名取需要的，绝不读它，
 * 也不落盘、不打日志，别为了方便把整段响应存下来。
 */
class BusApi(
    private val client: HttpClient,
    private val baseUrl: String = BASE_URL,
) : BusSource {

    override suspend fun runs(date: LocalDate): List<BusRun> {
        val response = client.get("$baseUrl/api/school/bus/line/bus4driver") {
            parameter("date", date.toString())
            parameter("lineId", "")
            parameter("busLabelId", "")
            parameter("keyWord", "")
            header("Accept", "application/json")
            header("Referer", "$baseUrl/h5/")
        }
        // 只带状态码，不让上游内容混进用户可见文案
        if (!response.status.isSuccess()) throw RuntimeException("校车接口 HTTP ${response.status.value}")
        return parseRuns(AppJson.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    internal fun parseRuns(root: JsonObject): List<BusRun> {
        val ok = root["success"].safeStringOrNull() == "true"
        val data = root["data"] as? JsonArray
        if (!ok || data == null) throw RuntimeException("校车接口返回异常")
        return data.mapNotNull { (it as? JsonObject)?.let(::parseRun) }.sortedBy { it.time }
    }

    private fun parseRun(o: JsonObject): BusRun? {
        val line = o.text("lineName") ?: return null
        val time = o.text("runTime")?.take(5) ?: return null
        val stops = (o["wayStationTimes"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val stop = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = stop.text("wayStation") ?: return@mapIndexedNotNull null
            // 发车站的 00:00 是占位，真正的时间就是发车时间
            val at = stop.text("wayStationTime")?.takeUnless { it == "00:00" } ?: time.takeIf { index == 0 }
            BusStop(name, at, cleanRemark(name, stop.text("remark").orEmpty()))
        }
        return BusRun(
            lineName = line,
            time = time,
            category = o.text("busLabelIdLabel").orEmpty(),
            seats = o.int("seatCount"),
            usedSeats = o.int("usedSeatCount"),
            plate = o.text("busNumber").orEmpty(),
            from = o.text("departureStation") ?: stops.firstOrNull()?.name.orEmpty(),
            to = o.text("arrivalStation") ?: stops.lastOrNull()?.name.orEmpty(),
            stops = stops,
        )
    }

    /** 上游写成「阳光小区：阳光小区西门路对面」，站名已经单独显示了。 */
    private fun cleanRemark(name: String, remark: String) =
        remark.trim().removePrefix(name).removePrefix("：").removePrefix(":").trim()

    private fun JsonObject.text(key: String): String? = this[key].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    companion object {
        const val BASE_URL = "https://xjbus.xjtu.edu.cn"
    }
}
