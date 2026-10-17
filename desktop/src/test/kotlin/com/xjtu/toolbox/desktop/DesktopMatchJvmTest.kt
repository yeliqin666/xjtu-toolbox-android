package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.social.MatchData
import com.xjtu.toolbox.social.MatchProfile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 匹配交友（`AppRoute.Match`）在桌面端的装配验收。 */
class DesktopMatchJvmTest {

    private val term = "2026-2027-1"
    private val account = "2021000001"

    private fun withTempDataRoot(block: () -> Unit) {
        dataRootOverride = Files.createTempDirectory("xjtu-desktop-match-test").toFile()
        val suffix = AccountContext.suffixFor(account)
        for (name in listOf(
            DesktopScheduleHost.SCHEDULE_CACHE_STORE + "_default",
            DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix,
            DesktopScheduleHost.CUSTOM_COURSE_STORE,
        )) {
            wipeSecureStore(name)
        }
        AccountContext.activeAccountId = null
        try {
            block()
        } finally {
            dataRootOverride = null
        }
    }

    private fun course(
        day: Int,
        from: Int,
        to: Int,
        code: String,
        name: String,
        teacher: String,
        room: String,
    ) = CourseItem(
        courseName = name,
        teacher = teacher,
        location = room,
        weekBits = "1".repeat(20),
        dayOfWeek = day,
        startSection = from,
        endSection = to,
        courseCode = code,
        courseType = "",
    )

    private fun exam(code: String, name: String, date: String) = ExamItem(
        courseName = name,
        courseCode = code,
        examDate = date,
        examTime = "09:00-11:00",
        location = "主楼A-101",
        seatNumber = "12",
    )

    /** 我这一端缓存里的那份课表（值都是编的）：周三 5-6 节在中2，还有一门周一的课。 */
    private fun myCourses() = listOf(
        course(1, 1, 2, "FIX-MATH", "高等数学（夹具）", "张老师（夹具）", "主楼A-101"),
        course(3, 5, 6, "FIX-PHY", "大学物理（夹具）", "李老师（夹具）", "中2-2101"),
    )

    /** 「对方」的档案（同一个学期的另一份夹具课表）：同课、同楼、同老师、同一天考试。 */
    private fun theirLocal() = MatchData.Local(
        courses = listOf(
            course(3, 5, 6, "FIX-PHY", "大学物理（夹具）", "李老师（夹具）", "中2-3305"),
            course(5, 9, 10, "FIX-ENG", "大学英语（夹具）", "王老师（夹具）", "东1东-101"),
        ),
        exams = listOf(
            exam("FIX-PHY", "大学物理（夹具）", "2027-01-08"),
            // 比我这门晚三天：顺带钉住「谁先考完」那一条发现。
            exam("FIX-ENG", "大学英语（夹具）", "2027-01-11"),
        ),
    )

    @Test
    fun `真出数：本端课表缓存 → 分享码 → 与对方的码解回来比出交集`() = withTempDataRoot {
        val auth = DesktopAuth()
        AccountContext.activeAccountId = account
        // 这一端真正的数据源：与「我的课表」「首页」同一份键值文件
        val cache = DesktopScheduleHost(auth).cache(account)
        ScheduleCache.writeCurrentTerm(cache, term)
        ScheduleCache.writeOptimizedCourses(cache, term, myCourses())
        ScheduleCache.writeExams(cache, term, listOf(exam("FIX-PHY", "大学物理（夹具）", "2027-01-08")))

        val host = DesktopMatchHost(auth)
        val local = MatchData.read(host, null)
        assertEquals(term, local.term, "本学期应当从本端课表缓存读出来")
        assertEquals(2, local.courses.size, "夹具那两门课应当在：${local.courses.map { it.courseName }}")
        assertEquals(1, local.exams.size)
        assertTrue(local.hasAnything, "有课表缓存 ⇒ 这一屏不是空的")

        // 自己的码：真编一段（jvm 上 deflate / Base64 两条缝都在）
        val mine = MatchProfile.build(local, nickname = "我（夹具）")
        val myCode = MatchProfile.encode(mine)
        assertTrue(myCode.isNotEmpty(), "桌面端有 deflate 与 Base64，不该出空码")
        assertEquals(mine, MatchProfile.decode(myCode), "自己的码解得回来")

        // 对方的码：同一套算法在别处编出来的夹具档案 —— 屏上收到的是**一段字符串**，这里也照这条路走
        val theirsCode = MatchProfile.encode(MatchProfile.build(theirLocal(), nickname = "小张（夹具）"))
        val decoded = assertNotNull(MatchProfile.decode(theirsCode), "对方的码应当解得开")

        val r = MatchProfile.compare(mine, decoded)
        assertTrue(r.scored, "两边都分享了课表 ⇒ 应当算得出分（不是只列交集）")
        assertTrue(r.overall > 0, "夹具两份课表有共同空闲，不该是 0 分")

        // 同课（课程号相同、课名带出来）
        assertEquals(
            listOf("大学物理（夹具）"),
            assertNotNull(r.discoveries.firstOrNull { "一起上" in it.title }, "应当有「一起上」那一条").items,
        )
        // 同楼：周三 5-6 节都在中2 ⇒ 一段擦肩而过
        assertEquals(1, r.encounters.size, "同楼那一段应当算出来一次")
        assertEquals("中2", r.encounters.single().building)
        assertEquals(2, r.encounters.single().day)
        assertEquals(5, r.encounters.single().from)
        // 同场考试（同课程号同一天）
        assertEquals(
            listOf("大学物理（夹具） · 1 月 8 日"),
            assertNotNull(r.discoveries.firstOrNull { "撞在同一天" in it.title }).items,
        )
        // 谁先考完：我这门 1/8，对方最晚 1/11
        assertTrue(
            r.discoveries.any { "考完" in it.title },
            "应当算出「谁先考完」那条：${r.discoveries.map { it.title }}",
        )
        // 缓存那一层真落在本端文件里（不是只躺在内存里）
        val file = File(
            requireNotNull(dataRootOverride),
            "${DesktopScheduleHost.SCHEDULE_CACHE_STORE}${AccountContext.suffixFor(account)}.properties",
        )
        assertTrue(file.isFile, "课表族缓存应当落在本端键值文件里：${file.absolutePath}")
    }

    @Test
    fun `如实降级：学工档案与校园卡消费这一端没有，分享码里就不会出现它们`() = withTempDataRoot {
        val auth = DesktopAuth()
        AccountContext.activeAccountId = account
        val cache = DesktopScheduleHost(auth).cache(account)
        ScheduleCache.writeCurrentTerm(cache, term)
        ScheduleCache.writeOptimizedCourses(cache, term, myCourses())

        val host = DesktopMatchHost(auth)
        // 宿主面这一层就说清了「没有」——屏上那两维因此是禁用 + 写明原因
        assertNull(host.identity(), "桌面没有学工系统档案（HelloProfileStore 是 :app 的实现）")
        assertTrue(host.transactions().isEmpty(), "桌面没有校园卡消费快照（CampusCardCache 是 :app 的）")

        val local = MatchData.read(host, null)
        assertNull(local.identity)
        assertTrue(local.diningHourCounts.isEmpty())
        assertTrue(local.canteens.isEmpty())
        assertTrue(local.hasAnything, "课表还在 ⇒ 这一屏仍有东西可分享")

        // 「开关开着但没数据」不会进码：身份与吃那两维在分享码里是空的。
        val dims = MatchProfile.Dimensions(
            textbooks = true, diningHours = true, canteens = true, dietTags = true,
        )
        val p = MatchProfile.build(local, nickname = "我（夹具）", dietTags = setOf("辣"), dims = dims)
        assertEquals(0, p.grade)
        assertEquals("", p.campus)
        assertEquals("", p.profession)
        assertEquals("", p.province)
        assertEquals("", p.diningHours)
        assertTrue(p.canteens.isEmpty())
        assertEquals(setOf("辣"), p.dietTags, "自己写的口味不依赖本机数据源，照旧进码")
        // 课表那一半照旧是真的
        assertTrue(p.busyGrid.isNotEmpty())
        assertEquals(2, p.courses.size)
    }
}
