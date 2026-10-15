package com.xjtu.toolbox.server

import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.venue.VenueCaptchaFixture
import com.xjtu.toolbox.venue.VenueFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `POST /api/venue/book` / `POST /api/venue/cancel` / `GET /api/venue/captcha` 的契约测试
 * （`docs/api-contract.md` §5.3.2 的写路径）……
 *
 * 与 `ServeLibraryActionTest` 同一条脚手架（真 serve + 假上游代理），差别是这里的写路径要吃**滑块
 * 挑战**：夹具的下单剧本（[VenueFakeUpstream.captchaScript]）打开后才校验轨迹并把关，取消剧本
 * （[VenueFakeUpstream.cancelScript]）单独一个开关（取消不需要滑块）。
 *
 * 这一组钉的是：写端点闸门（401）、参数形状（400 逐项）、下单成功（`data{ok,orderId,price,message}`
 * 与上游真收到的 `param`/`yzm`）、轨迹对不上（上游说「验证码有误」⇒ 409，且 `:data` 那份重试没被
 * 改掉）、取消成功与「上游说不行」（409）。
 */
class ServeVenueActionTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `写端点闸门与参数：未登录 401 · 缺 captchaId 与 sliderTrack 400 · selections 空 400`() = withFakeProxy { _ ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server

        // 未登录 ⇒ 401（信封 + 中文短句指路先登录）：取挑战与两个写端点同一道闸门
        val anonymousBook = http.postJson(BOOK_PATH, bookBody(solvedTrack()))
        assertEquals(401, anonymousBook.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymousBook))
        assertTrue(envelopeMessage(anonymousBook).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(401, http.postJson(CANCEL_PATH, """{"orderId":"x"}""").statusCode())
        assertEquals(401, http.get(CAPTCHA_PATH + "?serviceId=${VenueFakeUpstream.SERVICE_ID}").statusCode())

        val login = http.postJson(SESSION_PATH + "/login", LOGIN_BODY)
        assertEquals(200, login.statusCode())

        // 身体不是端点要的形状 ⇒ 400
        val badJson = http.postJson(BOOK_PATH, "not-json")
        assertEquals(400, badJson.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badJson))

        // 空 body（serviceId 缺省 0）⇒ 400
        val noService = http.postJson(BOOK_PATH, "{}")
        assertEquals(400, noService.statusCode())
        assertTrue(envelopeMessage(noService).orEmpty().contains("serviceId"), "400 要说出缺什么")

        // selections 空 ⇒ 400
        val noSelections = http.postJson(BOOK_PATH, """{"serviceId":${VenueFakeUpstream.SERVICE_ID}}""")
        assertEquals(400, noSelections.statusCode())
        assertTrue(envelopeMessage(noSelections).orEmpty().contains("selections"), "400 要说出缺什么")

        // 缺 captchaId ⇒ 400
        val noCaptchaId = http.postJson(BOOK_PATH, bookBody(sliderTrack = solvedTrack(), withCaptchaId = false))
        assertEquals(400, noCaptchaId.statusCode())
        assertTrue(envelopeMessage(noCaptchaId).orEmpty().contains("captchaId"), "400 要说出缺什么")

        // 缺 sliderTrack ⇒ 400
        val noTrack = http.postJson(BOOK_PATH, bookBody(withCaptchaId = true))
        assertEquals(400, noTrack.statusCode())
        assertTrue(envelopeMessage(noTrack).orEmpty().contains("sliderTrack"), "400 要说出缺什么")

        // 取挑战缺 serviceId ⇒ 400；取消缺 orderId ⇒ 400
        assertEquals(400, http.get(CAPTCHA_PATH).statusCode())
        val noOrderId = http.postJson(CANCEL_PATH, "{}")
        assertEquals(400, noOrderId.statusCode())
        assertTrue(envelopeMessage(noOrderId).orEmpty().contains("orderId"), "400 要说出缺什么")
    }

    @Test
    fun `取挑战：逐字段 + serviceId 400（剧本打开时发程序生成的真图）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server
        fake.venue.captchaScript = true
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get(CAPTCHA_PATH + "?serviceId=${VenueFakeUpstream.SERVICE_ID}")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $CAPTCHA_PATH", IDENTITY)
        val data = envelopeData(response)
        assertEquals(
            setOf("captchaId", "backgroundImage", "sliderImage", "bgWidth", "bgHeight", "sliderWidth", "sliderHeight"),
            data.keys,
            "挑战就是这七格（多一格少一格都说明投影漂了）",
        )
        // 上游那个验证码号原样转出来（客户端要把它连同轨迹带回来）
        assertEquals(VenueFakeUpstream.CAPTCHA_ID, data.optString("captchaId"))
        assertEquals(VenueFakeUpstream.CAPTCHA_BG_WIDTH, data.optInt("bgWidth"))
        assertEquals(VenueFakeUpstream.CAPTCHA_BG_HEIGHT, data.optInt("bgHeight"))
        assertEquals(VenueFakeUpstream.CAPTCHA_SLIDER_WIDTH, data.optInt("sliderWidth"))
        assertEquals(VenueFakeUpstream.CAPTCHA_SLIDER_HEIGHT, data.optInt("sliderHeight"))
        // 剧本打开 ⇒ `/gen` 发的是 VenueCaptchaFixture 的程序生成真图（data URI 原样转发）
        assertTrue(
            data.optString("backgroundImage").orEmpty().startsWith(VenueCaptchaFixture.backgroundDataUri()),
            "背景图应当是夹具那张程序生成的图（原样转发）",
        )
        assertTrue(data.optString("sliderImage").orEmpty().startsWith("data:image/png;base64,"))
        assertEquals(1, fake.venue.captchaCalls.get(), "一枪取一次挑战")
    }

    @Test
    fun `下单：轨迹落在缺口 → 200 data{ok,orderId,price,message}，上游收到 param 与 yzm`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server
        fake.venue.captchaScript = true
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val booked = http.postJson(BOOK_PATH, bookBody(solvedTrack()))
        assertEquals(200, booked.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(booked))
        assertNoIdentity(booked.body(), "POST $BOOK_PATH", IDENTITY)
        val data = envelopeData(booked)
        assertEquals(setOf("ok", "orderId", "price", "message"), data.keys, "结果就是这四格（契约 §5.3.2）")
        assertEquals(true, data.optBoolean("ok"), "成功响应的 data.ok 恒为 true")
        assertEquals(VenueFakeUpstream.BOOKED_ORDER_ID, data.optString("orderId"))
        assertEquals(
            VenueFakeUpstream.BOOKED_ORDER_PRICE.toDouble(),
            data.optString("price")?.toDouble(),
            "price 是上游那个字符串读出来的数",
        )
        assertEquals("预订成功", data.optString("message"), "message 是 :data 的结果文案原样转发")

        // 上游真收到了服务端拼的那个信封：stockdetail 的 key（库存）+ value（场地明细），address 是场馆号
        val param = fake.venue.lastBookingParam.orEmpty()
        assertTrue(param.contains("\"stockdetail\""), "param 里要有 stockdetail，实际：$param")
        assertTrue(param.contains("\"9101\":\"5101\""), "stockdetail 是 库存→场地明细，实际：$param")
        assertTrue(param.contains("\"address\":\"${VenueFakeUpstream.SERVICE_ID}\""), "address 是场馆号，实际：$param")
        // yzm 的拼接形状（轨迹 + 验证码号 + 固定尾巴）由 :data 负责，本层只传递两个入参
        assertTrue(
            fake.venue.lastYzm.orEmpty().contains("synjones${VenueFakeUpstream.CAPTCHA_ID}synjoneshttp://202.117.17.144:8071"),
            "yzm 要带上回传的 captchaId，实际：${fake.venue.lastYzm}",
        )
        assertTrue(fake.venue.lastYzm.orEmpty().startsWith("{\"bgImageWidth\":260"), "轨迹原文在最前面原样带上")
        assertEquals(1, fake.venue.tobookCalls.get(), "拖对了 ⇒ 一次成功，没有重试")
    }

    @Test
    fun `下单：轨迹对不上 → 409（上游那句「验证码有误」，重试照旧三次）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server
        fake.venue.captchaScript = true
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 最终 x 离缺口（EXPECTED_SOLVE_TARGET_X ± 3）很远 ⇒ 上游回 result=100「验证码有误」
        val rejected = http.postJson(BOOK_PATH, bookBody(track(finalX = VenueCaptchaFixture.BG_WIDTH)))
        assertEquals(409, rejected.statusCode())
        assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(rejected))
        assertTrue(envelopeMessage(rejected).orEmpty().contains("验证码有误"), "409 文案是 :data 读出的原文")
        assertEquals(3, fake.venue.tobookCalls.get(), ":data 对「验证码有误」照旧原样重试三次（本层没改那段）")
    }

    @Test
    fun `取消订单：剧本开着 → 200{ok,message}；上游说不行 → 409`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 剧本关着：`delorder` 是 404 ⇒ `:data` 把它读成 success=false ⇒ 409 + 那句中文短句（不是 502）
        val upstreamSaysNo = http.postJson(CANCEL_PATH, """{"orderId":"${VenueFakeUpstream.ORDER_PAID_ID}"}""")
        assertEquals(409, upstreamSaysNo.statusCode())
        assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(upstreamSaysNo))
        assertTrue(envelopeMessage(upstreamSaysNo).orEmpty().contains("取消订单失败"), "文案是 :data 那句原文")
        assertEquals(0, fake.venue.delorderCalls.get(), "剧本关着时那一枪根本没到夹具")

        // 剧本开着：200 + {ok:true, message = 上游那句}
        fake.venue.cancelScript = true
        val cancelled = http.postJson(CANCEL_PATH, """{"orderId":"${VenueFakeUpstream.ORDER_PAID_ID}"}""")
        assertEquals(200, cancelled.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(cancelled))
        assertNoIdentity(cancelled.body(), "POST $CANCEL_PATH", IDENTITY)
        val data = envelopeData(cancelled)
        assertEquals(setOf("ok", "message"), data.keys, "结果就是这两格（与 /api/library/action 同一套）")
        assertEquals(true, data.optBoolean("ok"), "成功响应的 data.ok 恒为 true")
        assertEquals(VenueFakeUpstream.CANCEL_OK_MESSAGE, data.optString("message"))
        // 真提交了表单：orderid 带上了（`:data` 的 cancelOrder 拼的就是 orderid + json=true）
        assertTrue(
            fake.venue.lastCancelForm.orEmpty().contains("orderid=${VenueFakeUpstream.ORDER_PAID_ID}"),
            "delorder 表单要带订单号，实际：${fake.venue.lastCancelForm}",
        )
        assertEquals(1, fake.venue.delorderCalls.get())
    }

    @Test
    fun `支付跳转：未登录 401 · 缺 orderId 400 · 没令牌 401`() = withFakeProxy { _ ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server

        // 令牌闸门照旧（这一条虽然不取数，但它是场馆那一族的口径）：没带令牌 401 + 指路那句中文短句
        val noToken = http.get("$PAY_PATH?orderId=${VenueFakeUpstream.ORDER_PAID_ID}", bearer = null)
        assertEquals(401, noToken.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(noToken))

        // 令牌对、但没会话 ⇒ 401（与会话闸门同一个码，指路先登录）
        val anonymous = http.get("$PAY_PATH?orderId=${VenueFakeUpstream.ORDER_PAID_ID}")
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 缺 orderId（没给 / 空白）⇒ 400，文案要说缺什么
        val missing = http.get(PAY_PATH)
        assertEquals(400, missing.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missing))
        assertTrue(envelopeMessage(missing).orEmpty().contains("orderId"), "400 要说出缺什么")
        assertEquals(400, http.get("$PAY_PATH?orderId=%20").statusCode(), "空白 orderId 同一条判据")
    }

    @Test
    fun `支付跳转：302 到 80 端口那台站上的真支付页（地址由数据层拼，本层不取数）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            venueRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 真支付页地址 = `:data` 的 `VenueApi.paymentUrl`（夹具那个 `paymentPageUrl` 就是同一个拼法）
        val hop = http.get("$PAY_PATH?orderId=${VenueFakeUpstream.ORDER_PAID_ID}")
        assertEquals(302, hop.statusCode(), "这一条是跳转，不是信封（契约 §4 的例外清单第二条）")
        assertEquals(
            VenueFakeUpstream.paymentPageUrl(VenueFakeUpstream.ORDER_PAID_ID),
            hop.headers().firstValue("Location").orElse(""),
            "Location 要逐字等于 80 端口那台站上的那个地址",
        )
        assertTrue(hop.body().isBlank(), "302 的响应体是空的（没有信封）")
        // 地址就落在 80 端口（不是 8080 那套业务站）、路径与 :data 拼的一致
        assertEquals(
            VenueFakeUpstream.PAY_ORIGIN + VenueFakeUpstream.PAYMENT_PAGE_PATH,
            hop.headers().firstValue("Location").orElse("").substringBefore("?"),
        )
        // 本层不取数：这一枪根本不该碰场馆站（取支付页等于替用户打支付站，更别说发起支付）
        assertEquals(0, fake.venue.orderCalls.get(), "支付跳转不打订单接口")
        assertEquals(0, fake.venue.tobookCalls.get(), "支付跳转不下单")

        // 订单号里的 `/` 与 `+` 原样带到 Location（都是 `java.net.URLEncoder` 那套：`/`→`%2F`、`+`→`%2B`）
        val tricky = "A/1+2"
        val encoded = java.net.URLEncoder.encode(tricky, "UTF-8")
        val trickyHop = http.get("$PAY_PATH?orderId=$encoded")
        assertEquals(302, trickyHop.statusCode())
        assertEquals(VenueFakeUpstream.paymentPageUrl(tricky), trickyHop.headers().firstValue("Location").orElse(""))

        // 红线（§3.4）：这一条只回一个 Location，但试体里也不许出现身份
        assertNoIdentity(hop.body(), "GET $PAY_PATH", IDENTITY)
    }

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val BOOK_PATH = "/api/venue/book"
        const val CANCEL_PATH = "/api/venue/cancel"
        const val CAPTCHA_PATH = "/api/venue/captcha"

        const val PAY_PATH = "/api/venue/pay"
        /** 夹具只扮演这一个场馆的一个场地明细（`slotsJson` 的第一行：stockid=9101 / id=5101）。 */
        const val STOCK_ID = 9101L
        const val AREA_DETAIL_ID = 5101L

        /** §3.4 红线的钉子组（编造值，非真人）。 */
        val IDENTITY = listOf("username", "studentId", "学号", "姓名", LibraryFakeUpstream.USERNAME)

        /** 拖对了的那条轨迹（最终 x = 识别器会算出的那个位移）。 */
        fun solvedTrack(): String = track(VenueCaptchaFixture.EXPECTED_SOLVE_TARGET_X)

        /**
         * 一条与 `:data`/各宿主同协议的轨迹原文（260 坐标系 + down/up 两点）。
         * 服务端只把它当字符串转交，所以这里不必过 `SliderResult` 的序列化器 —— 反而是**故意**
         * 按线上原文拼：它同时钉住「轨迹是字符串、不被本层解析」这件事。
         */
        fun track(finalX: Int): String =
            """{"bgImageWidth":260,"bgImageHeight":0,"sliderImageWidth":0,"sliderImageHeight":50,""" +
                """"startSlidingTime":"2026-10-13T08:05:00Z","entSlidingTime":"2026-10-13T08:05:01Z",""" +
                """"trackList":[{"x":0,"y":0,"type":"down","t":900},{"x":$finalX,"y":0,"type":"up","t":1500}]}"""

        /**
         * `POST /api/venue/book` 的身体：`sliderTrack = null` 或 [withCaptchaId] = false 的字段**不发**
         * —— 缺哪一项就是哪一项的 400 用例。字符串值走序列化器转义（轨迹是字符串字段）。
         */
        fun bookBody(sliderTrack: String? = null, withCaptchaId: Boolean = true): String = buildJsonObject {
            put("serviceId", VenueFakeUpstream.SERVICE_ID)
            putJsonArray("selections") {
                addJsonObject {
                    put("stockId", STOCK_ID)
                    put("areaDetailId", AREA_DETAIL_ID)
                }
            }
            if (withCaptchaId) put("captchaId", VenueFakeUpstream.CAPTCHA_ID)
            if (sliderTrack != null) put("sliderTrack", sliderTrack)
        }.toString()
    }
}
