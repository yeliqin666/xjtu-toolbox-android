package com.xjtu.toolbox.server

import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/** `POST /api/library/action` 的契约测试（`docs/api-contract.md` §5.3.1 的写路径）…… */
class ServeLibraryActionTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `写端点闸门与参数：未登录 401 · 未知动作 400 · 缺参数 400 · campus 不认 400`() = withFakeProxy(casEnabled = true) { _ ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            libraryRoutes(session)
        }
        started = server

        // 未登录 ⇒ 401（信封 + 中文短句，指路先登录）—— 写端点与会话闸门的关系同读端点
        val anonymous = http.postJson(ACTION_PATH, """{"action":"seat","seatId":"A01","area":"north2east"}""")
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")

        val login = http.postJson(SESSION_PATH + "/login", LOGIN_BODY)
        assertEquals(200, login.statusCode())

        // 未知动作 ⇒ 400（文案把合法的列出来）
        val unknown = http.postJson(ACTION_PATH, """{"action":"teleport"}""")
        assertEquals(400, unknown.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(unknown))
        assertTrue(envelopeMessage(unknown).orEmpty().contains("seat"), "400 文案要把合法动作列出来")

        // seat 缺 seatId/area ⇒ 400
        val missing = http.postJson(ACTION_PATH, """{"action":"seat"}""")
        assertEquals(400, missing.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missing))
        assertTrue(envelopeMessage(missing).orEmpty().contains("seatId"), "400 要说出缺什么")

        // 身体不是端点要的形状 ⇒ 400
        val badBody = http.postJson(ACTION_PATH, "not-json")
        assertEquals(400, badBody.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badBody))

        // switch_campus 给了不认的校区码 ⇒ 400
        val badCampus = http.postJson(ACTION_PATH, """{"action":"switch_campus","campus":"mars"}""")
        assertEquals(400, badCampus.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badCampus))
        assertTrue(envelopeMessage(badCampus).orEmpty().contains("east"), "400 要把能改的校区码列出来")

    }

    @Test
    fun `约座：成功 data{ok,message}，座位被占 409 + 中文短句`() = withFakeProxy(casEnabled = true) { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            libraryRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 约座成功：`/seat/` 302 到 `/my/` ⇒ 200 + {ok:true, message}（`:data` 的原文）
        val booked = http.postJson(ACTION_PATH, """{"action":"seat","seatId":"A01","area":"north2east"}""")
        assertEquals(200, booked.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(booked))
        assertNoIdentity(booked.body(), "POST /api/library/action（约座）", IDENTITY)
        assertTrue(envelopeData(booked).optBoolean("ok") == true, "成功响应的 data.ok 恒为 true")
        assertTrue(envelopeData(booked).optString("message").orEmpty().contains("预约成功"))

        // 座位上被占：剧本掰到「已被他人预约」那一侧 ⇒ 409（业务拒绝，不是 502）
        fake.library.seatTaken.set(true)
        val taken = http.postJson(ACTION_PATH, """{"action":"seat","seatId":"A01","area":"north2east"}""")
        assertEquals(409, taken.statusCode())
        assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(taken))
        assertTrue(envelopeMessage(taken).orEmpty().contains("已被他人预约"), "409 文案是 :data 读出的短句")
        fake.library.seatTaken.set(false)
    }

    @Test
    fun `签到、中途离开、中途返回、退座：动作只认当前预约页上的按钮，不在就 409`() = withFakeProxy(casEnabled = true) { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            libraryRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 我的预约：actionUrls 投影出来（写端点落地后屏上的按钮要它们）
        val before = http.get("/api/library/my")
        val actionsBefore = envelopeData(before).getValue("my").jsonObject.getValue("actionUrls").jsonObject
        assertEquals(
            setOf("取消预约", "入馆签到"),
            actionsBefore.keys,
            "没签到时的按钮：取消 + 入馆签到（夹具默认状态）",
        )

        // 签到：按钮在页面上 ⇒ 200 + 剧本的落地文案
        val signin = http.postJson(ACTION_PATH, """{"action":"signin"}""")
        assertEquals(200, signin.statusCode())
        assertEquals("签到成功", envelopeData(signin).optString("message"))

        // 中途离开 → 中途返回，一步步把状态推过去（executeAction 按按钮消失判成功）
        val leave = http.postJson(ACTION_PATH, """{"action":"leave"}""")
        assertEquals(200, leave.statusCode())
        assertEquals("已暂离", envelopeData(leave).optString("message"))
        val back = http.postJson(ACTION_PATH, """{"action":"back"}""")
        assertEquals(200, back.statusCode())
        assertEquals("已返回", envelopeData(back).optString("message"))

        // 回到「已签到未离开」：页面上没有「入馆签到」了 ⇒ 再签到是 409（业务拒绝）
        val signinAgain = http.postJson(ACTION_PATH, """{"action":"signin"}""")
        assertEquals(409, signinAgain.statusCode())
        assertTrue(envelopeMessage(signinAgain).orEmpty().contains("当前预约上没有"), "要说清楚为什么不行")

        // 退座：取消预约 ⇒ 200，接下来「我的预约」是 null（页面明确说没有了）
        val cancel = http.postJson(ACTION_PATH, """{"action":"cancel"}""")
        assertEquals(200, cancel.statusCode())
        assertEquals("已取消", envelopeData(cancel).optString("message"))
        assertTrue(fake.library.cancelled.get(), "应真打过 /my/?cancel=1 那一枪")
        val after = http.get("/api/library/my")
        assertEquals(JsonNull, envelopeData(after).getValue("my"), "退座后 my 是 null（§4：null = 明确没有）")
    }

    @Test
    fun `切校区：POST 把整个表单提交回去，GET campus 跟着变`() = withFakeProxy(casEnabled = true) { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            libraryRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 切到雁塔：200 + 文案
        val switched = http.postJson(ACTION_PATH, """{"action":"switch_campus","campus":"west"}""")
        assertEquals(200, switched.statusCode())
        assertTrue(envelopeData(switched).optString("message").orEmpty().contains("雁塔"))

        // 表单真的提交了（csrf_token 是 switchCampus 的判据；夹具缺它响亮 400 它还能过说明带上了）
        assertEquals("west", fake.library.lastPostedRplace, "rplace 应当真 POST 上去")

        // 账号资料的校区接着就变了（getCurrentCampus 读 /modify 的选中项）
        val campus = http.get("/api/library/campus")
        assertEquals("west", envelopeData(campus).getValue("current").jsonObject.optString("code"))
    }

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val ACTION_PATH = "/api/library/action"

        /** §3.4 红线的钉子组（编造值，非真人）。 */
        val IDENTITY = listOf("username", "studentId", "学号", "姓名", LibraryFakeUpstream.USERNAME)
    }
}