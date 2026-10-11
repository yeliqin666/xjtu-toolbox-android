package com.xjtu.toolbox.server

import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * 空闲教室两端点的契约测试（`/api/emptyroom/cdn` · `/api/emptyroom/rooms`）。
 *
 * 三档（实时 / CDN / 直查）的假上游都在 `:testkit`（`EmptyRoomFakeUpstream` 的
 * 智慧教室平台 + CDN 两个域、`JwxtFakeUpstream` 的直查教务四条 URL）。能力开关
 * `availableSources` 随会话如实变：未登录只有 CDN，登录后 [live, cdn, direct]。
 *
 * **红线**：`teacher` 字段是教室**当前这堂课**的教师名（公开课堂信息），夹具值
 * `示例丙` 是编造的 —— 断言它就等于夹具常量，同时响应绝不含登录学号。
 */
class ServeEmptyRoomTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `CDN 档：未登录即可查，逐字段；无数据那天 noData 而非报错；能力开关只有 cdn`() =
        withFakeProxy(casEnabled = false) { _ ->
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) { emptyRoomRoutes(session) }
            started = server

            // ── 主路：主楼A 那天的逐节占用表 ──
            val cdn = http.get("/api/emptyroom/cdn?campus=兴庆校区&building=主楼A&date=${EmptyRoomFakeUpstream.DATE}")
            assertEquals(200, cdn.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(cdn))
            assertNoIdentity(cdn.body(), "GET /api/emptyroom/cdn", LOGIN_IDENTITY)
            val data = envelopeData(cdn)
            assertEquals(listOf("key", "name"), (data.arrOf("availableSources")[0] as JsonObject).keys.toList().sorted())
            assertEquals(
                listOf("cdn"),
                data.arrOf("availableSources").map { (it as JsonObject).optString("key") },
                "未登录 ⇒ 能力开关只报 CDN 一档",
            )
            assertEquals("cdn", data.optString("source"))
            assertEquals("兴庆校区", data.optString("campus"))
            assertEquals(listOf("主楼A"), data.arrOf("buildings").map { it.toString().trim('"') })
            assertEquals(EmptyRoomFakeUpstream.DATE, data.optString("date"))
            assertEquals(false, data.optBoolean("noData"))
            assertEquals(null, data.optString("note"))

            // 五间房（junk 键被滤掉 / 缺 status 的被丢），按教室名排序，status[11] 与座位数逐字段；
            // 无容量房有 status 没有 size ⇒ size 落 0（:data 的 CDN 解析口径）
            val rooms = data.arrOf("rooms")
            assertEquals(5, rooms.size)
            assertRoomRow(rooms[0] as JsonObject, "兴庆校区", "主楼A", "203", 24, listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1))
            assertRoomRow(rooms[1] as JsonObject, "兴庆校区", "主楼A", EmptyRoomFakeUpstream.ROOM_A101, EmptyRoomFakeUpstream.SEATS_A101,
                listOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0))
            assertRoomRow(rooms[2] as JsonObject, "兴庆校区", "主楼A", EmptyRoomFakeUpstream.ROOM_A102, EmptyRoomFakeUpstream.SEATS_A102,
                List(11) { 0 })
            assertRoomRow(rooms[3] as JsonObject, "兴庆校区", "主楼A", EmptyRoomFakeUpstream.ROOM_A103, EmptyRoomFakeUpstream.SEATS_A103,
                listOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1))
            assertRoomRow(rooms[4] as JsonObject, "兴庆校区", "主楼A", "无容量房", 0, List(11) { 0 })

            // ── 没有数据的那天：noData=true + note，rooms 空，仍是 200 信封 ──
            val noData = http.get("/api/emptyroom/cdn?campus=兴庆校区&building=主楼A&date=${EmptyRoomFakeUpstream.NO_DATA_DATE}")
            assertEquals(200, noData.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(noData))
            val noDataBody = envelopeData(noData)
            assertEquals(true, noDataBody.optBoolean("noData"))
            assertTrue(noDataBody.optString("note")!!.isNotBlank(), "note 是中文短句")
            assertEquals(0, noDataBody.arrOf("rooms").size)

            // ── 缺 campus → 400 ──
            val missingCampus = http.get("/api/emptyroom/cdn?date=2026-10-10")
            assertEquals(400, missingCampus.statusCode())
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missingCampus))

            // 闸门
            assertEquals(401, http.get("/api/emptyroom/cdn", bearer = null).statusCode())
            assertEquals(401, http.get("/api/emptyroom/cdn", bearer = "wrong").statusCode())
        }

    @Test
    fun `登录后：直查与实时两档可用，能力开关报三档；实时带公开课堂信息`() = withFakeProxy(casEnabled = true) { _ ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            emptyRoomRoutes(session)
        }
        started = server

        // 未登录的 rooms 端点 → 401（直查/实时都要会话）
        val anonymousDirect = http.get("/api/emptyroom/rooms?source=direct&campus=兴庆校区&building=主楼A")
        assertEquals(401, anonymousDirect.statusCode())

        val login = http.postJson(SESSION_PATH + "/login", LOGIN_BODY)
        assertEquals(200, login.statusCode())
        assertTrue(envelopeData(login).optBoolean("authenticated") == true)

        // ── 直查教务档：与 CDN 同形状（status[11]），逐字段 ──
        val direct = http.get("/api/emptyroom/rooms?source=direct&campus=兴庆校区&building=主楼A&date=${EmptyRoomFakeUpstream.DATE}")
        assertEquals(200, direct.statusCode())
        assertNoIdentity(direct.body(), "GET /api/emptyroom/rooms（direct）", LOGIN_IDENTITY)
        val directData = envelopeData(direct)
        assertEquals(
            listOf("live", "cdn", "direct"),
            directData.arrOf("availableSources").map { (it as JsonObject).optString("key") },
            "登录过 ⇒ 三档都在能力开关里",
        )
        assertEquals("direct", directData.optString("source"))
        val directRooms = directData.arrOf("rooms")
        assertEquals(3, directRooms.size)
        assertRoomRow(directRooms[0] as JsonObject, "兴庆校区", "主楼A", EmptyRoomFakeUpstream.ROOM_A101, EmptyRoomFakeUpstream.SEATS_A101,
            listOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0), "直查与 CDN 同源：同一年的同一张表")
        assertRoomRow(directRooms[1] as JsonObject, "兴庆校区", "主楼A", EmptyRoomFakeUpstream.ROOM_A102, EmptyRoomFakeUpstream.SEATS_A102,
            List(11) { 0 })
        assertRoomRow(directRooms[2] as JsonObject, "兴庆校区", "主楼A", EmptyRoomFakeUpstream.ROOM_A103, EmptyRoomFakeUpstream.SEATS_A103,
            List(11) { 1 })

        // ── 实时档：live 快照逐字段（状态码 / 人数 / 课程 / 教师都是夹具编造值）──
        val live = http.get("/api/emptyroom/rooms?source=live&campus=兴庆校区")
        assertEquals(200, live.statusCode())
        val liveData = envelopeData(live)
        assertEquals("live", liveData.optString("source"))
        assertEquals(
            listOf("live", "cdn", "direct"),
            liveData.arrOf("availableSources").map { (it as JsonObject).optString("key") },
        )
        assertEquals(listOf(EmptyRoomFakeUpstream.BUILDING_A, EmptyRoomFakeUpstream.BUILDING_E),
            liveData.arrOf("buildings").map { it.toString().trim('"') }, "楼顺序照平台给")
        assertEquals(false, liveData.optBoolean("noData"))
        assertIsoRecent(liveData.optString("fetchedAt")!!)

        val liveRooms = liveData.arrOf("rooms")
        assertEquals(4, liveRooms.size)
        val inClass = liveRooms[0] as JsonObject
        assertEquals(EmptyRoomFakeUpstream.ROOM_A101, inClass.optString("name"))
        assertEquals(EmptyRoomFakeUpstream.BUILDING_A, inClass.optString("building"))
        assertEquals(3, inClass.optInt("status"), "上课中")
        assertEquals(EmptyRoomFakeUpstream.LIVE_IN_CLASS_PEOPLE, inClass.optInt("people"))
        assertEquals(EmptyRoomFakeUpstream.SEATS_A101, inClass.optInt("seats"))
        assertEquals(EmptyRoomFakeUpstream.LIVE_COURSE, inClass.optString("course"))
        assertEquals(EmptyRoomFakeUpstream.LIVE_TEACHER, inClass.optString("teacher"), "上课教室的教师名 = 夹具编造值")
        val free = liveRooms[1] as JsonObject
        assertEquals(2, free.optInt("status"), "空闲")
        assertEquals(0, free.optInt("people"))
        val inUse = liveRooms[3] as JsonObject
        assertEquals(1, inUse.optInt("status"), "其它使用")
        assertEquals(EmptyRoomFakeUpstream.LIVE_IN_USE_PEOPLE, inUse.optInt("people"))

        // 红线：live 响应里有教室上课的教师名（公开信息），但绝不含登录学号/密码/手机号键
        val liveBody = live.body()
        assertFalse(liveBody.contains(LibraryFakeUpstream.USERNAME), "响应体不该出现学号")
        assertFalse(liveBody.contains("password"))
        for (key in listOf("studentId", "学号", "手机号", "mobile")) {
            assertFalse(liveBody.contains(key), "响应体不该出现「$key」")
        }

        // ── source 参数不认 → 400 ──
        val badTier = http.get("/api/emptyroom/rooms?source=bogus&campus=兴庆校区")
        assertEquals(400, badTier.statusCode())

        // 闸门
        assertEquals(401, http.get("/api/emptyroom/rooms", bearer = null).statusCode())
        assertEquals(401, http.get("/api/emptyroom/rooms", bearer = "wrong").statusCode())
    }

    private fun assertRoomRow(
        obj: JsonObject,
        campus: String,
        building: String,
        room: String,
        seats: Int,
        status: List<Int>,
        message: String = "",
    ) {
        assertEquals(campus, obj.optString("campus"), message)
        assertEquals(building, obj.optString("building"), message)
        assertEquals(room, obj.optString("room"), message)
        assertEquals(seats, obj.optInt("seats"), message)
        assertEquals(status, obj.arrOf("status").map { it.toString().trim('"').toInt() }, message)
        assertEquals(setOf("campus", "building", "room", "seats", "status"), obj.keys)
    }

    private fun assertIsoRecent(iso: String) {
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?[+-]\d{2}:\d{2}""").matches(iso),
            "ISO-8601 带时区，实际：$iso",
        )
    }

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** 红线：登录用户本人的身份。 */
        val LOGIN_IDENTITY = listOf(
            LibraryFakeUpstream.USERNAME,
            "password",
            "username",
            "studentId",
            "学号",
            "手机号",
        )
    }
}