package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.dormpower.DormPowerApi
import com.xjtu.toolbox.dormpower.DormPowerSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/dorm*` —— 宿舍电费的**两个只读端点**（`docs/api-contract.md` §5.5），走 `:data` 的
 * [DormPowerApi]（实现 `:core` 的 [DormPowerSource]；解析与 GBK 兜底在 `DormPowerParsers`，
 * 本层一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/dorm/rooms` | `DormPowerSource.rooms()`（`cid` 由实现从会话里取） |
 * | `GET /api/dorm/reading` | `DormPowerSource.kwh(roomId)` |
 *
 * ## 绑定 / 解绑 / 充值（写路径）**不端**
 *
 * 那三件事发生在缴费页里（浏览器端的事）：`DormPowerSource.freshPayUrl()` 只是「换一张能直接
 * 登录缴费页的入口地址」（`ssn` 站没有可搬运的 cookie，每次都要凭 OAuth 回调里一次性的 code
 * 换会话），而且它**顺手记下「用户进过这一页」**（`DormPowerStore.markUsed`）—— 那是宿主侧状态，
 * serve 这一层不该替浏览器记。所以本批连它一起不端（契约 §5.5 记着：`:web` 的「去缴费」那一跳
 * 在 serve 模式下没有端点）。
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `DormPowerModels`）
 *
 * - **rooms**：`DormRoom(id, name)` —— `id ← roomId`（**要原样带回来**给 `reading` 用的句柄）、
 *   `name ← roomName`（「园区/楼/单元/层/房间…」拼好的全名）。上游缺 `roomId` 的行由 `:data`
 *   丢掉（不是投影成空 id）；
 * - **reading**：`{roomId, kwh}` —— `kwh` 是**剩余电量（度）**，`:data` 的 `DormPowerParsers.kwh`
 *   读不到就是 `null`（§4：`null` = 上游明确说没有 —— 那间房没装表，**不是 0**）。
 *   ⚠️ **没有时间戳**：`:core` 的 `DormReading.at` 是 `DormPowerStore` 在**本机**记的「什么时候
 *   查到的」，上游不给 ⇒ 本端点不编（契约 §4：时间一律 ISO-8601，编一个反而是第二个口径）。
 *   要「这张读数是什么时候的」由消费方在**自己拿到响应的那一刻**记。
 *
 * ## 请求参数与错误码
 *
 * - `?roomId=`（`reading` 必填，取自 `/api/dorm/rooms` 的 `id`）；缺或空白 ⇒ 400；
 * - `rooms` 不收参数；
 * - 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句，含会话失效那档
 *   —— 上游的 `401001` 被 `:data` 认成登录过期，本层按 502 报「稍后重试可能就成」）。
 *
 * ⚠️ 与 campus-api 的同名能力对照：旧的是 `GET /api/dorm/kwh?roomId=`（一个端点把列表与读数
 * 混在一处）。本契约拆成 `rooms` / `reading` 两个（列表一次、读数一间一次），旧的路径不在
 * 本契约里。
 *
 * @param session 会话装配；宿舍电费站点（key = `ssn`）从它那里取（`cid` 是登录时写进会话的）。
 */
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

/**
 * `GET /api/dorm/reading` 的 `data`。
 *
 * `kwh` 为 `null` = 上游明确说「这间房没有表」（**不是 0**）；见文件头 KDoc。
 */
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
