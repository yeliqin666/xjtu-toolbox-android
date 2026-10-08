package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.emptyroom.NoDataException
import com.xjtu.toolbox.emptyroom.RoomSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `CampusEmptyRoomApi`（Web 端的空闲教室取数）的**口径测试**。
 *
 * 为什么值得写：Web 端这一档的取数没有第二个地方能验证（浏览器里跑一次只能看出"有列表"，
 * 看不出"楼筛对没对、座位缺失算不算 0、这一天没数据会不会被当成请求失败"）。这里用 MockEngine
 * 喂 campus-api 的**实测形状**（2026-10-08 实测 948 间：`{campus, building, room, seats, status[11], free[]}`，
 * `seats` 偶有 null、无数据那天是 `noData:true` + `note`），把映射钉死，不依赖真服务器。
 */
class CampusEmptyRoomApiTest {

    private val date = "2026-10-08"

    private val payload = """
        {"code":0,"error":null,"data":{"module":"emptyroom-cdn","date":"$date",
          "campuses":["兴庆校区","雁塔校区"],"total":4,"matched":4,"truncated":false,
          "rooms":[
            {"campus":"兴庆校区","building":"主楼A","room":"主楼A-102","seats":96,
             "status":[1,1,0,0,1,1,1,1,1,1,1],"free":[3,4]},
            {"campus":"兴庆校区","building":"主楼A","room":"主楼A-101","seats":null,
             "status":[0,0,0,0,0,0,0,0,0,0,0],"free":[1,2,3,4,5,6,7,8,9,10,11]},
            {"campus":"兴庆校区","building":"中2","room":"中2-1001","seats":40,
             "status":[0,1,0,0,0,0,0,0,0,0,0],"free":[1,3]},
            {"campus":"雁塔校区","building":"主楼A","room":"主楼A-999","seats":10,
             "status":[1,1,1,1,1,1,1,1,1,1,1],"free":[]}
          ]}}
    """.trimIndent()

    private val noDataPayload = """
        {"code":0,"error":null,"data":{"date":"2030-01-01","rooms":[],"noData":true,
          "note":"该日期暂无数据（CDN 404）"}}
    """.trimIndent()

    private class Recorder {
        var calls = 0
    }

    private fun apiFor(body: String, recorder: Recorder = Recorder()): CampusEmptyRoomApi {
        val engine = MockEngine { request ->
            recorder.calls++
            check(request.url.encodedPath.endsWith("/api/emptyroom/cdn")) { "不该请求 ${request.url.encodedPath}" }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        return CampusEmptyRoomApi(createToolboxClient(engine = engine))
    }

    @Test
    fun buildingFilterIsLocalAndNamesSortByRoomName() = runTest {
        val rooms = apiFor(payload).rooms("兴庆校区", setOf("主楼A"), date, direct = false, force = false) { _, _ -> }
        // 只留 兴庆校区 + 主楼A（另外两个校区的、中2 的都不算），按教室名排序
        assertEquals(listOf("主楼A-101", "主楼A-102"), rooms.map { it.name })
        // status 原样搬过来（屏上的节次条靠它）
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), rooms[0].status)
        assertEquals(listOf(1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1), rooms[1].status)
    }

    @Test
    fun missingSeatsFallBackToZeroLikeApp() = runTest {
        // 上游偶有教室没 seats（实测 948 间里 3 间）⇒ :app 的 CDN 解析对 size 缺失也落 0
        val rooms = apiFor(payload).rooms("兴庆校区", setOf("主楼A"), date, direct = false, force = false) { _, _ -> }
        assertEquals(0, rooms.first { it.name == "主楼A-101" }.size)
        assertEquals(96, rooms.first { it.name == "主楼A-102" }.size)
    }

    @Test
    fun oneRequestPerDateAndFullCacheAfterwards() = runTest {
        val recorder = Recorder()
        val api = apiFor(payload, recorder)
        api.rooms("兴庆校区", setOf("主楼A"), date, direct = false, force = false) { _, _ -> }
        api.rooms("兴庆校区", setOf("中2"), date, direct = false, force = false) { _, _ -> }
        assertEquals(1, recorder.calls, message = "同一日期第二次查楼不该再打一次请求")
        // 下拉刷新（force）绕过缓存重新取
        api.rooms("兴庆校区", setOf("主楼A"), date, direct = false, force = true) { _, _ -> }
        assertEquals(2, recorder.calls)
    }

    @Test
    fun staleFallbackUsesTheFetchedSnapshotAndItsTimestamp() = runTest {
        val api = apiFor(payload)
        assertNull(api.readStaleRooms("兴庆校区", setOf("主楼A"), date, direct = false), message = "还没拉过就没有兜底")
        api.rooms("兴庆校区", setOf("主楼A"), date, direct = false, force = false) { _, _ -> }
        val stale = api.readStaleRooms("兴庆校区", setOf("主楼A"), date, direct = false)
        assertEquals(listOf("主楼A-101", "主楼A-102"), stale?.first?.map { it.name })
        assertTrue((stale?.second ?: 0L) > 0L, message = "兜底要带上'什么时候拿到的'，屏上写'缓存于 HH:mm'")
    }

    @Test
    fun noDataDateIsNoDataNotARequestFailure() = runTest {
        // 与 :app 的 CDN 404 同义：这一天没数据（直接报错），而不是请求失败（那才回退缓存）
        val e = assertFailsWith<NoDataException> {
            apiFor(noDataPayload).rooms("兴庆校区", setOf("主楼A"), "2030-01-01", direct = false, force = false) { _, _ -> }
        }
        assertEquals("该日期暂无数据（CDN 404）", e.message)
    }

    @Test
    fun webOnlyHasTheCdnTier() = runTest {
        val api = apiFor(payload)
        assertEquals(listOf(RoomSource.CDN), api.availableSources)
        // 另两档不是"暂时失败"，是本端没有这条数据 ⇒ 报了也只会被屏用 availableSources 挡掉
        assertFailsWith<RuntimeException> { api.liveSnapshot("兴庆校区", force = false) }
        assertNull(api.readStaleLive("兴庆校区"))
        assertFailsWith<RuntimeException> {
            api.rooms("兴庆校区", setOf("主楼A"), date, direct = true, force = false) { _, _ -> }
        }
    }

    @Test
    fun availableDatesAreTodayAndTomorrow() = runTest {
        val dates = apiFor(payload).availableDates()
        assertEquals(2, dates.size)
        assertEquals(1, kotlinx.datetime.LocalDate.parse(dates[1]).toEpochDays() -
            kotlinx.datetime.LocalDate.parse(dates[0]).toEpochDays())
    }
}
