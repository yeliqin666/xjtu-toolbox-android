package com.xjtu.toolbox.venue

import com.xjtu.toolbox.auth.VenueLogin
import com.xjtu.toolbox.auth.VenueSession
import com.xjtu.toolbox.auth.withVenueLogin
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.AREA_FALLBACK_NAME
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.AREA_FULL_SURPLUS
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.ADVANCE_DAY_DEFAULT
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.ADVANCE_NUM_DEFAULT
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.CAPTCHA_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.EMPTY_SERVICE_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.ORDER_PAID_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.ORDER_PENDING_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.PRICE_EARLY
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.PRICE_YUAN
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.SERVICE_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.SLOT_EARLY
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.SLOT_LATE
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.SLOT_OCCUPIED
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_A_ADDRESS
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_A_ADVANCE_DAY
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_A_ADVANCE_NUM
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_A_ICON
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_A_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_A_NAME
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_ASCII_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_ASCII_NAME
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_B_ADDRESS
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_B_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_B_NAME
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_F_ADVANCE_DAY
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_F_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_F_NAME
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_GBK_ADDRESS
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_GBK_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.VENUE_GBK_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 体育场馆取数的**字段级口径**：对着 `:testkit` 的 [VenueFakeUpstream] 夹具逐字段读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把场馆的取数（`VenueApi`）、收藏与站点（`VenueSession` + `VenueLogin`）从 `:app` 搬进了
 * `:data`（桌面端第 11 条真数据路由）。搬之前先用夹具把**搬之前**的口径钉住：`:app` 那边一行逻辑
 * 没改，只是文件换了地方、`Context` 换成了 `:core` 的 `VenueFavorites` —— 但「没改」这句话得有证据，
 * 这些断言就是那个证据。手法与空闲教室 / 校园卡 / 教务那几条（`EmptyRoomApiJvmTest` /
 * `CampusCardApiJvmTest` / `SchoolCourseApiJvmTest`）逐条对齐：要登录的那几条走**真登录链**
 *（`withVenueLogin`），URL 一个字符都不改。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（[VenueFakeUpstream] 里那些常量与 JSON），**不是从跑通的实现里抄回来的**。
 * 夹具刻意摆了一批「不完整 / 坏形状」的行，好让每条默认值都写在明面上：`id` 为 0 的场馆、
 * 缺 `name` 的场馆、负数与字符串形式的 `advanceday`、GBK 编码那一页、`status=1` 但已满的时段、
 * 空 `sname`、带 `¥` 的价格、订单里空 `orderid` 的行与不是对象的数组元素。
 *
 * ## 写路径为什么在有剧本之后才在这里
 *
 * 下单要先解滑块；夹具默认关着（[VenueFakeUpstream.captchaScript] = false，`tobook` 仍是 404）。
 * 打开剧本后（Stage B 两条写路径用例），这里钉住「识别 → 提交 → 成功」与「拖错被拒」——
 * 轨迹把关、返回形状都与真站点对齐（见那个夹具的「写路径」一节）。
 */
class VenueApiJvmTest {

    /** 夹具与生产必须指同一个站点、同一批路径 —— 漂了的话，下面这些断言测的就不是真协议。 */
    @Test
    fun `夹具与生产指同一个场馆站，URL 一字未改`() {
        assertEquals(VenueLogin.BASE_URL, VenueFakeUpstream.ORIGIN)
        assertEquals(VenueLogin.PAY_BASE_URL, VenueFakeUpstream.PAY_ORIGIN)
        assertEquals(VenueLogin.VENUE_OAUTH_URL, VenueFakeUpstream.OAUTH_URL)
        assertEquals(VenueApi.BROWSER_LOGIN_URL, VenueFakeUpstream.OAUTH_URL)
        assertEquals("${VenueLogin.BASE_URL}/web/cas/oauth2url.html", VenueFakeUpstream.OAUTH_CALLBACK_URL)
        assertEquals("${VenueLogin.BASE_URL}/web/index.html", VenueFakeUpstream.INDEX_URL)
        assertEquals("${VenueLogin.BASE_URL}/web/product/productData.html", VenueFakeUpstream.PRODUCT_LIST_URL)
        assertEquals("${VenueLogin.BASE_URL}/web/product/findOkArea.html", VenueFakeUpstream.OK_AREA_URL)
        assertEquals("${VenueLogin.BASE_URL}/web/product/findLockArea.html", VenueFakeUpstream.LOCK_AREA_URL)
        assertEquals("${VenueLogin.BASE_URL}/web/yyuser/searchorder.html", VenueFakeUpstream.ORDER_LIST_URL)
        // 验证码在**根路径**（不在 `/web/` 下）—— 这一条是最容易搬错的一处
        assertEquals("${VenueLogin.BASE_URL}/gen", VenueFakeUpstream.CAPTCHA_URL)
    }

    /**
     * 场馆列表：第一页正好 8 条 ⇒ 客户端必须再要一页；第二页 2 条 ⇒ 停（共两枪）。
     *
     * 同一批数据里钉住的默认值：`id<=0` 的行被跳过、缺 `name` 读成空串、`advanceday/advancenum`
     * 缺 / 为 0 / 为负都落回 7 / 8、数字写成字符串照样读得出；第二页那份按 GBK 发而声明 latin-1，
     * 中文名字要由 `fixGbk` 修回来，纯 ASCII 的名字不许被它动到。
     */
    @Test
    fun `真登录之后：场馆列表分两页拉完，坏 id 与缺字段都按口径落`() = withVenueLogin { site, fake ->
        // 登录链真的走完了（OAuth 入口 → CAS → 回跳 → 首页 userno）
        assertTrue(fake.venue.oauthCalls.get() >= 1, "登录入口那一跳应当被打过")
        assertEquals(1, fake.venue.ticketLandings.get(), "CAS 回跳只该落在那一个回调上")
        assertEquals(1, fake.library.credentialPosts.get(), "应当恰好提交一次凭据")
        assertTrue(site.hasLogin, "登录后场馆站点应是已登录态")

        val api = VenueApi(site)
        val venues = runBlocking { api.fetchVenueList() }

        assertEquals(2, fake.venue.listCalls.get(), "首页 8 条要再要一页，第二页不足一页就停")
        assertEquals("2", param(fake.venue.lastListQuery, "page"), "最后一次列表请求应当是第二页")
        // 夹具只认 Ajax 头：少了就 400（因此这条断言同时钉住「每一枪都带 Referer/X-Requested-With」）
        assertEquals("XMLHttpRequest", fake.venue.lastRequestedWith)
        assertTrue(fake.venue.lastReferer.orEmpty().startsWith(VenueFakeUpstream.WEB))

        assertEquals(
            listOf(
                VENUE_A_ID, VENUE_B_ID, 103, 105, VENUE_F_ID, 107, 108, VENUE_ASCII_ID, VENUE_GBK_ID,
            ),
            venues.map { it.id },
            "id=0 的那一行该被跳过；两页按上游给的顺序拼起来",
        )

        val byId = venues.associateBy { it.id }
        assertEquals(
            Venue(
                id = VENUE_A_ID,
                name = VENUE_A_NAME,
                address = VENUE_A_ADDRESS,
                iconType = VENUE_A_ICON,
                advanceDay = VENUE_A_ADVANCE_DAY,
                advanceNum = VENUE_A_ADVANCE_NUM,
            ),
            byId.getValue(VENUE_A_ID),
        )
        assertEquals(VENUE_B_ADDRESS, byId.getValue(VENUE_B_ID).address)
        assertEquals(listOf(7, 8), listOf(byId.getValue(VENUE_B_ID).advanceDay, byId.getValue(VENUE_B_ID).advanceNum))

        // 缺 name 的那一行：名字是空串，其余字段取默认值
        val bare = byId.getValue(105)
        assertEquals("", bare.name)
        assertNull(bare.address)
        assertNull(bare.iconType)
        assertEquals(listOf(ADVANCE_DAY_DEFAULT, ADVANCE_NUM_DEFAULT), listOf(bare.advanceDay, bare.advanceNum))

        // advanceday 写成字符串那一行：照样读成 5；advancenum 缺 ⇒ 8
        assertEquals(VENUE_F_NAME, byId.getValue(VENUE_F_ID).name)
        assertEquals(
            listOf(VENUE_F_ADVANCE_DAY, ADVANCE_NUM_DEFAULT),
            listOf(byId.getValue(VENUE_F_ID).advanceDay, byId.getValue(VENUE_F_ID).advanceNum),
        )

        // 负数那一行：落回默认值，不是负数
        assertEquals(
            listOf(ADVANCE_DAY_DEFAULT, ADVANCE_NUM_DEFAULT),
            listOf(byId.getValue(107).advanceDay, byId.getValue(107).advanceNum),
        )

        // 第二页（GBK 那一页）：ASCII 名原样，中文名由 fixGbk 修回来
        assertEquals(VENUE_ASCII_NAME, byId.getValue(VENUE_ASCII_ID).name)
        assertEquals("Xingyi Campus", byId.getValue(VENUE_ASCII_ID).address, "ASCII 的地址不该被 fixGbk 动到")
        assertEquals(VENUE_GBK_NAME, byId.getValue(VENUE_GBK_ID).name)
        assertEquals(VENUE_GBK_ADDRESS, byId.getValue(VENUE_GBK_ID).address)

        // 端口那一层是同一份取数（它只负责包调度器与收藏，不重写解析）
        assertEquals(venues, runBlocking { AppVenueSource(site).venues() })
    }

    /**
     * 某天的时段：可订与已占用**两路合并后按 (时段, 场地名) 排序** —— 只拿可订那一半，屏上就分不出
     * 「满了」和「没有这个时段」。同一条登录顺带把「没有时段的场馆给空表」与验证码那一条读了。
     */
    @Test
    fun `真登录之后：时段是两路合并后排序的，全满的时段不是零剩余`() = withVenueLogin { site, fake ->
        val api = VenueApi(site)
        val slots = runBlocking { api.fetchAvailableSlots(SERVICE_ID, DATE) }

        assertEquals(1, fake.venue.okCalls.get())
        assertEquals(1, fake.venue.lockCalls.get(), "已占用那一半也要打（不然分不出「满了」）")
        assertTrue(fake.venue.lastOkQuery.orEmpty().contains("s_date=$DATE"), "日期要原样传给上游")
        assertTrue(fake.venue.lastOkQuery.orEmpty().contains("serviceid=$SERVICE_ID"))

        assertEquals(
            listOf(5101L, 5102L, 5105L, 5103L, 5104L, 5202L, 5201L),
            slots.map { it.areaDetailId },
            "按 (时段, 场地名) 排序：08:00 三个场地、10:00 两个、12:00 两个（占用的那两个排在最后）",
        )
        assertEquals(
            listOf(
                SLOT_EARLY, SLOT_EARLY, SLOT_EARLY,
                SLOT_LATE, SLOT_LATE,
                SLOT_OCCUPIED, SLOT_OCCUPIED,
            ),
            slots.map { it.timeSlot },
        )
        assertEquals(listOf("场地1", "场地2", "场地3", "场地1", AREA_FALLBACK_NAME, "场地1", "场地3"), slots.map { it.areaName })

        // 剩余：status==1 ⇒ all-using；all_count == using_num 时兜到 1（不是 0）
        assertEquals(listOf(7, AREA_FULL_SURPLUS, 5, 6, AREA_FULL_SURPLUS, 0, 0), slots.map { it.surplus })
        assertTrue(slots.take(5).all { it.isAvailable }, "前五格可订")
        assertTrue(slots.drop(5).none { it.isAvailable }, "占用那两格剩余为 0 ⇒ 屏上画成已占")

        // 库存与容量：同一次查询里两个 `stock` 的记录（occupancy 是时段级的）
        val early = slots.first()
        assertEquals(9101L, early.stockId)
        assertEquals(10, early.allCount)
        assertEquals(3, early.usingNum)
        assertEquals(SERVICE_ID.toString(), early.serviceid)
        assertEquals(DATE, early.date, "date 用的是请求里那一天（夹具不写死日期）")

        // 价格：数字、带 ¥ 的、以及 `"0"`（读成 0.0）
        assertEquals(
            listOf(PRICE_EARLY, PRICE_EARLY, 18.5, PRICE_YUAN, 0.0, 25.0, 25.0),
            slots.map { it.price },
        )

        // 没有时段的场馆 ⇒ 空表（不是异常）
        assertEquals(emptyList(), runBlocking { api.fetchAvailableSlots(EMPTY_SERVICE_ID, DATE) })

        // 验证码：id 与六个数（其中四个在夹具里写成字符串）
        val captcha = runBlocking { api.generateCaptcha(SERVICE_ID) }
        assertEquals(1, fake.venue.captchaCalls.get())
        assertEquals(CAPTCHA_ID, captcha.id)
        assertEquals(
            listOf(260, 160, 50, 50),
            listOf(captcha.bgWidth, captcha.bgHeight, captcha.sliderWidth, captcha.sliderHeight),
        )
        assertEquals("data:image/jpeg;base64,ZmFrZS1iZw==", captcha.backgroundImage)
        assertEquals("data:image/png;base64,ZmFrZS1zbGlkZXI=", captcha.sliderImage)
    }

    /** 已占用那一路挂了（500）不该把可订那一半也带走 —— `fetchAvailableSlots` 里那句 `runCatching`。 */
    @Test
    fun `真登录之后：已占用那一路挂了，可订那一半照给`() = withVenueLogin { site, fake ->
        fake.venue.lockFails = true
        val slots = runBlocking { VenueApi(site).fetchAvailableSlots(SERVICE_ID, DATE) }

        assertEquals(
            listOf(5101L, 5102L, 5105L, 5103L, 5104L),
            slots.map { it.areaDetailId },
            "占用那一路没了就只少那两格，不是整屏报错",
        )
        assertEquals(1, fake.venue.okCalls.get())
        assertEquals(0, fake.venue.lockCalls.get(), "失败那一枪不该记成成功过")
    }

    /**
     * 订单列表：`rows` 包装、两条明细（一条字段齐全、一条只有场地名）、以及两个该被丢掉的行
     *（`orderid` 为空的那条、数组里不是对象的那个元素）。第二页给空数组 ⇒ 空页。
     */
    @Test
    fun `真登录之后：订单两条，空 orderid 与不是对象的行都丢掉`() = withVenueLogin { site, fake ->
        val api = VenueApi(site)
        val page = runBlocking { api.fetchOrders(page = 1, pageSize = 20) }

        assertEquals(2, page.total)
        assertEquals(false, page.hasMore, "总数 2 / 每页 20 ⇒ 没有下一页")
        assertEquals(
            listOf(ORDER_PAID_ID, ORDER_PENDING_ID),
            page.orders.map { it.orderId },
        )
        assertTrue(fake.venue.lastOrderQuery.orEmpty().contains("page=1"))
        assertTrue(fake.venue.lastOrderQuery.orEmpty().contains("rows=20"))

        val paid = page.orders[0]
        assertEquals(1, paid.status)
        assertEquals("预订成功", paid.statusText)
        assertEquals("2026-10-12 08:05:00", paid.createdAt)
        assertEquals(20.0, paid.price)
        assertEquals(VENUE_A_NAME, paid.venueName, "场馆名从明细的 service.name 来")
        assertEquals(
            listOf(
                OrderDetail(
                    date = "2026-10-12",
                    timeSlot = SLOT_EARLY,
                    areaName = "场地1",
                    price = 20.0,
                    serviceId = SERVICE_ID.toString(),
                    serviceName = VENUE_A_NAME,
                ),
            ),
            paid.details,
        )

        // 第二条：明细两条，第二条**没有** service / stock ⇒ 那几个字段如实留空（不是猜一个）
        val pending = page.orders[1]
        assertEquals(0, pending.status)
        assertEquals("预订中", pending.statusText)
        assertEquals(2, pending.details.size)
        assertEquals(
            OrderDetail(
                date = "",
                timeSlot = "",
                areaName = "场地3",
                price = 0.0,
                serviceId = "",
                serviceName = "",
            ),
            pending.details[1],
        )

        // 第二页：空数组 ⇒ 空页（不是异常）
        val second = runBlocking { api.fetchOrders(page = 2, pageSize = 20) }
        assertEquals(emptyList(), second.orders)
        assertEquals(2, fake.venue.orderCalls.get())
    }

    /** 两个纯函数：`prepareOrder` 拼服务端参数（stockdetail 的 key 是库存、value 是逗号连起来的场地）、`paymentUrl` 在 80 端口那台站上。 */
    @Test
    fun `prepareOrder 与 paymentUrl 是纯计算，不碰网络`() {
        val api = VenueApi(VenueSession())
        val early = slot(areaDetailId = 5101, areaName = "场地1", stockId = 9101, timeSlot = SLOT_EARLY)
        val second = slot(areaDetailId = 5102, areaName = "场地2", stockId = 9101, timeSlot = SLOT_EARLY)
        val late = slot(areaDetailId = 5103, areaName = "场地1", stockId = 9102, timeSlot = SLOT_LATE)

        assertEquals(
            """{"stockdetail":{"9101":"5101,5102","9102":"5103"},"venueReason":"","fileUrl":"","address":"$SERVICE_ID"}""",
            api.prepareOrder(SERVICE_ID, listOf(early, second, late)).rawParamJson,
        )
        // 空选择是调用方的错（屏上不会走到：时段没选之前「确认预订」不出现）
        assertFailsWith<IllegalArgumentException> { api.prepareOrder(SERVICE_ID, emptyList()) }

        assertEquals(
            "${VenueLogin.PAY_BASE_URL}/pay/show.html?id=$ORDER_PAID_ID",
            api.paymentUrl(ORDER_PAID_ID),
        )
        // 订单号里的特殊字符要转义（`java.net.URLEncoder` 的老口径：空格变 `+`、`/` 变 `%2F`）
        assertEquals("${VenueLogin.PAY_BASE_URL}/pay/show.html?id=A%2F1+2", api.paymentUrl("A/1 2"))
    }

    /**
     * 端口那一层的能力开关与收藏。
     *
     * `canBook` 由构造参数决定（Android 默认 true；桌面端传 false —— 它没有滑块控件，下单那一步
     * 走不完），`canCancel` 与实现一起是 true（取消不需要滑块）。收藏走 `:core` 共享的
     * [VenueFavorites]（`KeyValueStore`，JVM 侧是进程内内存实现）—— 这里钉住「翻转返回的是
     * **切换后**的状态」与「再翻一次回到原位」。
     */
    @Test
    fun `端口的能力开关由构造参数决定，收藏走共享实现`() {
        val android = AppVenueSource(VenueSession())
        assertTrue(android.canBook, "默认（Android 那一侧）是能下单的")
        assertTrue(android.canCancel)
        assertEquals(VenueApi.BROWSER_LOGIN_URL, android.browserLoginUrl)

        val desktop = AppVenueSource(VenueSession(), canBook = false)
        assertTrue(!desktop.canBook, "桌面端如实声明不能下单")
        assertTrue(desktop.canCancel, "取消不需要滑块 ⇒ 这一件照旧能做")

        // 收藏：同一份共享实现（`venue_favorites` / `favorite_venue_ids`，见 VenueFavorites 的 KDoc）
        val before = runBlocking { android.favorites() }
        assertTrue(VENUE_A_ID !in before, "这条用例开始时夹具那个场馆不该已被收藏：$before")
        assertTrue(runBlocking { android.toggleFavorite(VENUE_A_ID) }, "第一次翻转 ⇒ 变成已收藏")
        assertTrue(VENUE_A_ID in runBlocking { android.favorites() }, "收藏读得回来（走的是同一份落盘）")
        assertTrue(!runBlocking { desktop.toggleFavorite(VENUE_A_ID) }, "第二次翻转 ⇒ 取消收藏（同一个布尔返回）")
        assertEquals(before, runBlocking { android.favorites() }, "翻两次回到原位")
    }

    // ══════ Stage B：滑块剧本下的写路径（`captchaScript = true` 才打开）══════

    /**
     * 登录 → 拿滑块挑战 → **自动识别** → 提交 → 下单成功。
     *
     * `captchaScript = true` 时 `/gen` 发的是 [VenueCaptchaFixture] 的程序生成真图，识别器
     * 解出位移 141，`tobook` 校验轨迹最终 x 落在缺口（±3）才回成功 —— 这一条把「识别器 →
     * 提交 → 成功」的整条写路径钉在同一份夹具上。
     */
    @Test
    fun `真登录之后：滑块剧本下识别并提交，下单成功`() = withVenueLogin { site, fake ->
        fake.venue.captchaScript = true
        val api = VenueApi(site)

        val captcha = runBlocking { api.generateCaptcha(SERVICE_ID) }
        assertEquals(CAPTCHA_ID, captcha.id)
        assertEquals(1, fake.venue.captchaCalls.get())

        // 识别 + 盖章走共享宿主（:data）—— 桌面宿主也委托这一份
        val shownAt = System.currentTimeMillis()
        val solved = runBlocking { VenueSlideCaptchaHost.solve(captcha, shownAt) }
        assertNotNull(solved, "合成图上的缺口应当被识别出来")
        assertEquals(VenueCaptchaFixture.EXPECTED_SOLVE_TARGET_X, solved.sliderResult.trackList.last().x)

        val pending = api.prepareOrder(SERVICE_ID, listOf(slot(5101, "场地1", 9101, SLOT_EARLY)))
        val result = runBlocking {
            api.submitBooking(SERVICE_ID, pending, captcha.id, solved.sliderResult.toJson())
        }

        assertTrue(result.success, "拖对了应当下单成功：${result.message}")
        assertEquals(VenueFakeUpstream.BOOKED_ORDER_ID, result.orderId)
        assertEquals("预订成功", result.message)
        assertEquals(1, fake.venue.tobookCalls.get(), "一次成功，不该有重试")
        assertTrue(fake.venue.lastYzm.orEmpty().contains("synjones$CAPTCHA_ID" + "synjones"), "yzm 带着验证码 id")
        assertTrue(fake.venue.lastBookingParam.orEmpty().contains("\"stockdetail\""), "param 是服务端参数信封")
    }

    /**
     * 拖**错**位置的轨迹被夹具按真站点那句「验证码有误」拒绝（result 100 ⇒ [VenueApi.submitBooking]
     * 原样重试同一份请求三次，全失败）。写路径的拒绝对齐到真站点的语义。
     */
    @Test
    fun `真登录之后：拖错位置的轨迹被按验证码有误拒绝`() = withVenueLogin { site, fake ->
        fake.venue.captchaScript = true
        val api = VenueApi(site)

        val captcha = runBlocking { api.generateCaptcha(SERVICE_ID) }
        val wrong = SliderResult(
            bgImageWidth = 260,
            bgImageHeight = 0,
            sliderImageWidth = 0,
            sliderImageHeight = 50,
            startSlidingTime = "",
            entSlidingTime = "",
            trackList = listOf(
                TrackPoint(0, 0, "down", 800),
                TrackPoint(50, 0, "up", 1500),
            ),
        )
        val pending = api.prepareOrder(SERVICE_ID, listOf(slot(5101, "场地1", 9101, SLOT_EARLY)))
        val result = runBlocking {
            api.submitBooking(SERVICE_ID, pending, captcha.id, wrong.toJson())
        }

        assertTrue(!result.success, "拖错位置不该下单成功")
        assertTrue(result.message.contains("验证码"), "拒绝对齐真站点那句：实际=${result.message}")
        assertEquals(3, fake.venue.tobookCalls.get(), "result=100+验证码 ⇒ 同一份请求重试三次")
    }

    private fun param(raw: String?, name: String): String? =
        raw?.split('&')?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    private fun slot(
        areaDetailId: Long,
        areaName: String,
        stockId: Long,
        timeSlot: String,
    ) = AreaSlot(
        areaDetailId = areaDetailId,
        areaName = areaName,
        stockId = stockId,
        timeSlot = timeSlot,
        price = 20.0,
        date = DATE,
        allCount = 10,
        usingNum = 1,
        surplus = 9,
        serviceid = SERVICE_ID.toString(),
    )

    private companion object {
        /** 时段是按天查的，夹具不写死日期 ⇒ 断言里用这一个（`AreaSlot.date` 就是它）。 */
        const val DATE = "2026-10-12"
    }
}
