package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * serve 契约 §5.9 的**客户端那一侧**：`GET /api/jwxt/schedule?merged=1`
 *（[CampusJwxtScheduleApi.mergedSchedule]，2026-10-14 批①收尾）。
 *
 * 为什么值得写：这一档的消费方是 `:web` 的日程屏（`WebScheduleSource.schedule`），而 `:web`
 * **没有测试源集**（D14 才建）⇒ 形状与纪律只能在这里钉（与 [ServeJwxtScheduleExtraEndpointsApiTest]
 * 同一条理由）。MockEngine 喂的是 `:server` 实现出来的形状（`server/…/JwxtRoutes.kt` 的
 * `MergedScheduleData` / `MergedCourseDto` 就是这些字段），钉三件事：
 *
 *  1. **一行不多一行不少地解回 `:core` 的模型**：`courses` 是 [com.xjtu.toolbox.schedule.CourseItem]
 *     （九格；那两格分钟级时间服务端**不投影** ⇒ 本端拿到的是模型的默认值 `-1`，不是第二个口径），
 *     `changes` 与 `/api/jwxt/changes` 同一族十二格（`kind` 是枚举名）；
 *  2. **参数**：`merged=1` 一定发；`?term=` 与其余端点同一个读法（给了就带、空/空白就整个不发）；
 *  3. **形状没对上就抛错**（缺 `courses` / 缺 `changes`）—— 不拿空表冒充「这学期没课」；
 *     非 serve 形态直接抛 [UnsupportedOperationException]（campus-api 那一头不认 `merged`）。
 */
class ServeJwxtScheduleMergedApiTest {

    /** 一条发出去的请求：路径 + 完整 URL（判参数有没有发出去靠它）。 */
    private class Call(val path: String, val url: String)

    private fun engineFor(
        vararg bodies: Pair<String, String>,
        calls: MutableList<Call>? = null,
    ): MockEngine = MockEngine { request ->
        calls?.add(Call(request.url.encodedPath, request.url.toString()))
        val body = bodies.toMap().entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
            ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
    }

    /** `:server` 那一档端出来的形状：挖掉第 5 周的原行 + 只在新教室上第 5 周的那一条。 */
    private val mergedBody = """
        {"code":0,"data":{"term":"2026-2027-1","count":2,"courses":[
          {"courseName":"高等数学（上）","teacher":"示例甲","location":"主楼A-101","weekBits":"1111011111111111",
           "dayOfWeek":1,"startSection":1,"endSection":2,"courseCode":"MATH1001","courseType":""},
          {"courseName":"高等数学（上）","teacher":"示例甲","location":"主楼B-202","weekBits":"0000100000000000",
           "dayOfWeek":1,"startSection":1,"endSection":2,"courseCode":"MATH1001","courseType":""}],
         "changes":[
          {"courseName":"高等数学（上）","courseCode":"MATH1001","kind":"MOVED",
           "fromDay":1,"fromStartSection":1,"fromEndSection":2,
           "toDay":1,"toStartSection":1,"toEndSection":2,
           "toLocation":"主楼B-202","weeks":[5],"toWeeks":[5]}]}}
    """.trimIndent()

    private fun api(calls: MutableList<Call>? = null) = CampusJwxtScheduleApi(
        createToolboxClient(
            engine = engineFor("/api/jwxt/schedule" to mergedBody, calls = calls),
        ),
        "",
        ApiMode.SERVE,
    )

    /** 已合并的那一份解回 `:core` 的两个列表（`SourceSchedule`），字段与枚举零转换。 */
    @Test
    fun mergedScheduleDecodesIntoSourceSchedule() = runTest {
        val calls = mutableListOf<Call>()
        val schedule = api(calls).mergedSchedule("2026-2027-1")

        assertEquals("/api/jwxt/schedule", calls.single().path)
        assertTrue(calls.single().url.contains("merged=1"), "这一档必须带 merged=1：${calls.single().url}")
        assertTrue(calls.single().url.contains("term=2026-2027-1"), "显式学期要原样传下去：${calls.single().url}")

        assertEquals(2, schedule.courses.size, "挖剩的那一条 + 新位置那一条")
        val carved = schedule.courses.first()
        assertEquals("高等数学（上）", carved.courseName)
        assertEquals("主楼A-101", carved.location, "原教室那一条")
        assertEquals("1111011111111111", carved.weekBits, "第 5 周已被挖掉")
        assertEquals(1, carved.dayOfWeek)
        assertEquals(1, carved.startSection)
        assertEquals(2, carved.endSection)
        assertEquals("MATH1001", carved.courseCode, "服务端投影了课程号（非 merged 那一档没有它）")
        // 那两格分钟级时间服务端**不投影**（只有自建日程会填）⇒ 本端拿到的是模型的默认值。
        assertEquals(-1, carved.startMinuteOfDay, "不投影的格落到默认值，不是编出来的 0")
        assertEquals(-1, carved.endMinuteOfDay)

        val moved = schedule.courses.last()
        assertEquals("主楼B-202", moved.location, "调课的新位置")
        assertEquals("0000100000000000", moved.weekBits, "只在第 5 周")

        val change = schedule.changes.single()
        assertEquals(ScheduleChangeEvent.Kind.MOVED, change.kind, "kind 是枚举名，本端零转换")
        assertEquals(listOf(5), change.weeks)
        assertEquals("主楼B-202", change.toLocation)
    }

    /** `?term=` 的读法与其余端点同一条：空/空白 = 整个不发；而 `merged=1` 无论如何都要在。 */
    @Test
    fun mergedIsAlwaysSentAndBlankTermIsOmitted() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls)

        api.mergedSchedule("   ")
        assertFalse(calls[0].url.contains("term="), "空白串 = 省略，不该发出去：${calls[0].url}")
        assertTrue(calls[0].url.contains("merged=1"), "省略学期时 merged 仍在：${calls[0].url}")

        api.mergedSchedule(null)
        assertFalse(calls[1].url.contains("term="), "null 同样整个不发：${calls[1].url}")
        assertTrue(calls[1].url.contains("merged=1"))
    }

    /**
     * 形状没对上 **抛错**，不是回一份空课表 —— 「这学期没课」与「后端给的不是这一档」是两件事
     * （§4 的空值口径）。非 serve 形态同理：campus-api 那一头不认 `merged`（§7 第 2 条）。
     */
    @Test
    fun missingListsAndCampusApiModeThrow() = runTest {
        // 少了 `courses` 那一格（服务端换形状 / 打到了别的端点）
        val missingCourses = CampusJwxtScheduleApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwxt/schedule" to
                        """{"code":0,"data":{"term":"2026-2027-1","count":0,"changes":[]}}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        assertFailsWith<IllegalStateException> { missingCourses.mergedSchedule("2026-2027-1") }

        // 少了 `changes` 那一格（同一个原因的另一半）
        val missingChanges = CampusJwxtScheduleApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwxt/schedule" to
                        """{"code":0,"data":{"term":"2026-2027-1","count":0,"courses":[]}}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        assertFailsWith<IllegalStateException> { missingChanges.mergedSchedule("2026-2027-1") }

        // 非 serve 形态（默认 = CAMPUS_API）：直接抛，不返回空表
        val campusApi = CampusJwxtScheduleApi(
            createToolboxClient(engine = engineFor("/api/jwxt/schedule" to mergedBody)),
            "",
        )
        assertFailsWith<UnsupportedOperationException> { campusApi.mergedSchedule("2026-2027-1") }
    }
}
