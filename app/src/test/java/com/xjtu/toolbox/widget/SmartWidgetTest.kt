package com.xjtu.toolbox.widget

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.schedule.SlotAttendanceMarks
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.schedule.attendanceMarksOf
import com.xjtu.toolbox.widget.smart.SmartArbiter
import com.xjtu.toolbox.widget.smart.SmartBlock
import com.xjtu.toolbox.widget.smart.SmartEvidence
import com.xjtu.toolbox.widget.smart.SmartFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能卡片上课那一屏：本堂考勤状态直接摆在卡片上，并且**说清它是什么时候的事实**。
 *
 * 2026-11-18 的反馈定了口径：没记录不能一律说「未记录」——课前 / 刚上课时学校本来还没到
 * 出结论的时候，那是「待考勤」；请假单独说。2026-11-19 又补了一条更要紧的：
 * **「迟到」只在查证过的前提下说**，没查过或太久没查只能说「可能迟到」（`docs/smart-widget.md` §1.4）。
 */
class SmartWidgetTest {

    /** 第 3-4 节（冬夏两套表都是 10:10–12:00），打卡窗口 10:15 关。 */
    private val course = WidgetCourse(
        name = "高等数学", location = "主楼A-202",
        startMinute = 610, endMinute = 720, color = 0,
        startSection = 3, endSection = 4,
    )

    private val day = "2026-11-17"

    private fun marks(status: WaterType, name: String = "高等数学", code: String = ""): SlotAttendanceMarks =
        attendanceMarksOf(
            listOf(
                AttendanceWaterRecord(
                    sbh = "1", startTime = 3, endTime = 4, week = 6, date = day,
                    courseName = name, courseCode = code, status = status,
                ),
            ),
        )

    private fun facts(
        nowMinute: Int,
        attendance: SlotAttendanceMarks? = null,
        swipe: LocalSwipe? = null,
        ago: Int? = null,
        at: String? = null,
    ) = SmartFacts(
        nowMinute = nowMinute,
        courses = listOf(course),
        attendance = attendance,
        date = day,
        swipe = swipe,
        checkedAgoMin = ago,
        checkedAtClock = at,
    )

    private fun block(
        nowMinute: Int,
        attendance: SlotAttendanceMarks? = null,
        swipe: LocalSwipe? = null,
        ago: Int? = null,
        at: String? = null,
    ): SmartBlock = SmartArbiter.card(facts(nowMinute, attendance, swipe, ago, at)).block

    @Test
    fun `有记录就照实报，且是定论`() {
        assertEquals("已打卡 · 正常", block(620, marks(WaterType.NORMAL)).status)
        assertEquals("已打卡 · 迟到", block(620, marks(WaterType.LATE)).status)
        assertEquals("已打卡 · 缺勤", block(700, marks(WaterType.ABSENCE)).status)
        // 请假单独说，不带「打卡」——人根本没来，打卡这个词会误导
        assertEquals("已请假", block(620, marks(WaterType.LEAVE)).status)
        assertEquals(SmartEvidence.RECORDED, block(620, marks(WaterType.NORMAL)).evidence)
        // 学校写下的结论不会自己变，不用标时刻
        assertNull(block(620, marks(WaterType.NORMAL), ago = 30).meta)
    }

    @Test
    fun `官方打卡窗口关之前是待考勤`() {
        // 第 3 节 10:10 上课，attendanceEnd 是 10:15
        assertEquals("待考勤", block(610).status)
        assertEquals("待考勤", block(614).status)
        assertEquals(SmartEvidence.INFERRED, block(611).evidence)
    }

    @Test
    fun `窗口关了又没查过，只能说可能迟到`() {
        // 没查过就硬下结论，等于替学校盖章，而中间用户很可能补刷了
        val b = block(616)
        assertEquals("可能迟到", b.status)
        assertEquals("还未查询过", b.meta)
        assertEquals(SmartEvidence.INFERRED, b.evidence)
    }

    @Test
    fun `临界点上查证过才直说迟到`() {
        val b = block(620, ago = 3, at = "10:13")
        assertEquals("迟到", b.status)
        assertEquals("10:13 查过", b.meta)
        assertEquals(SmartEvidence.VERIFIED, b.evidence)
        // 状态行拼成一句话：这是卡片上真正画出来的那句
        assertEquals("迟到 · 10:13 查过", b.statusLine)
    }

    @Test
    fun `超过十分钟没再查就自动降级措辞`() {
        val b = block(640, ago = 25, at = "10:12")
        assertEquals("可能迟到", b.status)
        assertEquals("上次查于 10:12", b.meta)
        assertEquals(SmartEvidence.STALE, b.evidence)
        // 但屏幕上永远有一句说得清的话：不是不显示，是换个说法
        assertEquals("可能迟到 · 上次查于 10:12", b.statusLine)
    }

    @Test
    fun `查证时刻没给就按上次查的分钟推`() {
        // 没有 checkedAtClock（老缓存 / 跨天）时不能空着，按 ago 反推：10:20 减 3 分钟
        assertEquals("10:17 查过", block(620, ago = 3, at = null).meta)
    }

    @Test
    fun `刷卡流水里有记录就先报已刷卡，标的是刷卡时刻`() {
        // 考勤结果要等结算，但刷卡流水是实时的：这段时间里刷过，就不该继续催人去补刷
        val swipe = LocalSwipe(minuteOfDay = 9 * 60 + 58, location = "主楼A-202")
        val b = block(620, attendance = null, swipe = swipe)
        assertEquals("已刷卡 · 待结算", b.status)
        // 标「刷卡 09:58」而不是「刚刚查过」：两者含义不同（§10.3）
        assertEquals("刷卡 09:58", b.meta)
        assertEquals(SmartEvidence.OBSERVED, b.evidence)
    }

    @Test
    fun `刷卡时间落在本节课窗口外就不算这节课的`() {
        val morning = LocalSwipe(minuteOfDay = 8 * 60 + 10)
        assertEquals("迟到", block(620, swipe = morning, ago = 2).status)
        val evening = LocalSwipe(minuteOfDay = 20 * 60)
        assertEquals("迟到", block(620, swipe = evening, ago = 2).status)
    }

    @Test
    fun `课前那一屏写地点，上课中改写还剩多久`() {
        // 细节的重要性随情境变（§2.4 规则②）：课前得赶过去，上课中人已经在里面了
        val before = block(595)
        assertEquals("15 分钟后上课", before.label)
        assertEquals("主楼A-202", before.detail)

        val during = block(650)
        assertEquals("上课中", during.label)
        assertEquals("还剩 70 分钟", during.detail)
    }

    @Test
    fun `按日期找记录，不受周次口径影响`() {
        // 同一天同一节次、但不是这门课：不能被认成自己的记录
        assertEquals("迟到", block(620, marks(WaterType.NORMAL, name = "大学物理"), ago = 1).status)
    }

    @Test
    fun `认课优先用课程号，课名写法不一致也认得出`() {
        val coded = course.copy(name = "高等数学(甲)", courseCode = "MATH100101-05")
        val facts = SmartFacts(
            nowMinute = 620, courses = listOf(coded), date = day,
            attendance = marks(WaterType.NORMAL, name = "高等数学（甲）", code = "MATH100101-05"),
        )
        assertEquals("已打卡 · 正常", SmartArbiter.card(facts).block.status)
    }

    @Test
    fun `官方打卡窗口恒等于上课时刻加五分钟`() {
        // 卡片引用的是 XjtuTime 的 attendanceEnd，不是自己写死的魔数；这条不变量塌了，
        // 「迟到了没」这个判断就会整体错位
        for (summer in listOf(false, true)) {
            for (section in 1..11) {
                val t = XjtuTime.getClassTime(section, summer)!!
                val start = t.start.hour * 60 + t.start.minute
                val end = t.attendanceEnd.hour * 60 + t.attendanceEnd.minute
                assertEquals("第 $section 节（summer=$summer）", 5, end - start)
            }
        }
    }

    @Test
    fun `窗口关掉的那一刻从作息表取，不从魔数取`() {
        assertEquals(615, SmartArbiter.attendanceEndMinute(course))
        // 自建日程没有节次：退回「上课 + 5 分钟」
        assertEquals(605, SmartArbiter.attendanceEndMinute(course.copy(startSection = 0, startMinute = 600)))
    }

    @Test
    fun `刷卡时刻解析`() {
        assertEquals(14 * 60 + 14, minuteOfDay("2026-11-18 14:14:37"))
        assertEquals(8 * 60 + 5, minuteOfDay("08:05"))
        assertNull(minuteOfDay(""))
        assertNull(minuteOfDay("乱麻"))
        assertNull(minuteOfDay("99:99"))
    }

    @Test
    fun `没有考勤缓存时依旧给结论，只是带查证时刻`() {
        val b = block(630, attendance = null, ago = 120, at = "08:30")
        assertEquals("可能迟到", b.status)
        assertTrue("没有缓存不代表没有结论", b.statusLine!!.contains("上次查于"))
    }
}
