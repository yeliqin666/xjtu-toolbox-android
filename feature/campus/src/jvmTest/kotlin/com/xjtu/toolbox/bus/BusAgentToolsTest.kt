package com.xjtu.toolbox.bus

import com.xjtu.toolbox.agent.ToolReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class BusAgentToolsTest {
    private val day = LocalDate(2026, 11, 4)

    private fun run(time: String, line: String, vararg stops: String, used: Int = 0) = BusRun(
        lineName = line, time = time, category = "创新港班车", seats = 49, usedSeats = used, plate = "陕A1",
        from = stops.first(), to = stops.last(), stops = stops.map { BusStop(it) },
    )

    private val runs = listOf(
        run("07:30", "3号线（去）", "思源中心", "创新港", used = 12),
        run("18:15", "3号线（回）", "创新港", "思源中心"),
    )

    private class Fake(val runs: List<BusRun>, val fail: Boolean = false) : BusSource {
        val asked = mutableListOf<LocalDate>()
        override suspend fun runs(date: LocalDate): List<BusRun> {
            asked += date
            if (fail) error("断网")
            return runs
        }
    }

    private fun tools(source: BusSource, now: String = "12:00") = BusAgentTools(source, today = { day }, nowHhmm = { now })

    @Test fun todayHidesDepartedRunsAndSaysSo() = runTest {
        val reply = tools(Fake(runs)).execute("get_campus_bus", "{}")
        assertTrue("18:15" in reply)
        assertFalse("07:30" in reply)
        assertTrue("departed_hidden: 1" in reply)
    }

    @Test fun includeDepartedBringsThemBack() = runTest {
        assertTrue("07:30" in tools(Fake(runs)).execute("get_campus_bus", """{"include_departed":true}"""))
    }

    @Test fun otherDaysShowEverything() = runTest {
        val source = Fake(runs)
        val reply = tools(source).execute("get_campus_bus", """{"date":"明天"}""")
        assertEquals(LocalDate(2026, 11, 5), source.asked.single())
        assertTrue("07:30" in reply && "18:15" in reply)
    }

    @Test fun directionIsRespected() = runTest {
        val reply = tools(Fake(runs)).execute("get_campus_bus", """{"date":"明天","from":"兴庆","to":"创新港"}""")
        assertTrue("3号线（去）" in reply)
        assertFalse("3号线（回）" in reply)
    }

    @Test fun replyTellsStudentsNotToBook() = runTest {
        val reply = tools(Fake(runs)).execute("get_campus_bus", """{"date":"明天"}""")
        assertTrue(BusRules.BOARDING_TIP in reply)
        assertTrue("余量：37/49" in reply)
        assertTrue(reply.endsWith(ToolReply.EXTERNAL_DATA))
    }

    @Test fun badDateAndNetworkFailureAreReported() = runTest {
        assertTrue(tools(Fake(runs)).execute("get_campus_bus", """{"date":"下周"}""").startsWith("error: bad_format"))
        assertTrue(tools(Fake(runs, fail = true)).execute("get_campus_bus", "{}").startsWith("error: failed"))
    }

    @Test fun emptyResultDistinguishesAllDeparted() = runTest {
        assertTrue("departed_hidden: 2" in tools(Fake(runs), now = "23:00").execute("get_campus_bus", "{}"))
        assertTrue(tools(Fake(emptyList())).execute("get_campus_bus", "{}").startsWith("result: empty"))
    }
}
