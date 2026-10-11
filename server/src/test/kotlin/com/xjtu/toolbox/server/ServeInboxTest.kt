package com.xjtu.toolbox.server

import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 消息收纳的契约测试（`GET /api/inbox`，四路聚合的投影）。
 *
 * 轻登录：先 `POST /api/session/login`（一网通办站点随登录链预热），`AppInboxSource.refresh`
 * 会打四个假上游域（消息 / 事务中心 / 预约 / 校车，`:testkit` 的 `YwtbFakeUpstream`）。
 *
 * **红线**：响应里只有收纳条目（本人数据），**不出现学号** —— 账号是会话里的
 * `AccountContext`，响应不回显它。
 *
 * 时间断言：`time`/`schoolFetchedAt` 是 ISO-8601 **带时区**的字符串（契约 §4，不是 epoch）。
 */
class ServeInboxTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `消息收纳：登录后四路聚合逐字段，响应不含学号，时间 ISO 带时区`() = withFakeProxy(casEnabled = true) { _ ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            inboxRoutes(session)
        }
        started = server

        // ── 未登录：也要走到「未登录」的如实失败（401 中文短句），不许 200 空数据 ──
        val anonymous = http.get("/api/inbox")
        assertEquals(401, anonymous.statusCode())
        assertTrue(envelopeMessage(anonymous).orEmpty().any { it.code in 0x4e00..0x9fff }, "401 也要中文短句")
        assertNoIdentity(anonymous.body(), "未登录的 GET /api/inbox", LOGIN_IDENTITY)

        // ── 登录 → 拉一轮 ──
        val login = http.postJson(SESSION_PATH + "/login", LOGIN_BODY)
        assertEquals(200, login.statusCode())
        assertTrue(envelopeData(login).optBoolean("authenticated") == true)

        val response = http.get("/api/inbox?force=1")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/inbox", LOGIN_IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("messages", "todos", "finished", "readAt", "seenTodos", "ignored", "off", "schoolFetchedAt", "bubbled"),
            data.keys, "data 就是 InboxData 的逐字段投影，不多不少")

        // ── 消息：两条（事务中心的推送被去重、图书馆座位的短命消息被过滤）──
        val messages = data.arrOf("messages")
        assertEquals(2, messages.size)
        assertInboxItem(
            messages[0] as JsonObject,
            id = "school:${YwtbFakeUpstream.MESSAGE_SIGNED_ID}",
            category = "school:${YwtbFakeUpstream.MESSAGE_SIGNED_SOURCE}",
            source = YwtbFakeUpstream.MESSAGE_SIGNED_SOURCE,
            title = YwtbFakeUpstream.MESSAGE_SIGNED_TITLE,
        )
        assertTrue((messages[0] as JsonObject).optString("body")!!.startsWith("您当前的电费余额不足"), "正文是去标签后的纯文本")
        assertTrue(messages[0].let { it as JsonObject }.optString("route")!!.startsWith("browser?url=") &&
            messages[0].let { it as JsonObject }.optString("route")!!.contains("notice"), "外链投影成 browser 路由")
        assertInboxItem(
            messages[1] as JsonObject,
            id = "school:${YwtbFakeUpstream.MESSAGE_BARE_ID}",
            category = "school:${YwtbFakeUpstream.MESSAGE_BARE_APP_NAME}",
            source = YwtbFakeUpstream.MESSAGE_BARE_APP_NAME,
            title = YwtbFakeUpstream.MESSAGE_BARE_APP_NAME,
        )

        // ── 待办：公务中心（两条，含已超时那条）＋ 预约（只报数量）──
        val todos = data.getValue("todos").jsonObject
        val schoolTodos = todos.arrOf("todo.school")
        assertEquals(2, schoolTodos.size)
        assertInboxItem(
            schoolTodos[0] as JsonObject,
            id = "todo:${YwtbFakeUpstream.TODO_ID}",
            category = "todo.school",
            source = YwtbFakeUpstream.TODO_SOURCE,
            title = YwtbFakeUpstream.TODO_TITLE,
        )
        assertEquals("成绩单申请", (schoolTodos[0] as JsonObject).optString("body"), "nodeName 当正文；没超时不加后缀")
        val overdue = schoolTodos[1] as JsonObject
        assertEquals(YwtbFakeUpstream.TODO_BARE_TITLE, overdue.optString("title"))
        assertEquals(YwtbFakeUpstream.TODO_BARE_SOURCE, overdue.optString("source"))
        assertEquals("宿舍电费缴纳 · 已超时", overdue.optString("body"), "timeOut=true ⇒ 正文带「已超时」")
        assertTrue(overdue.optString("id")!!.startsWith("todo:"), "没 taskId 的待办用 标题@时间 拼 id")

        val bookings = todos.arrOf("todo.booking")
        assertEquals(1, bookings.size)
        val booking = bookings[0] as JsonObject
        assertEquals("booking:预约中心", booking.optString("id"))
        assertEquals("预约中心 有 ${YwtbFakeUpstream.BOOKING_RESERVATION_TOTAL} 个待使用的预约", booking.optString("title"))
        assertEquals("预约中心", booking.optString("source"))

        // ── 时间字段：ISO-8601 带时区，且是「刚刚」──
        val now = System.currentTimeMillis()
        for (item in messages) {
            assertIsoRecent((item as JsonObject).optString("time")!!, now)
        }
        assertIsoRecent(data.optString("schoolFetchedAt")!!, now)
        assertEquals(null, data.optString("readAt"), "没读过 ⇒ readAt 是空对象不是 null")
        assertEquals(0, data.arrOf("finished").size)
        assertEquals(0, data.arrOf("seenTodos").size)

        // 响应体绝不含学号 / 密码 / 令牌
        val body = response.body()
        assertFalse(body.contains(LibraryFakeUpstream.USERNAME), "响应体不该出现学号")
        assertFalse(body.contains("password"))

        // 第二轮不带 force：TTL 未到，不再打上游，直接给缓存（仍 200）
        val again = http.get("/api/inbox")
        assertEquals(200, again.statusCode())
        assertEquals(2, envelopeData(again).arrOf("messages").size)

        // 闸门
        assertEquals(401, http.get("/api/inbox", bearer = null).statusCode())
        assertEquals(401, http.get("/api/inbox", bearer = "wrong").statusCode())
    }

    private fun assertInboxItem(obj: JsonObject, id: String, category: String, source: String, title: String) {
        assertEquals(id, obj.optString("id"))
        assertEquals(category, obj.optString("category"))
        assertEquals(source, obj.optString("source"))
        assertEquals(title, obj.optString("title"))
        assertNotNull(obj.optString("time"), "time 必须有（契约 §4：ISO-8601 带时区）")
    }

    private fun assertIsoRecent(iso: String, now: Long) {
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?[+-]\d{2}:\d{2}""").matches(iso),
            "时间必须是 ISO-8601 带时区，实际：$iso",
        )
        val epoch = try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: Exception) {
            throw AssertionError("ISO 解析失败：$iso", e)
        }
        assertTrue(epoch in now - 14_400_000..now + 60_000, "应该是刚刚的时间，实际 $epoch vs now $now")
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