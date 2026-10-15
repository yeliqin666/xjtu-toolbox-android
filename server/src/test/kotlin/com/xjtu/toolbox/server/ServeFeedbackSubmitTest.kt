package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /api/feedback/submit` 的契约测试（`docs/api-contract.md` §5.7 的**反馈写路径**）。
 *
 * 与 [ServePayFeedbackMatchWebvpnTest] 同一条脚手架（[withFakeProxy] + [startEndpointServer]）：
 * 真 socket（端口 0）、真令牌闸门、真 `ServeFeedback` 注入。夹具是 `:testkit` 里那份
 * `FeedbackFakeUpstream`（`open.feishu.cn`）—— 这一组新动的是它的一处：`submitRejected` 开关
 * （演「上游到了、但上游说不给写」那一档，与「凭据没配全」不是一回事）。
 *
 * ## 这一组钉的是什么
 *
 * - **闸门**：反馈这一族**不套会话**（没有校园站点，见 `FeedbackRoutes` 的文件头）—— 唯一门牌
 *   就是令牌 ⇒ 不带令牌 401；
 * - **形状**：`{ticket, category, content, contact, anonId}` 五格逐条判 400（不是 JSON / 缺格 /
 *   空白 / 工单号形状），而且**参数不对那一枪不到上游**（`submitCalls == 0`）；
 * - **工单号由调用方生成**：成功响应就一个 `ticket`（原样回、且按 `:data` 那条正则归一成大写），
 *   不由服务端下发；
 * - **真写进去了哪八格**：`版本` / `机型` / `系统` 三格是 serve 自己那份 `Config` 填的
 *   （`ServeFeedback` 如实报这台机器，版本 = `serve`）—— 请求体里**没有** `version` 这一格；
 * - **红线**：响应体过 [assertNoIdentity]（`ticket` / `anonId` 都不是身份字段）。
 */
class ServeFeedbackSubmitTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
        // 反馈凭据是**进程级**的（`:data` 的 `FeedbackApi.config`）：每个用例自己收拾干净，
        // 免得同 JVM 里后面的用例看到上一个用例注入的那一份（与 §5.7 那一组同一条卫生）。
        FeedbackApi.config = null
    }

    @Test
    fun `提交反馈：令牌闸门 401 · 参数形状 400（不是 JSON 缺格 空白 工单号形状）· 一枪都不打上游`() =
        withFakeProxy { fake ->
            // ⚠️ 凭据**配全**：这一组要证明的是「参数不对」那一档的 400，不是「没配」那一档的 502
            ServeFeedback.install(feedbackEnv())
            val token = AccessToken.newToken()
            val (server, http) = startEndpointServer(token) { feedbackRoutes() }
            started = server

            // 令牌闸门：这一族不套会话，但令牌是唯一门牌
            val noToken = http.postJson(SUBMIT_PATH, validBody(), bearer = null)
            assertEquals(401, noToken.statusCode())
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(noToken))

            val badCases = listOf(
                "not json at all" to "请求体不是 JSON",
                """{"category":"bug","content":"内容够长了","anonId":"a"}""" to "缺 ticket",
                """{"ticket":"TEXAMPLE01","content":"内容够长了","anonId":"a"}""" to "缺 category",
                """{"ticket":"TEXAMPLE01","category":"bug","anonId":"a"}""" to "缺 content",
                """{"ticket":"TEXAMPLE01","category":"bug","content":"内容够长了"}""" to "缺 anonId",
                """{"ticket":"TEXAMPLE01","category":"  ","content":"内容够长了","anonId":"a"}""" to "category 空白",
                """{"ticket":"TEXAMPLE01","category":"bug","content":"   ","anonId":"a"}""" to "content 空白",
                """{"ticket":"TEXAMPLE01","category":"bug","content":"内容够长了","anonId":"  "}""" to "anonId 空白",
                """{"ticket":"nope","category":"bug","content":"内容够长了","anonId":"a"}""" to "工单号形状不对",
                """{"ticket":"T123","category":"bug","content":"内容够长了","anonId":"a"}""" to "工单号太短",
            )
            for ((body, why) in badCases) {
                val bad = http.postJson(SUBMIT_PATH, body)
                assertEquals(400, bad.statusCode(), why)
                assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad), why)
                assertTrue(!envelopeMessage(bad).isNullOrBlank(), "$why ⇒ message 要给中文短句")
            }
            // 工单号那一档的 400 要把形状说出来（下一眼就知道该发什么）
            val shape = http.postJson(SUBMIT_PATH, """{"ticket":"nope","category":"bug","content":"内容够长了","anonId":"a"}""")
            assertTrue(envelopeMessage(shape).orEmpty().contains("ticket"), "工单号那条 400 要说清是哪一格")

            assertEquals(0, fake.feedback.submitCalls.get(), "参数不对的那几枪不该到上游")
            assertEquals(0, fake.feedback.tokenCalls.get(), "参数不对的那几枪连令牌都不该去换")
        }

    @Test
    fun `提交反馈：成功一枪 —— 工单号原样回（归一成大写），上游那八格逐格对上`() = withFakeProxy { fake ->
        ServeFeedback.install(feedbackEnv())
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { feedbackRoutes() }
        started = server

        // 大小写：屏生成的就是大写，但端点收下小写也不再分两种语义（判据前先归一）
        val ok = http.postJson(
            SUBMIT_PATH,
            """{"ticket":"${FeedbackFakeUpstream.TICKET.lowercase()}","category":"$CATEGORY",""" +
                """"content":"$CONTENT","contact":"$CONTACT","anonId":"$ANON_ID"}""",
        )
        assertEquals(200, ok.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(ok))
        assertNoIdentity(ok.body(), "POST $SUBMIT_PATH", IDENTITY)

        val data = envelopeData(ok)
        assertEquals(setOf("ticket"), data.keys, "提交的 data 只有工单号那一格（不让服务端下发新号）")
        assertEquals(FeedbackFakeUpstream.TICKET, data.optString("ticket"))

        assertEquals(1, fake.feedback.submitCalls.get(), "成功那一发真打了上游的提交接口")
        val fields = assertNotNull(fake.feedback.lastSubmitFields).parseJsonObject()
            .getValue("fields").jsonObject
        // 五格来自请求，三格（版本 / 机型 / 系统）由 serve 那份 Config 顶上 —— 一个不多一个不少
        assertEquals(
            setOf("工单号", "类型", "内容", "联系方式", "匿名ID", "版本", "机型", "系统"),
            fields.keys,
            "提交进上游的格子数要与 `:data` 的 submit 一致",
        )
        assertEquals(FeedbackFakeUpstream.TICKET, fields.text("工单号"))
        assertEquals(CATEGORY, fields.text("类型"))
        assertEquals(CONTENT, fields.text("内容"))
        assertEquals(CONTACT, fields.text("联系方式"))
        assertEquals(ANON_ID, fields.text("匿名ID"))
        // serve 这一端如实报**这台机器**：版本写死了这个名字，不假装是某个 App 版本
        assertEquals("serve", fields.text("版本"))
        assertTrue(fields.text("机型").isNotBlank(), "机型如实填这台机器，不留空")
        assertTrue(fields.text("系统").contains("Java"), "系统那一格如实带上 Java 版本")
    }

    @Test
    fun `提交反馈：凭据没配全 502 且不打上游 · 上游拒收也是 502 并把上游那句话交给用户`() =
        withFakeProxy { fake ->
            val token = AccessToken.newToken()
            val (server, http) = startEndpointServer(token) { feedbackRoutes() }
            started = server

            // ① 凭据没配全：端点**在**，是这个部署没把凭据给它 ⇒ 502 + 一句「去哪儿配」（不是 404）
            FeedbackApi.config = null
            val unconfigured = http.postJson(SUBMIT_PATH, validBody())
            assertEquals(502, unconfigured.statusCode())
            assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(unconfigured))
            assertTrue(
                envelopeMessage(unconfigured).orEmpty().contains(ServeFeedback.ENV_APP_ID),
                "502 的文案要说清去哪儿配：$SERVE_FEEDBACK_ENV_HINT",
            )
            assertEquals(0, fake.feedback.submitCalls.get(), "凭据没配全那一枪不该到上游")

            // ② 上游到了、上游说不给写 ⇒ 还是 502，但 message 是上游那句话（FriendlyError 原样交回）
            ServeFeedback.install(feedbackEnv())
            fake.feedback.submitRejected = true
            val rejected = http.postJson(SUBMIT_PATH, validBody())
            assertEquals(502, rejected.statusCode())
            assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(rejected))
            assertTrue(
                envelopeMessage(rejected).orEmpty().contains(FeedbackFakeUpstream.SUBMIT_REJECTED_MSG),
                "上游拒收那档要把它那句中文交回用户，而不是吞成一句笼统的失败",
            )
            assertEquals(1, fake.feedback.submitCalls.get(), "这一枪确实打到了上游")

            // ③ 同一份凭据、上游恢复正常 ⇒ 立刻能提交（证明上一条是上游那一档，不是配置漂了）
            fake.feedback.submitRejected = false
            assertEquals(200, http.postJson(SUBMIT_PATH, validBody()).statusCode())
            assertEquals(2, fake.feedback.submitCalls.get())
        }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private fun validBody(): String =
        """{"ticket":"${FeedbackFakeUpstream.TICKET}","category":"$CATEGORY","content":"$CONTENT",""" +
            """"contact":"$CONTACT","anonId":"$ANON_ID"}"""

    private companion object {
        const val SUBMIT_PATH = "/api/feedback/submit"

        /** 三个编造的正文值（与夹具那套一样一眼假，不含任何真人信息）。 */
        const val CATEGORY = "示例类型：功能建议"
        const val CONTENT = "示例反馈：页面上的按钮点不动（这条是测试里的编造内容）"
        const val CONTACT = "示例联系方式：tester@example.invalid"
        const val ANON_ID = "anonfaketest01"

        /** serve 那一侧要看的那个环境变量名（`ServeFeedback` 里那三个必填之一）。 */
        const val SERVE_FEEDBACK_ENV_HINT = "FEEDBACK_APP_ID"

        /** 反馈那一族的凭据（`ServeFeedback` 认的那五个环境变量名，值就是夹具里那套编出来的）。 */
        fun feedbackEnv(): Map<String, String> = mapOf(
            ServeFeedback.ENV_APP_ID to FeedbackFakeUpstream.APP_ID,
            ServeFeedback.ENV_APP_SECRET to FeedbackFakeUpstream.APP_SECRET,
            ServeFeedback.ENV_BASE_TOKEN to FeedbackFakeUpstream.BASE_TOKEN,
            ServeFeedback.ENV_TABLE_SUBMIT to FeedbackFakeUpstream.SUBMIT_TABLE,
            ServeFeedback.ENV_TABLE_REPLY to FeedbackFakeUpstream.REPLY_TABLE,
        )

        /** §5.2 的红线钉子组（全是夹具里的编造值，不是真人）。 */
        val IDENTITY = listOf(
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
        )
    }
}

/** `fields` 里某一格当纯字符串读（八格都是纯字符串：工单号那格是**写**进去的，不是富文本数组）。 */
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
