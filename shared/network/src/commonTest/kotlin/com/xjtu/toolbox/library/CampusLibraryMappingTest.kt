package com.xjtu.toolbox.library

import com.xjtu.toolbox.core.net.CampusLibraryApi
import com.xjtu.toolbox.core.net.createToolboxClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** `CampusLibraryApi`（Web 端的图书馆取数）的字段映射测试。 */
class CampusLibraryMappingTest {

    private val campusPayload = """
        {"code":0,"data":{
          "current":{"code":"west","name":"雁塔校区图书馆"},
          "campuses":[{"code":"east","name":"兴庆校区 钱学森图书馆"},{"code":"west","name":"雁塔校区图书馆"}],
          "queryableFloors":["xingqing2floor","xingqing3floor","xingqing4floor"],
          "note":"本模块**只读**：不提供切校区（那是改账号资料的写操作）。",
          "parsed":true}}
    """.trimIndent()

    private val campusUnparsedPayload = """
        {"code":0,"data":{"current":{"code":null,"name":null},"campuses":[],"queryableFloors":[],"parsed":false}}
    """.trimIndent()

    /** 一层的区域表。第 3 行是**统计没到**（`total:null`），第 4 行没有区域码（`findArea` 那侧要跳过它）。 */
    private val areasPayload = """
        {"code":0,"data":{"floor":"xingqing2floor","areaCount":3,
          "areas":[
            {"code":"north2east","name":"北楼二层外文库（东）","floor":"xingqing2floor","total":330,"available":206,"occupied":124,"open":true},
            {"code":"north2elian","name":"二层连廊及流通大厅","floor":"xingqing2floor","total":156,"available":35,"occupied":121,"open":true},
            {"code":"ghost","name":"统计没到的区域","floor":"xingqing2floor","total":null,"available":null,"occupied":null,"open":false},
            {"code":"","name":"没有码的一行","floor":"xingqing2floor","total":1,"available":1,"occupied":0,"open":true}
          ],
          "totals":{
            "xingqing2floor":{"code":"xingqing2floor","total":759,"available":372},
            "east":{"code":"east","total":2158,"available":1394}
          },
          "cached":false}}
    """.trimIndent()

    /** 不带 `floor` 的那一份：全校区，用来给 `warmCampusAreas` 一次填齐区域名/楼层/统计。 */
    private val areasCampusWidePayload = """
        {"code":0,"data":{"campus":null,"floors":[{"code":"xingqing2floor","name":"二楼"}],"areaCount":1,
          "areas":[{"code":"south2","name":"南楼二层大厅","floor":"xingqing2floor","total":108,"available":29,"occupied":79,"open":true}],
          "totals":{"xingqing2floor":{"code":"xingqing2floor","total":759,"available":372}},
          "jsonSeen":true,"cached":true}}
    """.trimIndent()

    /** 座位表：**故意乱序**（上游是对象键序），且有一行没有座位号。 */
    private val seatsPayload = """
        {"code":0,"data":{"area":"south2","total":5,"available":2,
          "seats":[
            {"id":"C39","available":false,"raw":1},
            {"id":"C38","available":true,"raw":0},
            {"id":"C100","available":true,"raw":0},
            {"id":"C9","available":false,"raw":1},
            {"id":"","available":true,"raw":0}
          ],"cached":false}}
    """.trimIndent()

    private val myNonePayload = """
        {"code":0,"data":{"url":"http://rg.lib.xjtu.edu.cn:8086/my/","state":"none","verified":true,
          "statusText":null,"seatId":null,"seatLine":null,"actions":[],
          "markers":{"hasWell":false,"hasNotWell":false,"hasStatusLabel":false,"emptyHints":["暂无"]},
          "note":null,"cached":false}}
    """.trimIndent()

    /** ⚠️ campus-api 自己给这一支标了 `verified:false`（它没有预约样本）；`actions` 只有文案没有地址。 */
    private val myBookedPayload = """
        {"code":0,"data":{"url":"http://rg.lib.xjtu.edu.cn:8086/my/","state":"booked","verified":false,
          "statusText":"使用中","seatId":"056","seatLine":"北楼四层西南侧 056",
          "actions":["入馆签到","中途离开"],"markers":{"hasWell":true},
          "note":"booked 分支解析未经真机验证（无预约样本）；字段可能为空，请以网页为准。","cached":false}}
    """.trimIndent()

    /** 认不出来（改版/错误页）—— 必须失败，不能当成「没有预约」。 */
    private val myUnrecognizedPayload = """
        {"code":0,"data":{"state":"unrecognized","verified":false,"statusText":null,"seatId":null,
          "seatLine":null,"actions":[],"markers":{},"note":null,"cached":false}}
    """.trimIndent()

    private val errorPayload = """
        {"code":1,"error":"图书馆 qspace status=500","msg":"图书馆 qspace status=500"}
    """.trimIndent()

    private fun apiFor(vararg bodies: Pair<String, String>): CampusLibraryApi {
        val byPath = bodies.toMap()
        val engine = MockEngine { request ->
            val body = byPath.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        return CampusLibraryApi(createToolboxClient(engine = engine))
    }

    @Test
    fun campusOnlyTrustsCurrentCode() = runTest {
        // ⚠️ 只认 current.code：`west` = 雁塔（current.name 那句「雁塔校区图书馆」与屏上的 displayName 无关）
        assertEquals(LibraryCampus.YANTA, apiFor("/api/library/campus" to campusPayload).campus())

        // parsed=false（/modify 里认不出 selected）⇒ null，不猜一个校区出来
        assertNull(apiFor("/api/library/campus" to campusUnparsedPayload).campus())
    }

    @Test
    fun areasMappingKeepsOrderAndSkipsRowsWithoutStats() = runTest {
        val api = apiFor("/api/library/areas" to areasPayload)
        val areas = api.areas("xingqing2floor")

        // 顺序照上游；没有码的那一行跳过（与 :app 的 getFloorAreas 同一个判据）
        assertEquals(listOf("north2east", "north2elian", "ghost"), areas.keys.toList())
        assertEquals("北楼二层外文库（东）", areas["north2east"])

        // ⚠️ total:null 的那一行不进表
        assertEquals(AreaStats(available = 206, total = 330), api.areaStats()["north2east"])
        assertEquals(AreaStats(available = 35, total = 156), api.areaStats()["north2elian"])
        assertNull(api.areaStats()["ghost"])
        // 汇总键（楼层、校区）不能混进区域统计
        assertFalse("xingqing2floor" in api.areaStats().keys)
        assertFalse("east" in api.areaStats().keys)

        // 区域名 / 楼层：没学过就不下结论
        assertEquals("统计没到的区域", api.areaNameOf("ghost"))
        assertEquals("never-seen", api.areaNameOf("never-seen"))
        assertEquals("xingqing2floor", api.floorOfArea("north2east"))
        assertNull(api.floorOfArea("never-seen"))
        assertFalse(api.isForeignArea("北楼二层外文库（东）"))
        assertTrue(api.isForeignArea("雁塔 一楼"))
    }

    @Test
    fun warmCampusAreasFillsTheWholeCampusInOneRequest() = runTest {
        val api = apiFor("/api/library/areas" to areasCampusWidePayload)
        // 冷启动时不知道任何区域 ⇒ 不下「这是外校区」的结论
        assertFalse(api.isForeignArea("南楼二层大厅"))

        api.warmCampusAreas(LibraryCampus.XINGQING)

        assertEquals("南楼二层大厅", api.areaNameOf("south2"))
        assertEquals("xingqing2floor", api.floorOfArea("south2"))
        assertEquals(AreaStats(available = 29, total = 108), api.areaStats()["south2"])
        assertFalse(api.isForeignArea("南楼二层大厅"))
        assertTrue(api.isForeignArea("别的校区的区域"))
    }

    @Test
    fun seatsAreSortedLikeTheApp() = runTest {
        val api = apiFor(
            "/api/library/areas" to areasCampusWidePayload,
            "/api/library/seats" to seatsPayload,
        )
        api.warmCampusAreas(LibraryCampus.XINGQING)
        val result = api.seats("south2") as SeatResult.Success

        // ⚠️ 与 :app 的 getSeats 同一个排序（首个字母 → 数字部分），不是上游的对象键序
        assertEquals(listOf("C9", "C38", "C39", "C100"), result.seats.map { it.seatId })
        // 没有座位号的那一行丢掉；available 直接照搬（campus-api 已翻过）
        assertEquals(listOf(false, true, false, true), result.seats.map { it.available })
        // 附带的就是本端已经学到的那份区域统计
        assertEquals(AreaStats(available = 29, total = 108), result.areaStatsMap["south2"])
    }

    @Test
    fun myBookingStatesMapToSuccessOrFailure() = runTest {
        // 明确没有预约 ⇒ success(null)
        assertNull(apiFor("/api/library/my" to myNonePayload).myBooking().getOrThrow())

        val booked = apiFor("/api/library/my" to myBookedPayload).myBooking().getOrThrow()!!
        assertEquals("056", booked.seatId)
        assertEquals("使用中", booked.statusText)
        assertEquals("北楼四层西南侧", booked.area)
        // ⚠️ 上游只给按钮文案、不给地址（ri 也没投影）⇒ 留空；而且本端也不能执行这些动作
        assertTrue(booked.actionUrls.isEmpty())

        // 认不出来 ⇒ 失败，绝不装作「没有预约」
        assertTrue(apiFor("/api/library/my" to myUnrecognizedPayload).myBooking().isFailure)
    }

    @Test
    fun seatLineWithoutTrailingSeatIdLeavesAreaEmpty() = runTest {
        // 上游给的整行如果行尾不是座位号，就不猜区域名（屏上不画那一段）
        val payload = """
            {"code":0,"data":{"state":"booked","verified":false,"statusText":"已预约","seatId":"056",
              "seatLine":"北楼四层西南侧 A 区（东头）","actions":[],"markers":{},"cached":false}}
        """.trimIndent()
        val booked = apiFor("/api/library/my" to payload).myBooking().getOrThrow()!!
        assertEquals("056", booked.seatId)
        assertNull(booked.area)
    }

    @Test
    fun readOnlySideThrowsInsteadOfFaking() = runTest {
        val api = apiFor("/api/library/areas" to areasPayload)
        assertFalse(api.canBook)
        assertFalse(api.hasSeatPlan)

        // 写操作与「本端没有的端点」一律抛：真被调到就是有人画了不该画的按钮
        assertFailsWith<RuntimeException> { api.switchCampus(LibraryCampus.YANTA) }
        assertFailsWith<RuntimeException> { api.bookSeat("056", "north4southwest", autoSwap = true) }
        assertFailsWith<RuntimeException> { api.swapSeat("056", "north4southwest") }
        assertFailsWith<RuntimeException> { api.action("/my/?firstruguan=1&ri=1") }
        assertFailsWith<RuntimeException> { api.seatLayout("north2east") }
        assertFailsWith<RuntimeException> { api.planBase("north2east") }
        assertFailsWith<RuntimeException> { api.planTiles("north2east") }
        assertFailsWith<RuntimeException> { api.seatAvailability(LibrarySeatQr("056", "north4southwest")) }
    }

    @Test
    fun envelopeErrorFailsLoudly() = runTest {
        val api = apiFor("/api/library/areas" to errorPayload)
        val error = assertFailsWith<IllegalStateException> { api.areas("xingqing2floor") }
        assertTrue("图书馆 qspace status=500" in error.message.orEmpty())
    }

}
