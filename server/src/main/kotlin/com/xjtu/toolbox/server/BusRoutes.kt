package com.xjtu.toolbox.server

import com.xjtu.toolbox.bus.BusApi
import com.xjtu.toolbox.bus.BusRun
import com.xjtu.toolbox.bus.BusSource
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.util.todayOnCampus
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** 校车（契约 §5.18）：免登录，转发 `xjbus.xjtu.edu.cn` 的班次；乘客名单在 [BusApi] 里就丢了。 */
internal fun Route.busRoutes(source: BusSource = BusApi(createToolboxClient())) {
    get(BUS_RUNS_SEGMENT) {
        val raw = call.request.queryParameters["date"]?.trim().orEmpty()
        val date = if (raw.isEmpty()) {
            todayOnCampus()
        } else {
            runCatching { LocalDate.parse(raw) }.getOrNull()
                ?: return@get call.respondBadRequestMessage("参数 date 要写成 yyyy-MM-dd，实际：$raw")
        }
        val runs = try {
            source.runs(date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@get call.respondUpstreamFailure(e, "加载校车班次")
        }
        call.respond(ApiEnvelope.ok(BusRunsPayload(date.toString(), runs.map { it.toDto() })))
    }
}

/** `GET /api/bus/runs` 的 `data`。 */
@Serializable
internal data class BusRunsPayload(val date: String, val runs: List<BusRunDto>)

/** [BusRun] 的线上形状（余量等派生值由客户端按 seats/usedSeats 算）。 */
@Serializable
internal data class BusRunDto(
    val lineName: String,
    val time: String,
    val category: String,
    val seats: Int,
    val usedSeats: Int,
    val plate: String,
    val from: String,
    val to: String,
    val stops: List<BusStopDto>,
)

@Serializable
internal data class BusStopDto(val name: String, val time: String?, val remark: String)

private fun BusRun.toDto() = BusRunDto(
    lineName, time, category, seats, usedSeats, plate, from, to,
    stops.map { BusStopDto(it.name, it.time, it.remark) },
)

internal const val BUS_RUNS_SEGMENT = "bus/runs"
