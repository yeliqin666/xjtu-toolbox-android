package com.xjtu.toolbox.server

import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 付款码拿码 / 反馈读 / 匹配交友消费统计 / WebVPN 纯转换的契约测试。 */
class ServePayFeedbackMatchWebvpnTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
        // 反馈凭据是**进程级**的（`:data` 的 `FeedbackApi.config`）：每个用例自己收拾干净，
        // 免得同 JVM 里后面的用例看到上一个用例注入的那一份。
        FeedbackApi.config = null
    }

    // ── 付款码（pay）：拿码 + 可用加餐券 ────────────────────────────────

    @Test
    fun `付款码：未登录 401（且不打上游）+ 拿码逐字段`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            payRoutes(session)
        }
        started = server

        // 令牌对、但没登录 ⇒ 401 + 中文短句指路（不是 502），而且一枪都没打到上游
        for (path in listOf(PAY_CODE_PATH, PAY_VOUCHERS_PATH)) {
            val anonymous = http.get(path)
            assertEquals(401, anonymous.statusCode(), path)
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
            assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」：$path")
        }
        assertEquals(0, fake.campusCard.barCodeCalls.get(), "未登录那一枪不该到取数层")
        assertEquals(0, fake.campusCard.voucherListCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── 拿码：就是夹具里那一串编出来的付款码号，原样端出来 ──
        val code = http.get(PAY_CODE_PATH)
        assertEquals(200, code.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(code))
        assertNoIdentity(code.body(), "GET $PAY_CODE_PATH", IDENTITY)

        val codeData = envelopeData(code)
        assertEquals(setOf("barCode"), codeData.keys)
        assertEquals(CampusCardFakeUpstream.BAR_CODE, codeData.optString("barCode"))
        assertEquals(1, fake.campusCard.barCodeCalls.get(), "拿码真打了上游的付款码接口")
    }

    @Test
    fun `付款码：可用加餐券那五格（serverFlag 不投影）+ 勾选同步那一发必须是 0`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            payRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val vouchers = http.get(PAY_VOUCHERS_PATH)
        assertEquals(200, vouchers.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(vouchers))
        assertNoIdentity(vouchers.body(), "GET $PAY_VOUCHERS_PATH", IDENTITY)

        val data = envelopeData(vouchers)
        assertEquals(setOf("count", "vouchers"), data.keys)
        assertEquals(2, data.optInt("count"))
        val rows = data.arrOf("vouchers")
        assertEquals(2, rows.size)

        val first = rows[0] as JsonObject
        assertEquals(
            setOf("showCardId", "voucherName", "typeName", "amountFen", "endDate"),
            first.keys,
            "一张券的五格（`:core` 的 PaymentVoucher 去掉 serverFlag —— 屏不读它）",
        )
        assertEquals(CampusCardFakeUpstream.VOUCHER_CANTEEN_ID, first.optString("showCardId"))
        assertEquals(CampusCardFakeUpstream.VOUCHER_CANTEEN_NAME, first.optString("voucherName"))
        assertEquals("加餐券", first.optString("typeName"))
        assertEquals(CampusCardFakeUpstream.VOUCHER_CANTEEN_FEN, first.optLong("amountFen"), "金额单位是分")
        assertEquals("2026-12-31", first.optString("endDate"))
        assertEquals(
            CampusCardFakeUpstream.VOUCHER_MILK_FEN,
            (rows[1] as JsonObject).optLong("amountFen"),
            "第二张的金额与第一张不同（分转元那条口径真有人钉着）",
        )
        assertFalse(vouchers.body().contains("serverFlag"), "`serverFlag` 不投影")
        assertEquals(1, fake.campusCard.voucherListCalls.get())

        // ⚠️ 这一条是本族的重点：**写路径一次都没被打**（勾选同步 `updateVoucherStatus` 没有端点）
        assertEquals(0, fake.campusCard.voucherUpdateCalls.get(), "serve 模式下不该有「同步券选择」那一发")
    }

    // ── 反馈（feedback）：回复 + 公开问答（提交是写路径，不端）────────────

    @Test
    fun `反馈：凭据没配全 ⇒ 502 + 指路文案（端点在没有 404、部署没给它凭据）`() {
        FeedbackApi.config = null
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { feedbackRoutes() }
        started = server

        // 这一族不套会话 ⇒ 令牌对就能进；「没配凭据」是部署那一档，按 502 + 说清怎么办报
        val replies = http.get("$FEEDBACK_REPLIES_PATH?$FEEDBACK_PARAM_TICKETS=${FeedbackFakeUpstream.TICKET}")
        assertEquals(502, replies.statusCode())
        assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(replies))
        assertEquals(FEEDBACK_UNCONFIGURED_MESSAGE, envelopeMessage(replies))

        val qa = http.get(FEEDBACK_PUBLIC_QA_PATH)
        assertEquals(502, qa.statusCode())
        assertEquals(FEEDBACK_UNCONFIGURED_MESSAGE, envelopeMessage(qa))

        // 没令牌连门都进不去（闸门那一条，与上面两个码无关）
        val noToken = http.get(FEEDBACK_PUBLIC_QA_PATH, bearer = null)
        assertEquals(401, noToken.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(noToken))
    }

    @Test
    fun `反馈：回复与公开问答都读得到（富文本数组那一档 + 未公开的不出现 + 提交那发没被打）`() =
        withFakeProxy { fake ->
            // 凭据按环境变量注入（serve 那一份，见 `ServeFeedback`）；这里用夹具那一套编出来的值
            ServeFeedback.install(feedbackEnv())
            assertTrue(FeedbackApi.isConfigured, "注入夹具凭据之后这一条路应当可用")

            val token = AccessToken.newToken()
            val (server, http) = startEndpointServer(token) { feedbackRoutes() }
            started = server

            val unknown = "TUNKNOWN99"
            val replies = http.get(
                "$FEEDBACK_REPLIES_PATH?$FEEDBACK_PARAM_TICKETS=${FeedbackFakeUpstream.TICKET},$unknown",
            )
            assertEquals(200, replies.statusCode())
            assertNoIdentity(replies.body(), "GET $FEEDBACK_REPLIES_PATH", IDENTITY)
            val repliesData = envelopeData(replies)
            assertEquals(setOf("tickets", "count", "replies"), repliesData.keys)
            assertEquals(listOf(FeedbackFakeUpstream.TICKET, unknown), repliesData.arrOf("tickets").map { it.optString() })
            assertEquals(1, repliesData.optInt("count"), "只有夹具里那条真工单有回复")
            val reply = repliesData.arrOf("replies")[0] as JsonObject
            assertEquals(setOf("ticket", "text", "time"), reply.keys)
            assertEquals(FeedbackFakeUpstream.TICKET, reply.optString("ticket"))
            assertEquals(
                FeedbackFakeUpstream.REPLY_TEXT,
                reply.optString("text"),
                "多行文本在响应里是富文本片段数组 ⇒ :data 的 plainText 合成的那一串",
            )
            assertEquals("2026-10-01 12:00", reply.optString("time"))
            assertFalse(replies.body().contains("TOTHER999"), "不是我要的那条工单不进响应")

            // ── 公开问答：勾了「公开」的才出现 ──
            val qa = http.get(FEEDBACK_PUBLIC_QA_PATH)
            assertEquals(200, qa.statusCode())
            assertNoIdentity(qa.body(), "GET $FEEDBACK_PUBLIC_QA_PATH", IDENTITY)
            val qaData = envelopeData(qa)
            assertEquals(setOf("count", "items"), qaData.keys)
            assertEquals(1, qaData.optInt("count"))
            val item = qaData.arrOf("items")[0] as JsonObject
            assertEquals(setOf("question", "answer", "category"), item.keys)
            assertEquals(FeedbackFakeUpstream.QA_QUESTION, item.optString("question"))
            assertEquals(FeedbackFakeUpstream.QA_ANSWER, item.optString("answer"))
            assertEquals(FeedbackFakeUpstream.QA_CATEGORY, item.optString("category"))
            assertFalse(qa.body().contains(FeedbackFakeUpstream.QA_PRIVATE_QUESTION), "没勾「公开」的不该出现")

            // 两发都是 search（读），提交（写）一枪都没打
            assertTrue(fake.feedback.searchCalls.get() >= 2, "回复与公开问答各是一发 search")
            assertEquals(0, fake.feedback.submitCalls.get(), "提交是写路径 ⇒ serve 模式下没有端点，一枪都不该发")
        }

    @Test
    fun `反馈：tickets 缺或空白 ⇒ 400（而不是去打上游要一份全表）`() = withFakeProxy { fake ->
        ServeFeedback.install(feedbackEnv())
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { feedbackRoutes() }
        started = server

        for (query in listOf("", "?$FEEDBACK_PARAM_TICKETS=", "?$FEEDBACK_PARAM_TICKETS=%20,%20")) {
            val bad = http.get(FEEDBACK_REPLIES_PATH + query)
            assertEquals(400, bad.statusCode(), query)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
            assertTrue(envelopeMessage(bad).orEmpty().contains(FEEDBACK_PARAM_TICKETS), "文案要说清缺哪个参数")
        }
        assertEquals(0, fake.feedback.searchCalls.get(), "参数不对那一枪不该到上游")
    }

    @Test
    fun `反馈：凭据按环境变量注入（三个必填缺一个就如实「没配」）`() {
        ServeFeedback.install(feedbackEnv())
        val cfg = assertNotNull(FeedbackApi.config, "五个环境变量都在 ⇒ 必须有 config")
        assertEquals(FeedbackFakeUpstream.APP_ID, cfg.appId)
        assertEquals(FeedbackFakeUpstream.APP_SECRET, cfg.appSecret)
        assertEquals(FeedbackFakeUpstream.BASE_TOKEN, cfg.baseToken)
        assertEquals(FeedbackFakeUpstream.REPLY_TABLE, cfg.replyTable)
        assertEquals("serve", cfg.versionLabel, "版本那一列在 serve 上就是这一端的名字（没有包版本号）")
        assertTrue(cfg.deviceLabel.isNotBlank(), "机型列要报这台机器（不假装是手机）：${cfg.deviceLabel}")
        assertTrue(cfg.osLabel.contains("Java"), "系统列带 Java 版本：${cfg.osLabel}")
        assertTrue(FeedbackApi.isConfigured)

        // 三个必填缺一个 ⇒ config 置 null（`isConfigured` 的判据就是那三个非空），不是拿半份顶上
        ServeFeedback.install(feedbackEnv() - ServeFeedback.ENV_BASE_TOKEN)
        assertNull(FeedbackApi.config, "缺 FEEDBACK_BASE_TOKEN ⇒ 如实「没配」")
        assertFalse(FeedbackApi.isConfigured)
    }

    // ── 匹配交友（match）：消费统计 ────────────────────────────────────

    @Test
    fun `匹配交友：消费统计逐字段（食堂名归并、阈值没到就不进 diningHours、商户全称与金额都不端）`() =
        withFakeProxy { fake ->
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) {
                sessionRoutes(session, token)
                matchRoutes(session)
            }
            started = server

            // 未登录 ⇒ 401，且不打上游
            val anonymous = http.get("$MATCH_DINING_PATH?$MATCH_PARAM_FROM=2026-10-01&$MATCH_PARAM_TO=2026-10-14")
            assertEquals(401, anonymous.statusCode())
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
            assertEquals(0, fake.campusCard.turnoverCalls.get(), "未登录那一枪不该到取数层")

            assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

            val dining = http.get("$MATCH_DINING_PATH?$MATCH_PARAM_FROM=2026-10-01&$MATCH_PARAM_TO=2026-10-14")
            assertEquals(200, dining.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(dining))
            assertNoIdentity(dining.body(), "GET $MATCH_DINING_PATH", IDENTITY)

            val data = envelopeData(dining)
            assertEquals(
                setOf("from", "to", "scanned", "truncated", "hourCounts", "diningHours", "canteens"),
                data.keys,
                "七格：窗口两端 + 覆盖笔数 + 有没有截断 + 三个统计结果",
            )
            assertEquals("2026-10-01", data.optString("from"), "窗口两端原样回")
            assertEquals("2026-10-14", data.optString("to"))

            // 夹具只有一页七条 ⇒ 全进统计、没到页上限
            assertEquals(CampusCardFakeUpstream.TOTAL, data.optInt("scanned"))
            assertEquals(false, data.optBoolean("truncated"))
            assertTrue(fake.campusCard.turnoverCalls.get() >= 1, "统计是现取的（打了校园卡流水那一枪）")

            // 饭点：只有食堂/餐厅类**支出**进统计 ⇒ 夹具里那两条（08:12 / 12:30，都是学苑食堂三楼）
            val hours = data.getValue("hourCounts").jsonObject
            assertEquals(setOf("8", "12"), hours.keys, "小时是字符串键、升序")
            assertEquals(1, hours.optInt("8"))
            assertEquals(1, hours.optInt("12"))

            // 食堂：归并到楼号那个名字（夹具里商户是「学苑食堂三楼」，归并后是「学苑食堂」），去过 2 次 ⇒ 留下
            assertEquals(listOf("学苑食堂"), data.arrOf("canteens").map { it.optString() })

            // 阈值（该小时 ≥2 笔）没到 ⇒ 空串（= 这一维没数据，与 `MatchProfile.build` 同一条判据）
            assertEquals("", data.optString("diningHours"), "每条只有一笔 ⇒ 没到「经常」那个阈值")

            // ⚠️ 红线之外的两条「不端」：商户全称与金额一个都不进响应
            assertFalse(dining.body().contains(CampusCardFakeUpstream.MERCHANT_CANTEEN), "商户全称不端")
            assertFalse(dining.body().contains("merchant"), "商户那一格不端")
            assertFalse(dining.body().contains("18.5"), "金额不进统计结果")
            assertFalse(dining.body().contains("balance"), "余额不进统计结果")
        }

    @Test
    fun `匹配交友：参数形状 400、from 与 to 的默认值`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            matchRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val callsBefore = fake.campusCard.turnoverCalls.get()
        for (query in listOf(
            "?$MATCH_PARAM_FROM=2026/10/01",
            "?$MATCH_PARAM_TO=2026-13-01",
            "?$MATCH_PARAM_FROM=2026-10-14&$MATCH_PARAM_TO=2026-10-01",
        )) {
            val bad = http.get(MATCH_DINING_PATH + query)
            assertEquals(400, bad.statusCode(), query)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
        }
        assertEquals(callsBefore, fake.campusCard.turnoverCalls.get(), "参数不对那一枪不该到上游")

        // `to` 省略 = 服务端系统时区的今天；`from` 省略 = `to` 往前 90 天（2026-10-14 − 90 = 2026-07-16）
        val defaults = http.get("$MATCH_DINING_PATH?$MATCH_PARAM_TO=2026-10-14")
        assertEquals(200, defaults.statusCode())
        assertEquals("2026-07-16", envelopeData(defaults).optString("from"))
        assertEquals("2026-10-14", envelopeData(defaults).optString("to"))

        val todayOnly = http.get(MATCH_DINING_PATH)
        assertEquals(200, todayOnly.statusCode())
        assertEquals(
            com.xjtu.toolbox.util.todayInSystemZone().toString(),
            envelopeData(todayOnly).optString("to"),
            "两个日期参数都省略 ⇒ to 是服务端系统时区的今天",
        )
    }

    // ── WebVPN 转换（webvpn）：纯函数 ───────────────────────────────────

    @Test
    fun `WebVPN：正向转换（密文域名那一段是定值）+ 已经是网关地址的原样返回`() {
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { webVpnRoutes() }
        started = server

        val vpn = http.postJson(
            WEBVPN_CONVERT_PATH,
            """{"url":"https://jwxt.xjtu.edu.cn/","direction":"$WEBVPN_TO_VPN"}""",
        )
        assertEquals(200, vpn.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(vpn))
        val data = envelopeData(vpn)
        assertEquals(setOf("direction", "url"), data.keys)
        assertEquals(WEBVPN_TO_VPN, data.optString("direction"), "方向原样回，免得消费方猜这一次的语义")
        assertEquals(
            "https://webvpn.xjtu.edu.cn/https/77726476706e69737468656265737421" +
                "fae05988692862446b468ca88d1b203b/",
            data.optString("url"),
        )

        val again = http.postJson(
            WEBVPN_CONVERT_PATH,
            """{"url":"${data.optString("url")}","direction":"$WEBVPN_TO_VPN"}""",
        )
        assertEquals(200, again.statusCode())
        assertEquals(data.optString("url"), envelopeData(again).optString("url"))
    }

    @Test
    fun `WebVPN：反向转换 + 形状与参数错 400 + 没令牌 401`() {
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { webVpnRoutes() }
        started = server

        val vpnUrl = "https://webvpn.xjtu.edu.cn/https/77726476706e69737468656265737421" +
            "fae05988692862446b468ca88d1b203b/"
        val back = http.postJson(WEBVPN_CONVERT_PATH, """{"url":"$vpnUrl","direction":"$WEBVPN_FROM_VPN"}""")
        assertEquals(200, back.statusCode())
        assertEquals(WEBVPN_FROM_VPN, envelopeData(back).optString("direction"))
        assertEquals("https://jwxt.xjtu.edu.cn", envelopeData(back).optString("url"))

        // 带端口/路径/查询/片段的一趟来回（host 与端口一个不丢）
        val roundTrip = http.postJson(
            WEBVPN_CONVERT_PATH,
            """{"url":"http://rg.lib.xjtu.edu.cn:8086/seat/?a=1#top","direction":"$WEBVPN_TO_VPN"}""",
        )
        assertEquals(200, roundTrip.statusCode())
        val converted = envelopeData(roundTrip).optString("url").orEmpty()
        assertTrue(converted.startsWith("https://webvpn.xjtu.edu.cn/http-8086/"), "端口要编进那一段：$converted")
        val reversed = http.postJson(WEBVPN_CONVERT_PATH, """{"url":"$converted","direction":"$WEBVPN_FROM_VPN"}""")
        assertEquals(200, reversed.statusCode())
        assertEquals(
            "http://rg.lib.xjtu.edu.cn:8086/seat/?a=1#top",
            envelopeData(reversed).optString("url"),
            "来回一趟应当回到原网址（查询与片段都不丢）",
        )

        // 形状 / 参数不对：四个 400 + 各自的中文短句（都不是 502 —— 它不碰任何上游）
        val badCases = listOf(
            "not json at all" to "请求体不是 JSON",
            """{"url":"https://jwxt.xjtu.edu.cn/","direction":"sideways"}""" to "direction 不认识",
            """{"url":"   ","direction":"$WEBVPN_TO_VPN"}""" to "url 空白",
            """{"url":"https://jwxt.xjtu.edu.cn/","direction":"$WEBVPN_FROM_VPN"}""" to "不是网关地址",
        )
        for ((body, why) in badCases) {
            val bad = http.postJson(WEBVPN_CONVERT_PATH, body)
            assertEquals(400, bad.statusCode(), why)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad), why)
            assertTrue(!envelopeMessage(bad).isNullOrBlank(), "$why ⇒ message 要给中文短句")
        }

        // 令牌闸门那一条（这一族不套会话，但令牌还是唯一门牌）
        val noToken = http.postJson(
            WEBVPN_CONVERT_PATH,
            """{"url":"https://jwxt.xjtu.edu.cn/","direction":"$WEBVPN_TO_VPN"}""",
            bearer = null,
        )
        assertEquals(401, noToken.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(noToken))
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val PAY_CODE_PATH = "/api/pay/code"
        const val PAY_VOUCHERS_PATH = "/api/pay/vouchers"
        const val FEEDBACK_REPLIES_PATH = "/api/feedback/replies"
        const val FEEDBACK_PUBLIC_QA_PATH = "/api/feedback/public-qa"
        const val MATCH_DINING_PATH = "/api/match/dining"
        const val WEBVPN_CONVERT_PATH = "/api/webvpn/convert"

        /** 反馈那一族的凭据（`ServeFeedback` 认的那五个环境变量名，值就是夹具里那套编出来的）。 */
        fun feedbackEnv(): Map<String, String> = mapOf(
            ServeFeedback.ENV_APP_ID to FeedbackFakeUpstream.APP_ID,
            ServeFeedback.ENV_APP_SECRET to FeedbackFakeUpstream.APP_SECRET,
            ServeFeedback.ENV_BASE_TOKEN to FeedbackFakeUpstream.BASE_TOKEN,
            ServeFeedback.ENV_TABLE_SUBMIT to FeedbackFakeUpstream.SUBMIT_TABLE,
            ServeFeedback.ENV_TABLE_REPLY to FeedbackFakeUpstream.REPLY_TABLE,
        )

        /** §5.2 的红线钉子组（全是夹具里的编造值，不是真人）：登录学号、校园卡上的姓名与学号、卡账号，以及几个一眼能认出来的身份键名。 */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            CampusCardFakeUpstream.USER_NAME,
            CampusCardFakeUpstream.STUDENT_NO,
            CampusCardFakeUpstream.CARD_ACCOUNT,
        )
    }
}

/** `JsonArray` 里的一项当字符串读（本组用例到处要「把数组拉成 List<String>」）。 */
private fun kotlinx.serialization.json.JsonElement.optString(): String =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
