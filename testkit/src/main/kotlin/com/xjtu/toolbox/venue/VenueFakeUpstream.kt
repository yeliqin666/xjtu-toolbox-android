package com.xjtu.toolbox.venue

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 假的「体育场馆预订系统」上游 —— 扮演这条路由的**两个域名**（第 11 条真数据路由）。
 *
 * ```
 * ① 开放平台 org.xjtu.edu.cn（https，登录入口那一跳）
 *    GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?…&redirectUri=<场馆回调>
 *                                        → 302 到统一认证（service = 地址里那个 redirectUri）
 * ② 场馆站 202.117.17.144:8080（**明文 http**，业务与登录探针）
 *    GET  /web/cas/oauth2url.html?ticket=ST-n
 *                                        → 种 SESSION cookie，302 到 `/web/index.html`（CAS 回跳那一跳）
 *    GET  /web/index.html                → 200 首页（`VenueLogin.postLogin` 靠页面里的 userno 认身份）
 *    GET  /web/product/productData.html?page=N&rows=8&merccode=100001&remark=defaultProList
 *                                        → 场馆列表一页（每页 8 条）
 *    GET  /web/product/findOkArea.html?s_date=<日期>&serviceid=<场馆>
 *                                        → 那天**可订**的时段与场地
 *    GET  /web/product/findLockArea.html?s_date=<日期>&serviceid=<场馆>
 *                                        → 那天**已被占**的时段与场地（两路合并后屏才分得清「满了」和
 *                                          「没有这个时段」）
 *    GET  /gen                           → 滑块验证码（注意在**根路径**，不在 `/web/` 下）
 *    GET  /web/yyuser/searchorder.html?page=N&rows=20&…
 *                                        → 我的订单一页
 * ```
 *
 * ## 为什么一个夹具扮两个域名
 *
 * 这套夹具的规矩是「**一个 host 只能由一个夹具答**」（`FakeCampusProxy.dispatch` 按 host 分派）。
 * `org.xjtu.edu.cn` 与 `202.117.17.144` 属于**同一条路由的同一条登录链**（OAuth → CAS → 场馆站），
 * 拆成两个类只会让「service 从哪来」变成两处各猜一遍，所以放在一个文件里、两个入口
 *（[handleOauth] / [handle]）—— 一个 host 仍然只有一个夹具在答。CAS 那半台
 *（`login.xjtu.edu.cn`）仍由 [LibraryFakeUpstream] 扮演，与其余站点共用同一台假统一认证。
 *
 * ## 登录链被省略的那一跳
 *
 * 真站点上 OAuth 回调回来的是 `?code=…`，场馆站拿 code 换 SESSION。这里**直接把 CAS 签的
 * `?ticket=` 交给 `/web/cas/oauth2url.html`**（CAS 夹具只会往 service 后面拼 `ticket=`）。
 * 夹具要演的是「CAS 回跳之后客户端拿到的那条链」，中间那一次 code 兑换对被测代码没有可观察差别
 *（`VenueLogin.postLogin` 只判「最终落在 `/web/index.html` 且页面里有 userno」）。这条省略写在这里，
 * 免得被读成「真站点就这两跳」。`202.117.17.144` 这个 IP 同时也是 80 端口那台**支付站**
 *（[PAY_ORIGIN]）—— 分派只看 host，所以两个端口都落进本夹具；支付页是浏览器那边的事，
 * 这里**没有** `/pay/…` 的路由，真被打到就是 404。
 *
 * ## 写路径刻意没有夹具
 *
 * ## 写路径：默认没有夹具（`captchaScript = false`），要下单剧本才打开
 *
 * `POST /web/order/tobook.html`（下单）与 `POST /web/order/delorder.html`（取消）默认**不实现**：
 * 任何一次误提交都会以 404 响亮地失败，而不是被一个假的成功页糊过去 —— 与
 * `JwxtFakeUpstream` 留空评教提交端点同一条口径。
 *
 * Stage B（滑块共享化）给 `tobook` 加了一个**默认关**的剧本（[captchaScript]）：打开后 `/gen` 发
 * 的是 [VenueCaptchaFixture] 的**程序生成**真图（背景带缺口、滑块带透明 alpha，不是任何真人截图），
 * `tobook` 校验轨迹里的最终 x 是否落在缺口（±[VenueCaptchaFixture.BOOKING_X_TOLERANCE]）——
 * 对不上就回真站点那句「验证码有误」（识别器／手拖动对了才会下单成功）。
 * `delorder` 仍不实现（取消不需要滑块，且不是本轮的验收面）。
 *
 * ## 数据端点不验会话 cookie
 *
 * 夹具要钉的是**解析与请求形状**，不是会话判定：`/web/index.html` 与 `/web/cas/oauth2url.html`
 * 那两跳是真登录链必须走过的，其余端点直接给数据（真站点会先判 SESSION，那时走的是
 * `SiteSession.executeWithReAuth` 那条路，已由图书馆/教务/校园卡那几条钉子守着了）。
 *
 * ## 日期
 *
 * 真站点的时段是**按天**查的。夹具不写死某一天（那样桌面端那张图只在当天成立）：`s_date`
 * 只校验「带了、且是 YYYY-MM-DD」，内容照给。场馆这份样本里唯一带日期的字段就是
 * `AreaSlot.date`，它由调用方传进来 —— 断言里用的是「请求里那个日期」。
 */
class VenueFakeUpstream {

    companion object {
        // ── 登录链的两端共用这几个坐标（`OAUTH_URL` 与场馆站的回调都要它们，所以先摆在这里）──

        /** 业务基址（`VenueLogin.BASE_URL` 就是它）。 */
        const val HOST = "202.117.17.144"
        const val PORT = 8080
        const val ORIGIN = "http://$HOST:$PORT"

        /** 80 端口那台支付站（`VenueLogin.PAY_BASE_URL`）—— 分派只看 host，所以它也在本夹具上。 */
        const val PAY_ORIGIN = "http://$HOST"

        /** 业务接口前缀（`VenueApi` 的 `BASE`）。 */
        const val WEB = "$ORIGIN/web"

        /** CAS 的 service 与 OAuth 的 redirectUri 都是它（登录链的最后一跳）。 */
        const val MINI_LOGIN_PATH = "/web/cas/oauth2url.html"
        const val OAUTH_CALLBACK_URL = "$ORIGIN$MINI_LOGIN_PATH"

        // ── ① 开放平台（https）──

        /** OAuth2 入口那一跳的 host（`VenueLogin.VENUE_OAUTH_URL` 在它上面）。 */
        const val OAUTH_HOST = "org.xjtu.edu.cn"

        /** 与 `VenueLogin.VENUE_OAUTH_URL` **逐字相同**（有断言钉着）。 */
        const val OAUTH_URL =
            "https://$OAUTH_HOST/openplatform/oauth/authorize?" +
                "responseType=code&scope=user_info&appId=1659&state=1&" +
                "redirectUri=$OAUTH_CALLBACK_URL"

        // ── ② 场馆站（8080 与 80 都落在这里）──

        /** 登录探针：`VenueLogin.postLogin` 在这一页里找 userno。 */
        const val INDEX_URL = "$WEB/index.html"

        /** 场馆列表（`VenueApi.fetchVenueList`）。 */
        const val PRODUCT_LIST_URL = "$WEB/product/productData.html"

        /** 可订 / 已占用（`VenueApi.fetchSlots`）。 */
        const val OK_AREA_URL = "$WEB/product/findOkArea.html"
        const val LOCK_AREA_URL = "$WEB/product/findLockArea.html"

        /** 滑块验证码：**站点根路径**，不在 `/web/` 下。 */
        const val CAPTCHA_URL = "$ORIGIN/gen"
        const val CAPTCHA_PATH = "/gen"

        /** 我的订单（`VenueApi.fetchOrders`）。 */
        const val ORDER_LIST_URL = "$WEB/yyuser/searchorder.html"

        /** 回跳时种下的本站会话 cookie。 */
        const val SESSION_COOKIE_VALUE = "SESSION-fake-venue-1"

        /** 夹具只扮演这一个场馆的时段（其余 serviceid 一律 400，免得「换个场馆也能查到」静默成立）。 */
        const val SERVICE_ID = 101

        /** 这个场馆**没有**时段（`object` 是空数组）—— 钉住「空表」那条分支。 */
        const val EMPTY_SERVICE_ID = 102

        // ── 场馆样本 ──
        //
        // 名字、校区都是编的（不许出现真人/真实院系）。两页：第一页 8 条（正好一页 ⇒ 客户端必须再要
        // 第二页），第二页 2 条（不足一页 ⇒ 停）。第一页里刻意摆了几种「不完整」：
        // id<=0 的行、缺 name 的行、数字写成字符串、负数的 advanceday/advancenum。

        const val VENUE_A_ID = 101
        const val VENUE_A_NAME = "示例场馆甲"
        const val VENUE_A_ADDRESS = "兴庆校区东区"
        const val VENUE_A_ICON = "icon-badminton"
        const val VENUE_A_ADVANCE_DAY = 3
        const val VENUE_A_ADVANCE_NUM = 4

        const val VENUE_B_ID = 102
        const val VENUE_B_NAME = "示例场馆乙"
        const val VENUE_B_ADDRESS = "兴庆校区西区"

        /** `advanceday` 缺 / 为 0 / 为负时都该落回 7，`advancenum` 同理落回 8。 */
        const val ADVANCE_DAY_DEFAULT = 7
        const val ADVANCE_NUM_DEFAULT = 8

        /** 名字从字符串读出来的那一行（`advanceday` 是 `"5"`）。 */
        const val VENUE_F_ID = 106
        const val VENUE_F_NAME = "示例场馆己"
        const val VENUE_F_ADVANCE_DAY = 5

        /** 第二页那两行：一条 ASCII（不该被 GBK 修补动到）、一条真中文（要由 [VenueApi.fixGbk] 修回来）。 */
        const val VENUE_ASCII_ID = 109
        const val VENUE_ASCII_NAME = "Venue-109"
        const val VENUE_GBK_ID = 110
        const val VENUE_GBK_NAME = "示例场馆癸"
        const val VENUE_GBK_ADDRESS = "兴义校区"

        // ── 时段样本（serviceid = [SERVICE_ID]）──

        const val SLOT_EARLY = "08:00-09:00"
        const val SLOT_LATE = "10:00-11:00"
        const val SLOT_OCCUPIED = "12:00-13:00"

        /** `sname` 为空时该退化成「预订」（`VenueApi.fetchSlots` 里那一条）。 */
        const val AREA_FALLBACK_NAME = "预订"

        /** `status == 1` 但 all_count == using_num 时，`surplus` 会被兜到 1（不是 0）。 */
        const val AREA_FULL_SURPLUS = 1

        const val PRICE_EARLY = 20.0

        /** 价格字段里带 `¥` 与逗号，照样要读出 `30.0`（`readDouble` 的那两条 replace）。 */
        const val PRICE_YUAN = 30.0

        // ── 订单样本 ──

        const val ORDER_PAID_ID = "VT20261012001"
        const val ORDER_PENDING_ID = "VT20261012002"
        const val ORDER_TOTAL = 2

        /** 验证码样本：id + 六个数（其中宽高刻意写成字符串，`intValue` 要照样读得出来）。 */
        const val CAPTCHA_ID = "cap-1"
        const val CAPTCHA_BG_WIDTH = 260
        const val CAPTCHA_BG_HEIGHT = 160
        const val CAPTCHA_SLIDER_WIDTH = 50
        const val CAPTCHA_SLIDER_HEIGHT = 50

        /** 下单剧本（[captchaScript] = true）里 `tobook` 回的成功订单号。 */
        const val BOOKED_ORDER_ID = "VT20261013001"
        const val BOOKED_ORDER_PRICE = "20"
    }

    // ── 打进来的请求（断言用）──────────────────────────────────────

    /** 开放平台入口被打开了几次。 */
    val oauthCalls = AtomicInteger(0)

    /** 带票落在 `/web/cas/oauth2url.html` 几次（CAS 之后的回跳）。 */
    val ticketLandings = AtomicInteger(0)

    /** 场馆列表被要了几页。 */
    val listCalls = AtomicInteger(0)

    /** 可订 / 已占用各被要了几次。 */
    val okCalls = AtomicInteger(0)
    val lockCalls = AtomicInteger(0)

    /** 验证码被要了几次。 */
    val captchaCalls = AtomicInteger(0)

    /** 订单列表被打了几次。 */
    val orderCalls = AtomicInteger(0)

    /** 最近一次列表 / 时段 / 订单的 query（形状漂了一眼看得出来）。 */
    @Volatile
    var lastListQuery: String? = null
        private set

    @Volatile
    var lastOkQuery: String? = null
        private set

    @Volatile
    var lastOrderQuery: String? = null
        private set

    /** 最近一次业务请求带的 Referer（`VenueApi` 每一枪都会带）。 */
    @Volatile
    var lastReferer: String? = null
        private set

    /** 最近一次业务请求带的 `X-Requested-With`。 */
    @Volatile
    var lastRequestedWith: String? = null
        private set

    /** 置真之后 `findLockArea` 回 500 —— 钉住「已占用那一路挂了，可订那一半照样给出来」。 */
    @Volatile
    var lockFails: Boolean = false

    /**
     * **默认关**的下单剧本开关（见类 KDoc 的「写路径」一节）：打开后 `/gen` 发
     * [VenueCaptchaFixture] 的真图、`tobook` 校验轨迹并把关（拖对了才成功）。
     * 默认关 ⇒ 现有一切读路径断言与「写路径 404」的钉住都不受影响。
     */
    @Volatile
    var captchaScript: Boolean = false

    /** 下单剧本里 `tobook` 被打了几次。 */
    val tobookCalls = AtomicInteger(0)

    /** 最近一次 `tobook` 收到的 `yzm` 原文（轨迹 + 验证码 id + 固定尾巴）。 */
    @Volatile
    var lastYzm: String? = null
        private set

    /** 最近一次 `tobook` 收到的 `param` 原文（服务端参数信封）。 */
    @Volatile
    var lastBookingParam: String? = null
        private set

    // ── ① 开放平台 ────────────────────────────────────────────────

    /**
     * `org.xjtu.edu.cn` 那一半：只认那一条 authorize 路径（`GET`），把 `redirectUri` 原样当 CAS 的
     * `service` 交出去 —— 与真站点「OAuth 授权页带你去统一认证」同一个形状（少了中间那一次 code 兑换，
     * 见类 KDoc）。
     */
    fun handleOauth(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        if (path != "/openplatform/oauth/authorize" || exchange.requestMethod != "GET") {
            badRequest(exchange, "开放平台只认 GET /openplatform/oauth/authorize，收到：${exchange.requestMethod} $path?$query")
            return
        }
        if (!query.contains("appId=1659") || !query.contains("responseType=code")) {
            badRequest(exchange, "OAuth 入口少了 appId/responseType，收到：$query")
            return
        }
        val callback = param(query, "redirectUri")
        if (callback != OAUTH_CALLBACK_URL) {
            badRequest(exchange, "redirectUri 不是 $OAUTH_CALLBACK_URL，收到：$callback")
            return
        }
        oauthCalls.incrementAndGet()
        redirect(exchange, "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=${encode(callback)}")
    }

    // ── ② 场馆站 ──────────────────────────────────────────────────

    /**
     * `202.117.17.144`（8080 与 80 都落在这里）那一半。认不出的路径 → 404（body 里带着完整 URL）；
     * 认识的路径但形体不对（缺 query、缺 ajax 头）→ 400（body 里带着实际收到的样子）。
     */
    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == MINI_LOGIN_PATH && exchange.requestMethod == "GET" -> {
                if ("ticket=" !in query) {
                    badRequest(exchange, "oauth2url 没带 CAS 签的 ticket，收到：$query")
                    return
                }
                ticketLandings.incrementAndGet()
                // CAS 回跳：场馆站把票换成自己的会话（真站点是拿 code 换，见类 KDoc）
                exchange.responseHeaders.add("Set-Cookie", "SESSION=$SESSION_COOKIE_VALUE; Path=/")
                redirect(exchange, INDEX_URL)
            }

            // 登录探针。`VenueLogin.postLogin` 在这一页里找 userno，所以它必须带值。
            path == "/web/index.html" && exchange.requestMethod == "GET" -> respondHtml(exchange, indexPage)

            path == "/web/product/productData.html" && exchange.requestMethod == "GET" -> {
                requireAjax(exchange, query) ?: return
                lastListQuery = query
                val page = param(query, "page")?.toIntOrNull()
                if (page == null || page < 1) {
                    badRequest(exchange, "列表缺 page，收到：$query")
                    return
                }
                listCalls.incrementAndGet()
                when (page) {
                    1 -> respondJson(exchange, page1Json)
                    // 第二页声明 latin-1、实际发 GBK —— 真站点的老毛病（见 `VenueApi.fixGbk` 的 KDoc）
                    else -> respond(exchange, 200, "application/json;charset=ISO-8859-1", page2Json.toByteArray(charset("GBK")))
                }
            }

            path == "/web/product/findOkArea.html" && exchange.requestMethod == "GET" ->
                handleSlots(exchange, query, path = path, ok = true)

            path == "/web/product/findLockArea.html" && exchange.requestMethod == "GET" ->
                handleSlots(exchange, query, path = path, ok = false)

            path == CAPTCHA_PATH && exchange.requestMethod == "GET" -> {
                requireAjax(exchange, query) ?: return
                captchaCalls.incrementAndGet()
                // 剧本打开时发**真图**（程序生成），否则发原来的占位 base64 —— 现有关断不受影响
                respondJson(exchange, if (captchaScript) realCaptchaJson else captchaJson)
            }

            path == "/web/yyuser/searchorder.html" && exchange.requestMethod == "GET" -> {
                requireAjax(exchange, query) ?: return
                lastOrderQuery = query
                val page = param(query, "page")?.toIntOrNull()
                if (page == null || page < 1) {
                    badRequest(exchange, "订单列表缺 page，收到：$query")
                    return
                }
                orderCalls.incrementAndGet()
                respondJson(exchange, if (page == 1) ordersJson else "[]")
            }
            // 写路径：`captchaScript = true` 时 tobook 有剧本（见类 KDoc），否则照旧 404 响亮失败。
            path == "/web/order/tobook.html" && exchange.requestMethod == "POST" && captchaScript ->
                handleTobook(exchange)
            path == "/web/order/tobook.html" || path == "/web/order/delorder.html" ->
                notFound(exchange, "写路径没有夹具（captchaScript=false，见类 KDoc）")

            else -> notFound(exchange, ORIGIN)
        }
    }

    /** 两条时段端点共用：`s_date` 与 `serviceid` 都要有，占位那一路可以按 [lockFails] 装死。 */
    private fun handleSlots(exchange: HttpExchange, query: String, path: String, ok: Boolean) {
        requireAjax(exchange, query) ?: return
        if (ok) lastOkQuery = query
        val date = param(query, "s_date")
        val serviceId = param(query, "serviceid")?.toIntOrNull()
        if (date == null || !DATE_REGEX.matches(date) || serviceId == null) {
            badRequest(exchange, "时段查询缺 s_date/serviceid 或日期形状不对，收到：$query")
            return
        }
        if (!ok && lockFails) {
            respond(exchange, 500, "text/plain; charset=utf-8", "occupied side is down".toByteArray())
            return
        }
        when (serviceId) {
            SERVICE_ID -> {
                if (ok) okCalls.incrementAndGet() else lockCalls.incrementAndGet()
                respondJson(exchange, slotsJson(ok))
            }
            EMPTY_SERVICE_ID -> {
                if (ok) okCalls.incrementAndGet() else lockCalls.incrementAndGet()
                respondJson(exchange, """{"object":[]}""")
            }
            else -> badRequest(exchange, "夹具只扮演 serviceid=$SERVICE_ID / $EMPTY_SERVICE_ID，收到：$path?$query")
        }
    }

    /**
     * 下单剧本（[captchaScript] = true 才走到）：校验 `param`/`yzm` 的形状，再按轨迹的**最终 x**
     * 把关 —— 落在缺口（±[VenueCaptchaFixture.BOOKING_X_TOLERANCE]）→ 成功订单，否则回真站点那句
     * 「验证码有误」（身份与真站点的拒绝对齐：拖不对就是 100）。识别器/手拖动对的 x 来自
     * [VenueCaptchaFixture.EXPECTED_SOLVE_TARGET_X]。
     */
    private fun handleTobook(exchange: HttpExchange) {
        tobookCalls.incrementAndGet()
        val body = runCatching { exchange.requestBody.readBytes().decodeToString() }.getOrDefault("")
        val params = body.split('&').mapNotNull { segment ->
            val kv = segment.split('=', limit = 2)
            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val param = params["param"] ?: ""
        val yzm = params["yzm"] ?: ""
        if (params["json"] != "true" || param.isBlank() || yzm.isBlank()) {
            respondJson(exchange, """{"result":"500","message":"缺 param/yzm/json"}""")
            return
        }
        if ("\"stockdetail\"" !in param || "\"address\"" !in param) {
            respondJson(exchange, """{"result":"500","message":"param 形状不对"}""")
            return
        }
        // yzm = {轨迹JSON}synjones{验证码ID}synjoneshttp://202.117.17.144:8071（固定拼接格式）
        val segments = yzm.split("synjones")
        if (segments.size != 3 || segments[1] != CAPTCHA_ID || segments[2] != "http://202.117.17.144:8071") {
            respondJson(exchange, """{"result":"500","message":"yzm 形状不对"}""")
            return
        }
        val trackJson = segments[0]
        lastYzm = yzm
        lastBookingParam = param
        if ("\"bgImageWidth\":260" !in trackJson || extractFinalX(trackJson) == null) {
            respondJson(exchange, """{"result":"100","message":"验证码轨迹有误，请重试"}""")
            return
        }
        val target = VenueCaptchaFixture.EXPECTED_SOLVE_TARGET_X
        val finalX = extractFinalX(trackJson)!!
        if (abs(finalX - target) <= VenueCaptchaFixture.BOOKING_X_TOLERANCE) {
            respondJson(
                exchange,
                """{"result":"2","message":"预订成功","object":{"orderid":"$BOOKED_ORDER_ID","price":"$BOOKED_ORDER_PRICE"}}""",
            )
        } else {
            respondJson(exchange, """{"result":"100","message":"验证码有误，请重试"}""")
        }
    }

    /** 从轨迹 JSON 里取**最后**一个 `"x":N` —— 服务端协议里最后一个点就是 up 点的最终位置。 */
    private fun extractFinalX(trackJson: String): Int? =
        Regex("\"x\":(-?\\d+)").findAll(trackJson).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull()

    // ── 页面与数据样本 ────────────────────────────────────────────

    /**
     * 首页：真站点在这张页面里带 userno（登录成功的唯一判据，见 [VenueLogin] 的 `hasUserNo`）。
     * 这里放的是假统一认证那个账号，不是任何人的学号。
     */
    val indexPage = """
        <html><head><title>场馆预订</title></head><body>
        <input type="hidden" id="userno" value="${LibraryFakeUpstream.USERNAME}">
        </body></html>
    """.trimIndent()

    /**
     * 第一页：正好 8 条（⇒ 客户端必须再要一页）。
     *
     * 摆进去的几种「不完整」：`id` 为 0 的一行（该被跳过）、缺 `name` 的一行（读成空串）、
     * `advanceday` 写成字符串的一行、`advanceday/advancenum` 为负的一行（落回 7 / 8）、
     * 只有图标没有地址的一行。
     */
    val page1Json = """[
      {"id":$VENUE_A_ID,"name":"$VENUE_A_NAME","address":"$VENUE_A_ADDRESS","icon":"$VENUE_A_ICON","advanceday":$VENUE_A_ADVANCE_DAY,"advancenum":$VENUE_A_ADVANCE_NUM},
      {"id":$VENUE_B_ID,"name":"$VENUE_B_NAME","address":"$VENUE_B_ADDRESS","icon":"icon-tennis","advanceday":7,"advancenum":8},
      {"id":103,"name":"示例场馆丙","icon":"icon-swim"},
      {"id":0,"name":"幽灵场馆","address":"不存在的地方"},
      {"id":105},
      {"id":$VENUE_F_ID,"name":"$VENUE_F_NAME","icon":"icon-gym","advanceday":"$VENUE_F_ADVANCE_DAY"},
      {"id":107,"name":"示例场馆庚","address":null,"icon":null,"advanceday":-1,"advancenum":-2},
      {"id":108,"name":"示例场馆辛","advanceday":14,"advancenum":2}
    ]""".trimIndent()

    /** 第二页：2 条（不足一页 ⇒ 客户端停）。这一页按 GBK 发（见 [handle]）。 */
    val page2Json = """[
      {"id":$VENUE_ASCII_ID,"name":"$VENUE_ASCII_NAME","address":"Xingyi Campus"},
      {"id":$VENUE_GBK_ID,"name":"$VENUE_GBK_NAME","address":"$VENUE_GBK_ADDRESS"}
    ]""".trimIndent()

    /** 验证码：`captcha` 那六个数里有四个刻意写成字符串（`intValue` 要照样读得出来）。 */
    val captchaJson = """
        {"id":"$CAPTCHA_ID","captcha":{
          "backgroundImage":"data:image/jpeg;base64,ZmFrZS1iZw==",
          "sliderImage":"data:image/png;base64,ZmFrZS1zbGlkZXI=",
          "backgroundImageWidth":"$CAPTCHA_BG_WIDTH","backgroundImageHeight":$CAPTCHA_BG_HEIGHT,
          "sliderImageWidth":"$CAPTCHA_SLIDER_WIDTH","sliderImageHeight":$CAPTCHA_SLIDER_HEIGHT}}
    """.trimIndent()

    /**
     * 剧本（[captchaScript] = true）里的验证码：id 不变、六个数不变，图片换成
     * [VenueCaptchaFixture] 的**程序生成真图**（背景带缺口、滑块带透明 alpha）。
     */
    val realCaptchaJson = """
        {"id":"$CAPTCHA_ID","captcha":{
          "backgroundImage":"${VenueCaptchaFixture.backgroundDataUri()}",
          "sliderImage":"${VenueCaptchaFixture.sliderDataUri()}",
          "backgroundImageWidth":"$CAPTCHA_BG_WIDTH","backgroundImageHeight":$CAPTCHA_BG_HEIGHT,
          "sliderImageWidth":"$CAPTCHA_SLIDER_WIDTH","sliderImageHeight":$CAPTCHA_SLIDER_HEIGHT}}
    """.trimIndent()
    /**
     * 我的订单：一页两条（一条已预订成功、一条预订中，明细逐条对着 `OrderDetail` 的字段摆），
     * 另外两个坑：数组里夹了一个**不是对象**的元素（`mapNotNull` 该丢掉）、一条 `orderid` 为空的行
     *（`filter { it.orderId.isNotBlank() }` 该丢掉）。`total` 一并给 2 ⇒ `hasMore` 为 false。
     */
    val ordersJson = """
        {"total":$ORDER_TOTAL,"rows":[
          {"orderid":"$ORDER_PAID_ID","status":1,"createdate":"2026-10-12 08:05:00","price":"20.00",
           "orderdetail":[
             {"stock":{"s_date":"2026-10-12","time_no":"$SLOT_EARLY"},"stockdetail":{"sname":"场地1"},
              "service":{"name":"$VENUE_A_NAME"},"price":"20","serviceid":"$SERVICE_ID"}
           ]},
          12345,
          {"orderid":"$ORDER_PENDING_ID","status":"0","createdate":"2026-10-13 09:00:00","price":"30.00",
           "orderdetail":[
             {"stock":{"s_date":"2026-10-13","time_no":"$SLOT_LATE"},"stockdetail":{"sname":"场地2"},
              "service":{"name":"$VENUE_A_NAME"},"price":"30","serviceid":"$SERVICE_ID"},
             {"stockdetail":{"sname":"场地3"},"price":0}
           ]},
          {"orderid":"","status":2,"price":"0"}
        ]}
    """.trimIndent()

    /**
     * 某天 [SERVICE_ID] 的时段：可订那一路给三档价格与容量的坑，占用那一路给两条 `status != 1`。
     *
     * 摆在明面上的几条口径：
     *  - `status == 1` 才是可订；`all_count == using_num` 时 `surplus` 兜到 **1**（不是 0）；
     *  - `sname` 为空 ⇒ 名字退化成「预订」；`stock` 整个缺了 ⇒ 时间与价格读成空/0；
     *  - 价格里的 `¥` 与逗号会被 strip 掉；
     *  - 占用那一路的 `status` 不是 1 ⇒ `surplus` 恒为 0（屏据此把这一格画成已满/已占）。
     */
    fun slotsJson(ok: Boolean): String = if (ok) """
        {"object":[
          {"id":5101,"sname":"场地1","stockid":9101,"status":1,
           "stock":{"time_no":"$SLOT_EARLY","price":"20.0","all_count":10,"using_num":3}},
          {"id":5102,"sname":"场地2","stockid":9101,"status":1,
           "stock":{"time_no":"$SLOT_EARLY","price":"20.0","all_count":10,"using_num":10}},
          {"id":5105,"sname":"场地3","stockid":"9101","status":"1",
           "stock":{"time_no":"$SLOT_EARLY","price":"18.5","all_count":"6","using_num":"1"}},
          {"id":5103,"sname":"场地1","stockid":9102,"status":1,
           "stock":{"time_no":"$SLOT_LATE","price":"¥30","all_count":8,"using_num":2}},
          {"id":5104,"sname":"","stockid":9102,"status":1,
           "stock":{"time_no":"$SLOT_LATE","price":"0","all_count":8,"using_num":8}}
        ]}
    """.trimIndent() else """
        {"object":[
          {"id":5201,"sname":"场地3","stockid":9103,"status":0,
           "stock":{"time_no":"$SLOT_OCCUPIED","price":"25","all_count":6,"using_num":6}},
          {"id":5202,"sname":"场地1","stockid":9103,"status":2,
           "stock":{"time_no":"$SLOT_OCCUPIED","price":"25","all_count":6,"using_num":4}}
        ]}
    """.trimIndent()

    // ── 响应小工具（与其余夹具同形）────────────────────────────────

    /**
     * `VenueApi` 的每一枪都带 ajax 头（`Referer` + `X-Requested-With`）。少了就 400 ——
     * 「请求形状」这一条才有东西钉着，而不是随便一个 URL 都能拿到数据。
     */
    private fun requireAjax(exchange: HttpExchange, query: String): Unit? {
        val referer = exchange.requestHeaders.getFirst("Referer")
        val requestedWith = exchange.requestHeaders.getFirst("X-Requested-With")
        lastReferer = referer
        lastRequestedWith = requestedWith
        if (referer.isNullOrBlank() || requestedWith != "XMLHttpRequest") {
            badRequest(
                exchange,
                "业务请求要带 Referer 与 X-Requested-With: XMLHttpRequest，" +
                    "收到 Referer=$referer X-Requested-With=$requestedWith（?$query）",
            )
            return null
        }
        return Unit
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun respondHtml(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun notFound(exchange: HttpExchange, origin: String) {
        val url = exchange.requestURI.toString()
        respond(exchange, 404, "text/plain; charset=utf-8", "no such venue path at $origin: $url".toByteArray())
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
}
