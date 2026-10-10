package com.xjtu.toolbox.emptyroom

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.auth.withJsLogin
import com.xjtu.toolbox.auth.withJwxtLogin
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.BUILDING_A
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.BUILDING_E
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.CDN_FILE_PREFIX
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.DATE
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.LIVE_CAMPUS
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.NO_DATA_DATE
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_A101
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_A102
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_A103
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_BAD_JSON
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_BLANK_KEY
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_E303
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_E305
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream.Companion.ROOM_NULL_KEY
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 空闲教室取数的**字段级口径**：三档（CDN / 直查教务 / 实时状态）对着 `:testkit` 的夹具逐字段读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把空闲教室的取数与站点从 `:app` 搬进了 `:data`（桌面端第 10 条真数据路由）。搬之前先用
 * 夹具把**搬之前**的口径钉住：`:app` 那边一行逻辑没改，只是文件换了地方、`Context` 换成
 * `:core` 的 [EmptyRoomStore] 缝 —— 但「没改」这句话得有证据，这些断言就是那个证据。
 * 手法与校园卡 / 教务那几条（`CampusCardApiJvmTest` / `SchoolCourseApiJvmTest`）逐条对齐：
 * 要登录的两档走**真登录链**（[withJwxtLogin] / [withJsLogin]），URL 一个字符都不改；
 * CDN 那一档本来就免登录，只要假上游在。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（`EmptyRoomFakeUpstream` / `JwxtFakeUpstream` 里那些常量与 JSON），
 * **不是从跑通的实现里抄回来的** —— 两档刻意摆了同一批教室名与座位数（屏按教室名把「实时状态 +
 * 当天课表」对上），CDN 那份还摆了四个坑（键为 `"null"` / 空串、值为 null、缺 `status`），
 * 教务那份摆了两条该被滤掉的噪声行。「过滤与兜底给什么」因此写在明面上。
 */
class EmptyRoomApiJvmTest {

    /** CDN 那一档的假上游：**不需要任何会话**（这个接口是公开的预生成 JSON）。 */
    private fun withFakeCdn(block: (FakeCampusProxy) -> Unit) {
        val fake = FakeCampusProxy()
        FakeCampusProxy.installFakeUpstreams()
        fake.start()
        try {
            block(fake)
        } finally {
            fake.close()
        }
    }

    // ══════ ① CDN 课表（免登录那一档）══════

    /**
     * 一次问两个楼：结果是**按教室名排好的一份**（`getEmptyRoomsMulti` 的 merge 语义），
     * 四个坑（`"null"` 键、空键、值为 null、缺 `status`）一个都不该出现。
     */
    @Test
    fun `CDN：免登录就读得出教室列表与 11 节占用（空键、null、坏数据都滤掉）`() {
        withFakeCdn { fake ->
            val rooms = EmptyRoomApi()
                .getEmptyRoomsMulti(LIVE_CAMPUS, setOf(BUILDING_A, BUILDING_E), DATE)

            // 两个字串比较是 UTF-16 码位序：数字 < 东(4E1C) < 主(4E3B) < 无(65E0)
            assertEquals(
                listOf("203", ROOM_E303, ROOM_E305, ROOM_A101, ROOM_A102, ROOM_A103, ROOM_BLANK_KEY),
                rooms.map { it.name },
            )
            val byName = rooms.associateBy { it.name }
            // 座位数：`size` 缺了就是 0（不是「拿不到就算了」——屏上那一格要显示「0 座」）
            assertEquals(24, byName.getValue("203").size)
            assertEquals(EmptyRoomFakeUpstream.SEATS_A101, byName.getValue(ROOM_A101).size)
            assertEquals(EmptyRoomFakeUpstream.SEATS_E303, byName.getValue(ROOM_E303).size)
            assertEquals(0, byName.getValue(ROOM_BLANK_KEY).size)
            // 11 节状态：数组照上游给的顺序、原样 0/1
            assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1), byName.getValue("203").status)
            assertEquals(listOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0), byName.getValue(ROOM_A101).status)
            assertEquals(List(11) { 0 }, byName.getValue(ROOM_A102).status)
            assertEquals(listOf(0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0), byName.getValue(ROOM_E303).status)

            // 四个坑一个都不在
            assertTrue(ROOM_NULL_KEY !in byName, "键为 \"null\" 的那间该被滤掉")
            assertTrue("" !in byName, "空键的那间该被滤掉")
            assertTrue(ROOM_BAD_JSON !in byName, "缺 status 的那间该被 mapNotNull 丢掉")
            assertTrue("空值房" !in byName, "值为 JSON null 的那间该被滤掉")

            // 只打了一枪（源自己按天缓存整份 JSON）
            assertEquals(1, fake.emptyRoom.cdnCalls.get())
            assertEquals(DATE, fake.emptyRoom.lastCdnDate)
        }
    }

    /** 另一个校区：只给要的那几个楼（上游那份 JSON 里另一个校区也有数据）。 */
    @Test
    fun `CDN：换一个校区与楼就只拿那几间`() {
        withFakeCdn {
            val rooms = EmptyRoomApi().getEmptyRoomsMulti("创新港校区", setOf("1号巨构"), DATE)
            assertEquals(listOf("1-2050"), rooms.map { it.name })
            assertEquals(50, rooms.single().size)
        }
    }

    /**
     * 两条错误分支要分得开：**404 = 这天还没生成**（`NoDataException`，屏上直接报错、不兜底缓存），
     * **校区不存在**（也是 `NoDataException`，但文案不同）。夹具把这两条摆成两种响应。
     */
    @Test
    fun `CDN：404 与查不到的校区都抛 NoDataException（不是 RuntimeException）`() {
        withFakeCdn {
            val missing = assertFailsWith<NoDataException> {
                EmptyRoomApi().getEmptyRoomsMulti(LIVE_CAMPUS, setOf(BUILDING_A), NO_DATA_DATE)
            }
            assertEquals("当天暂无空闲教室数据，请稍后再试", missing.message)

            val unknownCampus = assertFailsWith<NoDataException> {
                EmptyRoomApi().getEmptyRoomsMulti("曲江校区", setOf(BUILDING_A), DATE)
            }
            assertEquals("暂无 曲江校区 的数据", unknownCampus.message)
        }
    }

    /**
     * 座位数（课表页的课程详情要显示「XX座」）：上游同一份 JSON 里既有全名键、也有纯房间号键，
     * 两条查找路径都要走通；查不到的记成 null 且**不重复拉全校数据**（同一次进程只打一枪）。
     */
    @Test
    fun `座位数：全名与「楼名-房间号」两种写法都查得到，查不到返回 null 且不再多打上游`() {
        withFakeCdn { fake ->
            val api = EmptyRoomApi()
            assertEquals(EmptyRoomFakeUpstream.SEATS_A101, api.getRoomSeatCount(ROOM_A101), "全名键（尝试 2）")
            assertEquals(24, api.getRoomSeatCount("主楼A-203"), "拆成「楼名 + 房间号」（尝试 1）")
            assertEquals(EmptyRoomFakeUpstream.SEATS_E305, api.getRoomSeatCount(ROOM_E305))
            assertNull(api.getRoomSeatCount(ROOM_BLANK_KEY), "size 缺了这一格，不该编一个")
            assertNull(api.getRoomSeatCount("主楼A-888"), "查不到就是 null")
            assertEquals(1, fake.emptyRoom.cdnCalls.get(), "四枪座位查询共用同一份当天快照")
        }
    }

    // ══════ ② 直查教务（第三档：逐楼逐节问教务）══════

    /**
     * 真登录链（[withJwxtLogin]：CAS 表单 POST → TGC → 签 ticket → 回跳教务）之后直查「主楼A」：
     * `queryDay` = 先问一次全楼名录（`KSJC=JSJC=0`）、再逐节问 11 次，命中即把那一节标成空闲。
     *
     * 夹具把三条分支摆齐：第 6-11 节空闲 / 全天空闲 / 全天占用；另有两行**噪声**（`JASLXDM` 为 null
     * 的「幻觉教室」与名字含「测试专用」的）每枪都回，必须被生产代码滤掉。
     */
    @Test
    fun `直查教务：逐节问出 11 节状态，两条噪声行被滤掉`() {
        withJwxtLogin { site, fake ->
            val query = EmptyRoomDirectQuery(site.client)
            val progress = mutableListOf<Pair<Int, Int>>()

            val rooms = query.queryDay(LIVE_CAMPUS, BUILDING_A, DATE) { done, total -> progress += done to total }

            assertEquals(
                listOf(
                    JwxtFakeUpstream.EMPTY_ROOM_A101,
                    JwxtFakeUpstream.EMPTY_ROOM_A102,
                    JwxtFakeUpstream.EMPTY_ROOM_A103,
                ),
                rooms.map { it.name },
                "两条噪声行不该出现在结果里",
            )
            val byName = rooms.associateBy { it.name }
            assertEquals(
                listOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0),
                byName.getValue(JwxtFakeUpstream.EMPTY_ROOM_A101).status,
            )
            assertEquals(List(11) { 0 }, byName.getValue(JwxtFakeUpstream.EMPTY_ROOM_A102).status)
            assertEquals(List(11) { 1 }, byName.getValue(JwxtFakeUpstream.EMPTY_ROOM_A103).status)
            assertEquals(JwxtFakeUpstream.EMPTY_ROOM_A101_SEATS, byName.getValue(JwxtFakeUpstream.EMPTY_ROOM_A101).size)

            // 进度回调：1..11 各一次，总节数恒为 11
            assertEquals((1..11).map { it to 11 }, progress)

            // 12 枪：全楼名录一次 + 逐节 11 次；已经是学生 ⇒ 一次角色切换都不发
            assertEquals(12, fake.jwxt.emptyRoomQueryCalls.get())
            assertEquals(0, fake.jwxt.changeRoleCalls.get(), "当前身份就是学生，不该换角色")
            assertTrue(fake.jwxt.currentUserCalls.get() >= 1, "查之前要确认一次当前身份")

            // 请求形状（最后一枪是第 11 节）
            val form = fake.jwxt.lastEmptyRoomForm.orEmpty()
            listOf("XXXQDM=1", "JXLDM=A", "KXRQ=$DATE", "KSJC=11", "JSJC=11", "pageSize=500", "pageNumber=1")
                .forEach { assertTrue(form.contains(it), "cxkxjs.do 的请求里少了 $it：$form") }
        }
    }

    /**
     * 单次查询那一层（`queryRooms`）的**逐字段**口径：`DirectRoomRow` 的六个字段与两条过滤，
     * 以及缺 `JASLXDM_DISPLAY` 时 `type` 是 null（不是空串、不编一个）。
     */
    @Test
    fun `直查教务：单次查询逐字段对得上（缺的键留 null）`() {
        withJwxtLogin { site, fake ->
            val rows = EmptyRoomDirectQuery(site.client).queryRooms("1", "A", DATE, 0, 0)

            assertEquals(3, rows.size)
            val first = rows.first()
            assertEquals(JwxtFakeUpstream.EMPTY_ROOM_A101, first.name)
            assertEquals(BUILDING_A, first.buildingName)
            assertEquals(JwxtFakeUpstream.EMPTY_ROOM_TYPE, first.type)
            assertEquals(JwxtFakeUpstream.EMPTY_ROOM_A101_SEATS, first.capacity)
            assertEquals(JwxtFakeUpstream.EMPTY_ROOM_A101_SEATS, first.examCapacity)
            assertEquals(JwxtFakeUpstream.EMPTY_ROOM_CAMPUS_NAME, first.campusName)

            assertNull(rows.last().type, "那一行没给 JASLXDM_DISPLAY ⇒ null")
            assertEquals(0, fake.jwxt.changeRoleCalls.get(), "当前身份就是学生，不该换角色")
        }
    }


    // ══════ ③ 实时状态（智慧教室平台那一档）══════

    /**
     * 真登录链（[withJsLogin]：CAS → 回跳 js 入口 → 用票换 `TOKEN-AUTH`）之后拉一个校区：
     * 楼顺序就是平台给的顺序，四种状态、人数、课程与教师都逐字段对。
     *
     * 顺带钉住「没有宿主存储时的那条口径」：`LiveRoomApi` 的 60 秒新鲜度缓存**是存到磁盘的**
     * （`cache?.writeJson`）⇒ 桌面端（`store = null`）第二枪照样打平台。
     */
    @Test
    fun `实时状态：真登录链之后读得出夹具样本（store 为 null 时不吃新鲜度缓存）`() {
        withJsLogin { site, fake ->
            val api = LiveRoomApi(site)
            val snapshot = runBlocking { api.fetchCampus(LIVE_CAMPUS) }

            assertEquals(listOf(BUILDING_A, BUILDING_E), snapshot.buildings)
            assertEquals(
                listOf(ROOM_A101, ROOM_A102, ROOM_E303, ROOM_E305),
                snapshot.rooms.map { it.name },
            )
            assertEquals(2, snapshot.freeCount)
            assertEquals(1, snapshot.inUseCount)
            assertEquals(1, snapshot.inClassCount)

            val inClass = snapshot.rooms.first { it.name == ROOM_A101 }
            assertTrue(inClass.isInClass)
            assertEquals(EmptyRoomFakeUpstream.LIVE_COURSE, inClass.course)
            assertEquals(EmptyRoomFakeUpstream.LIVE_TEACHER, inClass.teacher)
            assertEquals(EmptyRoomFakeUpstream.LIVE_IN_CLASS_PEOPLE, inClass.people)
            assertEquals(EmptyRoomFakeUpstream.SEATS_A101, inClass.seats)

            val inUse = snapshot.rooms.first { it.name == ROOM_E305 }
            assertTrue(inUse.isInUse)
            assertEquals(EmptyRoomFakeUpstream.LIVE_IN_USE_PEOPLE, inUse.people)
            assertNull(inUse.course)

            // 登录链：CAS 那一跳 + 换令牌各一次；请求里带着那两个头（夹具认头，缺了就 401）
            assertEquals(1, fake.emptyRoom.ticketLandings.get())
            assertEquals(1, fake.emptyRoom.tokenCalls.get())
            assertEquals(1, fake.emptyRoom.liveCalls.get())
            assertEquals(
                EmptyRoomFakeUpstream.TOKEN,
                site.localToken["token_auth"],
                "换来的令牌要落进站点快照（`JsSession.TOKEN_KEY` 就是它）",
            )
            assertTrue(
                fake.emptyRoom.lastLiveQuery.orEmpty().contains("campusName=$LIVE_CAMPUS"),
                "实时状态那一枪要带上平台认识的校区名：${fake.emptyRoom.lastLiveQuery}",
            )

            // 没有宿主存储 ⇒ 新鲜度缓存无处可存，第二枪再打一次（这是桌面端那条口径的代价）
            runBlocking { api.fetchCampus(LIVE_CAMPUS) }
            assertEquals(2, fake.emptyRoom.liveCalls.get())
        }
    }

    // ══════ ④ 端口实现（`AppEmptyRoomSource`）在「没有宿主存储」时的那条口径 ══════

    /**
     * 桌面端传的就是 `(sessionManager, store = null)`：三档能力照报（会话由它自己 ensure），
     * 但两处磁盘兜底都只能是 null（「缓存里什么都没有」），CDN 说明与日期口径与 Android 同源。
     *
     * 这一条**不碰网络**：没有会话时实时状态那一档抛的就是搬进 `:core` 之前那一句文案。
     */
    @Test
    fun `端口：store 为 null 时两处磁盘兜底都是 null，三档能力照报`() {
        val source = AppEmptyRoomSource(sessionManager = null)

        assertEquals(listOf(RoomSource.LIVE, RoomSource.CDN, RoomSource.DIRECT), source.availableSources)
        assertEquals(2, source.availableDates().size, "今天 + 明天（与 `getAvailableDates` 同口径）")
        assertNull(source.readStaleLive(LIVE_CAMPUS))
        assertNull(source.readStaleRooms(LIVE_CAMPUS, setOf(BUILDING_A), DATE, direct = false))
        assertNull(source.readStaleRooms(LIVE_CAMPUS, setOf(BUILDING_A), DATE, direct = true))

        val e = assertFailsWith<RuntimeException> {
            runBlocking { source.liveSnapshot(LIVE_CAMPUS, force = false) }
        }
        assertEquals("实时状态暂不可用，可在右上角切换到课表数据", e.message)
    }

    /**
     * 夹具与生产必须指同一批 URL 与同一批教室名：「实时状态 + 当天课表」在屏上按**教室全名**对上，
     * 两档名字与座位数各写一份，漂了就该有人喊。
     */
    @Test
    fun `夹具与生产：两档教室名与座位数指同一批`() {
        // 楼名得是屏上真列得出的（`CAMPUS_BUILDINGS` 里就有）
        assertTrue(BUILDING_A in CAMPUS_BUILDINGS.getValue(LIVE_CAMPUS), "$BUILDING_A 不在 $LIVE_CAMPUS 的楼表里")
        assertTrue(BUILDING_E in CAMPUS_BUILDINGS.getValue(LIVE_CAMPUS), "$BUILDING_E 不在 $LIVE_CAMPUS 的楼表里")
        // CDN / 实时状态那一份与直查教务那一份摆的是同一批教室（屏按名字把两档对上）
        assertEquals(ROOM_A101, JwxtFakeUpstream.EMPTY_ROOM_A101)
        assertEquals(ROOM_A102, JwxtFakeUpstream.EMPTY_ROOM_A102)
        assertEquals(ROOM_A103, JwxtFakeUpstream.EMPTY_ROOM_A103)
        assertEquals(EmptyRoomFakeUpstream.SEATS_A101, JwxtFakeUpstream.EMPTY_ROOM_A101_SEATS)
        assertEquals(EmptyRoomFakeUpstream.SEATS_A102, JwxtFakeUpstream.EMPTY_ROOM_A102_SEATS)
        assertEquals(EmptyRoomFakeUpstream.SEATS_A103, JwxtFakeUpstream.EMPTY_ROOM_A103_SEATS)
    }
}
