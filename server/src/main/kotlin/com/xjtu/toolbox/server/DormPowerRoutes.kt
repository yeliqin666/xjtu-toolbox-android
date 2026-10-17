package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.dormpower.DormPowerApi
import com.xjtu.toolbox.dormpower.DormPowerSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/dorm*`。 */
internal fun Route.dormRoutes(session: ServeSession) {
    route(DORM_SEGMENT) {

        // ── 已绑定的宿舍列表 ──────────────────────────────────────────
        get(DORM_ROOMS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val rooms = session.dormPowerSource().rooms()
                call.respond(ApiEnvelope.ok(DormRoomsData(rooms.map { DormRoomDto(id = it.id, name = it.name) })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载宿舍列表")
            }
        }

        // ── 某间宿舍的剩余电量 ────────────────────────────────────────
        get(DORM_READING) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val roomId = call.request.queryParameters[DORM_PARAM_ROOM_ID]?.trim().orEmpty()
            if (roomId.isEmpty()) {
                return@get call.respondBadRequestMessage("roomId 必填（取自 /api/dorm/rooms 的 id）")
            }
            try {
                call.respond(ApiEnvelope.ok(DormReadingData(roomId = roomId, kwh = session.dormPowerSource().kwh(roomId))))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载宿舍电量")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/dorm/rooms` 的 `data`。 */
@Serializable
internal data class DormRoomsData(val rooms: List<DormRoomDto>)

/** 一间已绑定的宿舍（`DormRoom` 的线上形状）。 */
@Serializable
internal data class DormRoomDto(val id: String, val name: String)

/** `GET /api/dorm/reading` 的 `data`。 */
@Serializable
internal data class DormReadingData(val roomId: String, val kwh: Double?)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 宿舍电费站点会话（key = `ssn`，`:data` 的 `SsnSession`：`cid` 在登录时写进 `localToken`）。 */
internal fun ServeSession.dormPowerSite(): SiteSession = sessionManager.getSite(ServeSession.SSN_SITE_KEY)

/** 取数实例：`:data` 的 [DormPowerApi] 本身就是端口实现（桌面端用的也是它）。 */
internal fun ServeSession.dormPowerSource(): DormPowerSource = DormPowerApi(dormPowerSite())

internal const val DORM_SEGMENT = "dorm"
internal const val DORM_ROOMS = "rooms"
internal const val DORM_READING = "reading"
internal const val DORM_PARAM_ROOM_ID = "roomId"
