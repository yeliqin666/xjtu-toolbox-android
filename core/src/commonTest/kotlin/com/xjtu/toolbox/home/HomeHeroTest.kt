package com.xjtu.toolbox.home

import com.xjtu.toolbox.schedule.CUSTOM_COURSE_CODE_PREFIX
import com.xjtu.toolbox.schedule.CourseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Hero 的两项本地推算（`:core` 的 [HomeHero]）—— 从 `:app` 的 `main/HomeTab.kt` 里那段内联的
 * `LaunchedEffect` 搬进来时判定一行未改（只换了日期类型），所以这里钉的是那几条判定：
 *
 * - 15 天窗口、周次从开学日期算、同一天里按开始时间排序；
 * - **放假停的是教务排的课，自建日程照常提醒**（`isUserCreated`）；
 * - 开学日期算不出来（null）时返回 null —— 那是「算不出来」，与「这两周真没课」不是一回事；
 * - 教学周只在 1..25 之间才算数。
 *
 * 改 `:core` 的 [HomeHero] 之前先看这里。
 */
class HomeHeroTest {

    /** 2026 秋季学期开学日：周一（教务下发的 XQKSRQ 就是周一）。 */
    private val termStart = LocalDate(2026, 9, 14)

    /** 高等数学：周一第 1-2 节、1-16 周、主楼A-101 —— 与 `:testkit` 的夹具同形。 */
    private val math = CourseItem(
        courseName = "高等数学（上）",
        teacher = "示例甲",
        location = "主楼A-101",
        weekBits = "1111111111111111",
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
    )

    private fun custom(startMinute: Int, endMinute: Int, name: String = "自修", location: String = "图书馆") = CourseItem(
        courseName = name,
        location = location,
        weekBits = "1111111111111111",
        dayOfWeek = 1,
        startSection = 0,
        endSection = 0,
        courseCode = CUSTOM_COURSE_CODE_PREFIX + "self-study",
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
    )

    @Test
    fun `今天那节课已经上过就跳到下周一`() {
        val next = HomeHero.find(
            courses = listOf(math),
            startOfTerm = termStart,
            now = LocalDateTime(2026, 10, 12, 10, 0),
        )
        assertEquals("高等数学（上）", next?.title)
        assertEquals("08:00", next?.timeLabel)
        assertEquals("至 09:50", next?.timeCaption, "第 2 节结束在 09:50（10 月是冬季作息）")
        assertEquals("6天22小时后", next?.eta)
        assertEquals("10月19日 · 主楼A-101", next?.detail, "今天那节已经过了 ⇒ 报的是下周一那节")
        assertEquals(LocalDateTime(2026, 10, 19, 8, 0), next?.startAt)
    }

    @Test
    fun `今天还没上的那节就是下一项`() {
        val next = HomeHero.find(
            courses = listOf(math),
            startOfTerm = termStart,
            now = LocalDateTime(2026, 10, 12, 7, 0),
        )
        assertEquals("08:00", next?.timeLabel)
        assertEquals("1小时后", next?.eta)
        assertEquals("主楼A-101", next?.detail, "当天不出日期前缀")
        assertEquals(LocalDateTime(2026, 10, 12, 8, 0), next?.startAt)
    }

    /** 放假停的是**教务排的课**；自建日程不受那一张节假日表影响（与搬迁前逐字一致）。 */
    @Test
    fun `节假日停教务的课，自建日程照常提醒`() {
        val holiday = setOf(LocalDate(2026, 10, 19))
        val apiOnly = HomeHero.find(
            courses = listOf(math),
            startOfTerm = termStart,
            holidayDates = holiday,
            now = LocalDateTime(2026, 10, 12, 10, 0),
        )
        assertEquals("10月26日 · 主楼A-101", apiOnly?.detail, "10-19 放假 ⇒ 教务的课顺延一周")

        val withCustom = HomeHero.find(
            courses = listOf(math, custom(startMinute = 630, endMinute = 660, name = "自修", location = "图书馆")),
            startOfTerm = termStart,
            holidayDates = holiday,
            now = LocalDateTime(2026, 10, 12, 11, 0),
        )
        assertEquals("自修", withCustom?.title, "自建日程在假日里照常，而且排在教务那节之后")
        assertEquals("10:30", withCustom?.timeLabel)
        assertEquals("至 11:00", withCustom?.timeCaption)
        assertEquals("10月19日 · 图书馆", withCustom?.detail)
    }

    @Test
    fun `一天里按开始时间排序`() {
        // 两条都在今天：教务 08:00、自建 10:30 ⇒ 早的那一条是下一项（与列表顺序无关）
        val next = HomeHero.find(
            courses = listOf(custom(startMinute = 630, endMinute = 660), math),
            startOfTerm = termStart,
            now = LocalDateTime(2026, 10, 12, 7, 0),
        )
        assertEquals("高等数学（上）", next?.title)
        assertEquals("08:00", next?.timeLabel)
        assertNull(
            HomeHero.find(
                courses = listOf(custom(startMinute = 630, endMinute = 660)),
                startOfTerm = termStart,
                now = LocalDateTime(2026, 10, 12, 11, 0),
                days = 1,
            ),
            "窗口只有今天，而今天那一条已经开始了",
        )
    }

    @Test
    fun `开学日期算不出来时返回 null —— 那不是「真没课」`() {
        assertNull(
            HomeHero.find(
                courses = listOf(math),
                startOfTerm = null,
                now = LocalDateTime(2026, 10, 12, 10, 0),
            )
        )
    }

    @Test
    fun `这两周真的都没课`() {
        assertNull(
            HomeHero.find(
                courses = listOf(math.copy(weekBits = "1000000000000000")),
                startOfTerm = termStart,
                now = LocalDateTime(2026, 10, 12, 10, 0),
            )
        )
    }

    @Test
    fun `教学周只在 1 到 25 之间才算数`() {
        assertEquals(5, HomeHero.weekNumber(termStart, LocalDate(2026, 10, 12)))
        assertEquals(1, HomeHero.weekNumber(termStart, termStart))
        assertEquals(0, HomeHero.weekNumber(termStart, LocalDate(2026, 9, 7)), "开学前一周")
        assertEquals(0, HomeHero.weekNumber(termStart, LocalDate(2027, 5, 1)), "学期早已结束")
        assertEquals(0, HomeHero.weekNumber(null, LocalDate(2026, 10, 12)))
    }

    @Test
    fun `时分与倒计时的说法`() {
        assertEquals("08:00", HomeHero.clock(8 * 60))
        assertEquals("00:00", HomeHero.clock(-1))
        assertEquals("24:00", HomeHero.clock(24 * 60))
        assertEquals("即将开始", HomeHero.eta(0))
        assertEquals("30分钟后", HomeHero.eta(30))
        assertEquals("1小时后", HomeHero.eta(60))
        assertEquals("1小时30分钟后", HomeHero.eta(90))
        assertEquals("1天后", HomeHero.eta(24 * 60))
        assertEquals("6天22小时后", HomeHero.eta(6 * 24 * 60 + 22 * 60))
    }

    @Test
    fun `日期那一行按远近改说法`() {
        val today = LocalDate(2026, 10, 12)
        assertEquals("今天", HomeHero.dateLabel(today, today))
        assertEquals("明天", HomeHero.dateLabel(today.plusDays(1), today))
        assertEquals("后天", HomeHero.dateLabel(today.plusDays(2), today))
        assertEquals("周六", HomeHero.dateLabel(LocalDate(2026, 10, 17), today), "3..6 天说星期几")
        assertEquals("10月19日", HomeHero.dateLabel(today.plusDays(7), today))
    }

    private fun LocalDate.plusDays(days: Int): LocalDate =
        com.xjtu.toolbox.schedule.civilFromEpochDay(toEpochDays().toLong() + days)
}
