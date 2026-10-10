package com.xjtu.toolbox.emptyroom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `parseLiveSnapshot` / `liveBuildingName` 的**纯解析口径**：平台给的键（`status`/`studentNum`
 * 是字符串、`seatNum` 也是字符串）与楼顺序都是上游的事，这里不碰网络。
 *
 * 它原本在 `:app/src/test`。这一轮把实时状态的取数（`LiveRoomApi`）从 `:app` 搬进 `:data`
 * （桌面端第 10 条真数据路由），`parseLiveSnapshot` 是 `internal` —— 模块级可见性 ⇒ **测试跟着代码走**
 * （与 `SiteSnapshotsTest` 那次同一条）。断言值全部来自下面那段**编造的**响应原文，
 * 与搬动前逐字相同；只把 JUnit4 的断言换成 `kotlin.test`（`:data` 其余测试用的那一套）。
 */
class LiveRoomParseTest {
    // 结构照 2026-09-22 抓包的 classroomStatusList 响应，内容是编的
    private val body = """
        {"code":0,"data":{
          "campusStatusData":{"使用中":1,"上课中":1,"空闲":2},
          "buildingData":["1","18"],
          "classroomStatusCount":{},
          "classroomStatusData":{
            "18":[{"buildName":"18","classroomName":"18-2065","status":"2","studentNum":0,"course":null,"teacherName":null,"seatNum":"32"}],
            "1":[
              {"buildName":"1","classroomName":"1-2050","status":"2","studentNum":0,"course":null,"teacherName":null,"seatNum":"50"},
              {"buildName":"1","classroomName":"1-2053","status":"3","studentNum":37,"course":"某课程","teacherName":"某老师","seatNum":"50"},
              {"buildName":"1","classroomName":"1-2054","status":"1","studentNum":2,"course":null,"teacherName":null,"seatNum":""}
            ]
          }
        }}
    """.trimIndent()

    @Test
    fun parsesRoomsInPlatformBuildingOrder() {
        val snap = parseLiveSnapshot("创新港校区", body, 123L)
        assertEquals(listOf("1号巨构", "18号巨构"), snap.buildings)
        assertEquals(listOf("1-2050", "1-2053", "1-2054", "18-2065"), snap.rooms.map { it.name })
        assertEquals(2, snap.freeCount)
        assertEquals(1, snap.inUseCount)
        assertEquals(1, snap.inClassCount)
        assertEquals(123L, snap.fetchedAt)
    }

    @Test
    fun keepsPeopleCourseAndSeats() {
        val rooms = parseLiveSnapshot("创新港校区", body, 0L).rooms.associateBy { it.name }
        val inClass = rooms.getValue("1-2053")
        assertTrue(inClass.isInClass)
        assertEquals(37, inClass.people)
        assertEquals("某课程", inClass.course)
        assertEquals("某老师", inClass.teacher)
        val inUse = rooms.getValue("1-2054")
        assertTrue(inUse.isInUse)
        assertEquals(2, inUse.people)
        assertEquals(0, inUse.seats) // 空串座位数不当成错误
        assertNull(rooms.getValue("1-2050").course)
    }

    @Test
    fun onlyInnovationHarbourNumbersGetRenamed() {
        assertEquals("5号巨构", liveBuildingName("创新港校区", "5"))
        assertEquals("东1东", liveBuildingName("兴庆校区", "东1东"))
        assertEquals("1", liveBuildingName("兴庆校区", "1"))
    }

    @Test
    fun tokenErrorThrows() {
        assertFailsWith<java.io.IOException> {
            parseLiveSnapshot("兴庆校区", """{"code":401,"message":"token不存在或者过期"}""", 0L)
        }
    }
}
