package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.withJwxtLogin
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `:data` 的 `JwxtScheduleApi`（三端唯一的课表取数）对着 `:testkit` 的 `JwxtFakeUpstream` 逐字段读。 */
class JwxtScheduleApiJvmTest {

    private fun apiUnder(site: com.xjtu.toolbox.auth.SiteSession) = JwxtScheduleApi(site)

    // ── rows()：serve 契约面，排课原样 ──────────────────────────────

    @Test
    fun `rows 给的是排课原样：三行、缺键空串、数字按文本投影`() {
        withJwxtLogin { site, fake ->
            val rows = runBlocking { apiUnder(site).rows(JwxtFakeUpstream.TERM_NEW) }

            assertEquals(3, rows.size, "夹具给三行，排课原样就该回三行")
            val first = rows[0]
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_COURSE, first.courseName)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_TEACHER, first.teacher)
            // 调课前的教室：`rows` 不合调停补课，这一格必须是夹具里那个旧教室
            assertEquals(JwxtFakeUpstream.CHANGE_OLD_ROOM, first.classroom)
            // `SKXQ`/`KSJC`/`JSJC` 在夹具里是 JSON 数字 ⇒ 读成文本
            assertEquals("${JwxtFakeUpstream.SCHEDULE_1_DAY}", first.dayOfWeek)
            assertEquals("${JwxtFakeUpstream.SCHEDULE_1_START}", first.startSection)
            assertEquals("${JwxtFakeUpstream.SCHEDULE_1_END}", first.endSection)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_WEEKS, first.weeksText)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_JXBID, first.classId)

            // 第三行只有 KCM：其余四格是空串，不是 null 也不是 0
            val third = rows[2]
            assertEquals(JwxtFakeUpstream.SCHEDULE_3_COURSE, third.courseName)
            assertEquals("", third.teacher)
            assertEquals("", third.classroom)
            assertEquals("", third.dayOfWeek)

            // 请求侧：`XNXQDM=<学期>` 真发出去了（少了它问的就是别的东西）
            assertTrue(
                fake.jwxt.lastScheduleForm?.contains("XNXQDM=") == true,
                "xskcb.do 应带 XNXQDM：${fake.jwxt.lastScheduleForm}",
            )
        }
    }


    @Test
    fun `schedule 把调课合进课表：原行挖掉那一周、新位置另起一条`() {
        withJwxtLogin { site, fake ->
            val merged = runBlocking { apiUnder(site).schedule(JwxtFakeUpstream.TERM_NEW) }

            // 3 行原始排课 + 1 条调出来的新位置 = 4 条
            assertEquals(4, merged.courses.size, "调课应当表现为「原行拆掉一周 + 新位置多一条」")

            val moved = merged.courses.single { it.location == JwxtFakeUpstream.CHANGE_NEW_ROOM }
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_COURSE, moved.courseName)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_DAY, moved.dayOfWeek)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_START, moved.startSection)
            assertEquals(JwxtFakeUpstream.CHANGE_BITS, moved.weekBits)
            assertEquals(
                1,
                moved.weekBits.count { it == '1' },
                "调出来的那条只该在被调的那一周",
            )

            // 原教室那条还在，但那一周被挖掉了：16 位里恰好少一位，剩下 15 个 1
            val original = merged.courses.single { it.location == JwxtFakeUpstream.CHANGE_OLD_ROOM }
            assertEquals(15, original.weekBits.count { it == '1' }, "原行应当只剩 15 周")
            assertEquals(
                '0',
                original.weekBits[JwxtFakeUpstream.CHANGE_WEEK - 1],
                "被调走的那一周应当从原位串里清零",
            )
            // 合并结果与「排课原样」是两个不同的东西 —— 这条是防止将来有人把两者之一改了
            assertEquals(3, runBlocking { apiUnder(site).rows(JwxtFakeUpstream.TERM_NEW) }.size)

            // 两条请求都真发过
            assertTrue(fake.jwxt.scheduleCalls.get() >= 1, "schedule() 要拉一次 xskcb.do")
            assertTrue(fake.jwxt.changesCalls.get() >= 1, "schedule() 要拉一次 xsdkkc.do")
            assertTrue(
                fake.jwxt.lastChangesForm?.contains("XNXQDM=") == true,
                "xsdkkc.do 应带 XNXQDM：${fake.jwxt.lastChangesForm}",
            )
        }
    }

    @Test
    fun `changes 给的是给人看的那一份：一条 MOVED，工号已切掉`() {
        withJwxtLogin { site, _ ->
            val events = runBlocking { apiUnder(site).changes(JwxtFakeUpstream.TERM_NEW) }

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(ScheduleChangeEvent.Kind.MOVED, event.kind)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_COURSE, event.courseName)
            // `YSKJS` 是 "姓名/工号"，解析只留姓名
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_DAY, event.fromDay)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_START, event.fromStartSection)
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_END, event.fromEndSection)
            assertEquals(JwxtFakeUpstream.CHANGE_NEW_ROOM, event.toLocation)
            assertEquals(listOf(JwxtFakeUpstream.CHANGE_WEEK), event.weeks)
            assertEquals(listOf(JwxtFakeUpstream.CHANGE_WEEK), event.toWeeks)
        }
    }

    // ── termStart()：serve 契约面，兼记总周数 ────────────────────────

    @Test
    fun `termStart 取日期那一段与总周数，并记进 termWeeksOf`() {
        withJwxtLogin { site, fake ->
            val api = apiUnder(site)
            val start = runBlocking { api.termStart(JwxtFakeUpstream.TERM_NEW) }

            assertEquals(JwxtFakeUpstream.TERM_NEW, start.term)
            // 夹具给到秒 ⇒ 只取日期那一段
            assertEquals(JwxtFakeUpstream.TERM_START_DATE, start.startDate)
            assertEquals(JwxtFakeUpstream.TERM_TOTAL_WEEKS, start.totalWeeks)
            // 顺带记下来了：`saveTermStart` 与桌面小组件都要这一格
            assertEquals(JwxtFakeUpstream.TERM_TOTAL_WEEKS, api.termWeeksOf(JwxtFakeUpstream.TERM_NEW))
            // 学期号切成 XN + XQ 两格发出去了
            assertTrue(
                fake.jwxt.lastTermStartForm?.contains("XN=") == true &&
                    fake.jwxt.lastTermStartForm?.contains("XQ=") == true,
                "cxjcs.do 应带 XN/XQ：${fake.jwxt.lastTermStartForm}",
            )
            // `termStartDate` 是它的日期那半（只收日期、不看周数的调用方用）
            assertEquals(
                JwxtFakeUpstream.TERM_START_DATE,
                runBlocking { api.termStartDate(JwxtFakeUpstream.TERM_NEW) }.toString(),
            )
        }
    }

    @Test
    fun `termStart 碰到不是学期号的入参直接抛`() {
        withJwxtLogin { site, _ ->
            val api = apiUnder(site)
            assertFailsWith<IllegalStateException> { runBlocking { api.termStart("2026") } }
            assertTrue(isTermCode(JwxtFakeUpstream.TERM_NEW))
            assertTrue(!isTermCode("2026-2027"))
        }
    }

    // ── currentTerm() / termList() ────────────────────────────────

    @Test
    fun `currentTerm 打的是 wdkb 那条端点、termList 走全校课表应用`() {
        withJwxtLogin { site, fake ->
            val api = apiUnder(site)
            assertEquals(JwxtFakeUpstream.TERM_NEW, runBlocking { api.currentTerm() })
            assertTrue(
                fake.jwxt.wdkbCurrentTermCalls.get() >= 1,
                "currentTerm 应打 wdkb 自己的 dqxnxq.do",
            )

            // 学期名从 `MC` 记下来（`ScheduleTermStore` 那一份的来源）
            assertEquals(JwxtFakeUpstream.TERM_NEW_NAME, api.termNames()[JwxtFakeUpstream.TERM_NEW])

            val terms = runBlocking { api.termList() }
            assertTrue(JwxtFakeUpstream.TERM_NEW in terms, "学期列表应当含夹具那个学期：$terms")
            assertTrue(JwxtFakeUpstream.TERM_BARE in terms, "只有 DM 的那一行也要算一个学期：$terms")
        }
    }

    // ── examSchedule() ───────────────────────────────────────────

    @Test
    fun `examSchedule 把日期与时段从两格里剥干净`() {
        withJwxtLogin { site, fake ->
            val exams = runBlocking { apiUnder(site).examSchedule(JwxtFakeUpstream.TERM_NEW) }

            assertEquals(1, exams.size)
            val exam = exams.single()
            assertEquals(JwxtFakeUpstream.EXAM_COURSE, exam.courseName)
            assertEquals(JwxtFakeUpstream.EXAM_CODE, exam.courseCode)
            // `KSRQ` = "<日期> 00:00:00" ⇒ 只留日期
            assertEquals(JwxtFakeUpstream.EXAM_DATE, exam.examDate)
            // `KSSJMS` = "<日期> 14:30-16:30" ⇒ 日期那一截剥掉，只留时段
            assertEquals(JwxtFakeUpstream.EXAM_TIME, exam.examTime)
            assertEquals(JwxtFakeUpstream.EXAM_ROOM, exam.location)
            assertEquals(JwxtFakeUpstream.EXAM_SEAT, exam.seatNumber)

            assertTrue(
                fake.jwxt.lastExamForm?.contains("XNXQDM=") == true &&
                    fake.jwxt.lastExamForm?.contains("*order=") == true,
                "wdksap.do 应带 XNXQDM 与 *order：${fake.jwxt.lastExamForm}",
            )
        }
    }

    // ── 夹具自检：URL 漂了的话上面这些断言测的就不是真协议 ─────────

    @Test
    fun `夹具端点与生产实现打的是同一个 wdkb URL`() {
        assertEquals(
            "https://jwxt.xjtu.edu.cn/jwapp/sys/wdkb/modules/xskcb/xskcb.do",
            JwxtFakeUpstream.SCHEDULE_URL,
        )
        assertEquals(
            "https://jwxt.xjtu.edu.cn/jwapp/sys/wdkb/modules/xskcb/xsdkkc.do",
            JwxtFakeUpstream.CHANGES_URL,
        )
        assertEquals(
            "https://jwxt.xjtu.edu.cn/jwapp/sys/wdkb/modules/jshkcb/cxjcs.do",
            JwxtFakeUpstream.TERM_START_URL,
        )
    }
}
