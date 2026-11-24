package com.xjtu.toolbox.bus

import com.xjtu.toolbox.core.net.serveData
import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** 校车的 serve 版取数（`GET /api/bus/runs`）：给 Web 用，浏览器直连 xjbus 没有 CORS。 */
class CampusBusApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : BusSource {

    override suspend fun runs(date: LocalDate): List<BusRun> =
        parse(client.serveData("加载校车班次", baseUrl, "/api/bus/runs", listOf("date" to date.toString())))

    internal fun parse(data: JsonObject): List<BusRun> =
        AppJson.decodeFromJsonElement<List<RunDto>>(data["runs"] ?: return emptyList()).map { it.toModel() }

    @Serializable
    private data class StopDto(val name: String, val time: String? = null, val remark: String = "")

    @Serializable
    private data class RunDto(
        val lineName: String,
        val time: String,
        val category: String = "",
        val seats: Int = 0,
        val usedSeats: Int = 0,
        val plate: String = "",
        val from: String = "",
        val to: String = "",
        val stops: List<StopDto> = emptyList(),
    ) {
        fun toModel() = BusRun(
            lineName, time, category, seats, usedSeats, plate, from, to,
            stops.map { BusStop(it.name, it.time, it.remark) },
        )
    }
}
