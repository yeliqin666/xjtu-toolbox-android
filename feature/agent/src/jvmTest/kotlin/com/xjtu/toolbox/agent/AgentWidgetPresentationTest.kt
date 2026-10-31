package com.xjtu.toolbox.agent

import com.xjtu.toolbox.emptyroom.LiveRoom
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.card.CardInfo
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.util.AppJson
import kotlin.test.*
import kotlinx.serialization.json.jsonObject

class AgentWidgetPresentationTest {
    @Test fun presentationSchemaIncludesAllSupportedViews() {
        val views = AgentWidgetPresentation.schema()["properties"]!!.jsonObject["view"]!!.jsonObject["enum"].toString()
        listOf("slots", "status", "both", "balance", "summary", "transactions", "plan", "booking", "places", "route")
            .forEach { assertTrue("\"$it\"" in views) }
    }
    private fun display(widget: AgentWidget, json: String): AgentWidget =
        AgentWidgetPresentation.apply(listOf(widget), AppJson.parseToJsonElement(json).jsonObject).single()

    private val grades = GradeWidget(listOf(
        ReportedGrade("甲", 1.0, "90", 4.0),
        ReportedGrade("乙", 3.0, "80", 3.0),
        ReportedGrade("丙", 2.0, "通过"),
    ))

    @Test fun selectedGpaUsesAllSelectedCoursesBeforeVisualLimit() {
        val selected = display(grades, """{"rows":[2,1],"limit":1,"summary":"selected"}""") as GradeWidget
        assertEquals(listOf("乙", "甲"), selected.grades.map { it.courseName })
        assertEquals(3.25, selected.gpa)
        assertEquals(4.0, selected.totalPoints)
        assertEquals(1, selected.display.limit)
        assertEquals("selected", selected.display.summary)
        assertEquals(selected, storedToWidget(selected.toStored()))
    }

    @Test fun allAndSelectedSummaryHaveDifferentScope() {
        val all = display(grades, """{"rows":[1],"summary":"all"}""") as GradeWidget
        val one = display(grades, """{"rows":[1],"summary":"selected"}""") as GradeWidget
        assertEquals(3.25, all.gpa)
        assertEquals(4.0, one.gpa)
        assertEquals(1.0, one.totalPoints)
    }

    @Test fun invalidOrEmptySelectionCannotShowUnrelatedRows() {
        assertFailsWith<IllegalArgumentException> { display(grades, """{"rows":[0]}""") }
        assertFailsWith<IllegalArgumentException> { display(grades, """{"rows":[8]}""") }
        assertTrue((display(grades, """{"rows":[]}""") as GradeWidget).grades.isEmpty())
    }

    @Test fun roomStripeFiltersUseEveryRequestedSectionAndContinuousVacancy() {
        val rooms = RoomWidget(rooms = listOf(
            RoomInfo("A", 40, listOf(0, 0, 1, 0)),
            RoomInfo("B", 80, listOf(0, 0, 0, 1)),
            RoomInfo("C", 100, listOf(0, 1, 0, 1)),
        ))
        val result = display(rooms, """{"sections":[1,2],"min_free_sections":3,"view":"slots"}""") as RoomWidget
        assertEquals(listOf("B"), result.rooms.map { it.name })
        assertEquals(listOf(1, 2), result.display.sections)
        assertEquals("slots", result.display.view)
        assertEquals(-1, result.currentPeriod)
    }

    @Test fun liveSnapshotCannotInventAnAllDayStripe() {
        val live = LiveRoomWidget(rooms = listOf(LiveRoom("A", status = 2), LiveRoom("B", status = 1, people = 3)))
        assertFailsWith<IllegalArgumentException> { display(live, """{"view":"slots"}""") }
        assertFailsWith<IllegalArgumentException> { display(live, """{"fields":["slots","status"]}""") }
        assertFailsWith<IllegalArgumentException> { display(live, """{"sort":"free_desc"}""") }
        val free = display(live, """{"view":"status","statuses":["free"]}""") as LiveRoomWidget
        assertEquals(listOf("A"), free.rooms.map { it.name })
        assertTrue(AgentWidgetPresentation.catalog(listOf(live)).contains("slots: unavailable"))
        val receipt = AgentWidgetPresentation.receipt(listOf(free))
        assertTrue(receipt.contains("view=status; slots=unavailable"))
        assertTrue(receipt.contains("只代表此刻状态"))
    }

    @Test fun sectionConditionsAlwaysQueryTimetable() {
        fun request(json: String) = agentRoomRequest(AppJson.parseToJsonElement(json).jsonObject)
        assertEquals("slots", request("""{"sections":[5,6],"min_free_sections":4}""").view)
        assertEquals("slots", request("""{"display":{"fields":["slots"]}}""").view)
        assertEquals("both", request("""{"display":{"fields":["slots","status"]}}""").view)
        assertEquals("slots", request("""{"section":5}""").view)
        assertEquals("status", request("""{"view":"status"}""").view)
        assertFailsWith<IllegalArgumentException> { request("""{"view":"status","sections":[5]}""") }
        assertFailsWith<IllegalArgumentException> { request("""{"min_free_sections":0}""") }
    }

    @Test fun noticePicksKeepOriginalTitlesAndLinksWhenAnnotatedAndReordered() {
        val notices = NoticeWidget(listOf(
            NoticeWidgetItem("课程申报", "教务处", "2026-10-23", "https://school.example/1"),
            NoticeWidgetItem("体测安排", "教务处", "2026-10-21", "https://school.example/2"),
            NoticeWidgetItem("校园活动", "书院", "2026-10-22", "https://school.example/3"),
        ))
        val picked = display(notices, """{"rows":[2,1],"annotations":[{"row":2,"why":"查看本学期测试安排","level":3}],"sort":"priority_desc","caption":"先看体测安排","fields":["source","why"]}""") as NoticeWidget
        assertEquals(listOf("体测安排", "课程申报"), picked.items.map { it.title })
        assertEquals("https://school.example/2", picked.items.first().link)
        assertEquals("查看本学期测试安排", picked.items.first().why)
        assertEquals(3, picked.items.first().level)
        assertFalse(picked.display.shows("date"))
        assertEquals(picked, storedToWidget(picked.toStored()))
        assertFailsWith<IllegalArgumentException> { display(notices, """{"annotations":[{"row":5,"why":"不存在"}]}""") }
        assertFailsWith<IllegalArgumentException> { display(notices, """{"annotations":[{"row":1,"title":"改标题"}]}""") }
        val filtered = display(notices, """{"sources":["书院"],"date_from":"2026-10-22"}""") as NoticeWidget
        assertEquals(listOf("校园活动"), filtered.items.map { it.title })
    }

    @Test fun cardSummaryIsComputedFromSelectedRealTransactions() {
        val card = CardWidget(CardInfo(balance = 12.0), listOf(
            Transaction("2026-10-20 12:00", amount = -8.0),
            Transaction("2026-10-21 12:00", amount = 100.0),
            Transaction("2026-10-22 12:00", amount = -10.0),
        ), transactionsLoaded = true)
        val selected = display(card, """{"view":"summary","direction":"expense","date_from":"2026-10-21"}""") as CardWidget
        assertEquals(10.0, selected.spend)
        assertEquals(0.0, selected.income)
        assertEquals(1, selected.transactions.size)
        assertFailsWith<IllegalArgumentException> { display(CardWidget(CardInfo()), """{"view":"transactions"}""") }
    }

    @Test fun oldHistoryStillLoadsWithDefaultPresentation() {
        val old = StoredWidget("GradeWidget", """{"grades":[{"courseName":"甲","score":"90"}]}""")
        val loaded = storedToWidget(old) as GradeWidget
        assertEquals(15, loaded.display.limit)
        assertEquals("甲", loaded.grades.single().courseName)
    }
}
