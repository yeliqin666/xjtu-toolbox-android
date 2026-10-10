package com.xjtu.toolbox.server

import com.xjtu.toolbox.fitness.FitnessFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * 体测两端点的契约测试（`/api/fitness/years` · `/api/fitness/score`）。
 *
 * 轻登录：serve 进程要先 `POST /api/session/login`（走**真 CAS**，`:testkit` 那半台），
 * 体测站点会随登录链预热好（`ServeSession` 的 `SESSION_SITE_KEYS` 含 fitness）。取数是 `:data`
 * 的 [FitnessApi]（v3 加密协议 + 失败退回 legacy），夹具把这整条链都扮演了。
 *
 * ⚠️ **身份字段**：新契约（§5「要改」）里这是**本人的数据**，`/api/fitness/score` 的响应带
 * 学号/姓名 —— expected 值是夹具的**编造常量**（`FitnessFakeUpstream.STUDENT_NUM` /
 * `STUDENT_NAME`，同一个假账号 "2021000001"/"示例同学"），不是任何真人的数据。
 */
class ServeFitnessTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `体测：未登录 401，登录后 years 与 score 逐字段`() = withFakeProxy(casEnabled = true) { _ ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        // 假上游先装好、再建会话：ServeSession 一构造就建 HttpClients.base（把当时的 selector 抄走）
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            fitnessRoutes(session)
        }
        started = server

        // ── 未登录：401（体测会话未初始化），不让取数拿 502 蒙混 ──
        val anonymous = http.get("/api/fitness/years")
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.LOGIN_FAILED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().any { it.code in 0x4e00..0x9fff }, "401 也要中文短句")
        assertNoIdentity(anonymous.body(), "未登录的 GET /api/fitness/years", LOGIN_IDENTITY)

        // 缺少 year 参数 → 400（信封）
        val missingYear = http.get("/api/fitness/score")
        assertEquals(400, missingYear.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missingYear))

        // ── 真登录（夹具那组假账号；完整 CAS）──
        val login = http.postJson(SESSION_PATH + "/login", LOGIN_BODY)
        assertEquals(200, login.statusCode())
        assertTrue(envelopeData(login).optBoolean("authenticated") == true, "登上了就该报 true")

        // ── years：逐字段（夹具原文：2025 先、2026 checked）──
        val yearsResponse = http.get("/api/fitness/years")
        assertEquals(200, yearsResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(yearsResponse))
        assertNoIdentity(yearsResponse.body(), "GET /api/fitness/years", LOGIN_IDENTITY)
        val years = envelopeData(yearsResponse).arrOf("years")
        assertEquals(2, years.size)
        assertFitnessYear(years[0] as JsonObject, FitnessFakeUpstream.YEAR_OLD, FitnessFakeUpstream.YEAR_OLD_NAME, false)
        assertFitnessYear(years[1] as JsonObject, FitnessFakeUpstream.YEAR_NEW, FitnessFakeUpstream.YEAR_NEW_NAME, true)

        // ── score?year=2026：逐字段（身份字段 = 夹具编造值，见类 KDoc）──
        val scoreResponse = http.get("/api/fitness/score?year=${FitnessFakeUpstream.YEAR_NEW}")
        assertEquals(200, scoreResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(scoreResponse))
        val score = envelopeData(scoreResponse)
        assertEquals(FitnessFakeUpstream.STUDENT_NUM, score.optString("studentNumber"), "本人的学号（夹具假账号）")
        assertEquals(FitnessFakeUpstream.STUDENT_NAME, score.optString("studentName"), "本人的姓名（夹具编造值）")
        assertEquals("78.80", score.optString("totalScore"))
        assertEquals("良好", score.optString("totalGrade"))
        assertEquals("1", score.optString("reportType"))
        assertEquals(FitnessFakeUpstream.REPORT_STATUS, score.optString("reportStatus"))
        assertEquals(FitnessFakeUpstream.SEX, score.optString("sex"))
        assertEquals(FitnessFakeUpstream.GRADE, score.optString("grade"))

        // 七个分项：名字按性别换名（男 → 引体向上 / 1000 米），50 米缺键 → 「未测/缺项」
        val items = score.arrOf("items")
        assertEquals(7, items.size)
        assertFitnessItem(items[0] as JsonObject, "身高 / 体重", "85.00", "良好", "good")
        assertFitnessItem(items[1] as JsonObject, "肺活量", "4123.00", "优秀", "excellent")
        assertFitnessItem(items[2] as JsonObject, "立定跳远", "2.31", "良好", "good")
        assertFitnessItem(items[3] as JsonObject, "坐位体前屈", "12.50", "优秀", "excellent")
        assertFitnessItem(items[4] as JsonObject, "引体向上", "9.00", "及格", "pass")
        assertFitnessItem(items[5] as JsonObject, "50 米", "未测", "缺项", "")
        assertFitnessItem(items[6] as JsonObject, "1000 米", "4.12", "良好", "good")

        // 红线（身份字段之外）：响应里不许出现密码/令牌/手机号键，也不许出现查询账号之外的学号
        val body = scoreResponse.body()
        assertFalse(body.contains("password"), "响应体不该出现密码")
        for (key in listOf("username", "studentId", "手机号", "mobile")) {
            assertFalse(body.contains(key), "响应体不该出现「$key」键")
        }

        // 旧学年的成绩也一样取得到（夹具对任何 year_num 都回同一份）
        val old = http.get("/api/fitness/score?year=${FitnessFakeUpstream.YEAR_OLD}")
        assertEquals(200, old.statusCode())

        // 闸门
        assertEquals(401, http.get("/api/fitness/years", bearer = null).statusCode())
        assertEquals(401, http.get("/api/fitness/years", bearer = "wrong").statusCode())
    }

    private fun assertFitnessYear(obj: JsonObject, yearNum: String, name: String, checked: Boolean) {
        assertEquals(yearNum, obj.optString("yearNum"))
        assertEquals(name, obj.optString("name"))
        assertEquals(checked, obj.optBoolean("checked"))
        assertEquals(setOf("yearNum", "name", "checked"), obj.keys, "学年只投影这三个字段")
    }

    private fun assertFitnessItem(obj: JsonObject, name: String, value: String, grade: String, tone: String) {
        assertEquals(name, obj.optString("name"))
        assertEquals(value, obj.optString("value"))
        assertEquals(grade, obj.optString("grade"))
        assertEquals(tone, obj.optString("tone"))
    }

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** 红线：登录用户本人的身份（学号是夹具那组假账号；本端点 years 不含任何身份字段）。 */
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