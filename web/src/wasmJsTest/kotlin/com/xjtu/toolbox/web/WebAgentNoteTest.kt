package com.xjtu.toolbox.web

import com.xjtu.toolbox.agent.agentToolLabel
import com.xjtu.toolbox.web.testsources.WebSourceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「页面说明与白名单同源」的机器可验版本。 */
class WebAgentNoteTest {

    /** `:server` 的真白名单。 */
    private val serveWhitelist: List<String> = run {
        val source = WebSourceSnapshot.SERVE_AGENT_TOOLS
        val start = source.indexOf("val NAMES: List<String> = listOf(")
        assertTrue(start >= 0, "ServeAgentTools.kt 里找不到 NAMES 那份白名单 —— 这段解析要跟着改")
        val block = source.substring(start, source.indexOf(")", start))
        Regex("\"([a-z_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    /** 本端页面说明里「不开的那几档」的全部名字。 */
    private val closedNames: List<String> = AGENT_CLOSED_TOOL_GROUPS.flatMap { it.names }

    @Test
    fun `真下发的每一件工具都出现在页面说明里（同源，一件不许漏说）`() {
        assertTrue(serveWhitelist.isNotEmpty(), "白名单解析成空的了 —— 解析要跟着改")
        val note = agentToolNote(serveWhitelist)
        serveWhitelist.forEach { name ->
            assertTrue(
                note.contains(agentToolLine(name)),
                "serve 真下发给模型的 `$name` 没有出现在页面说明里：$note",
            )
        }
        // 「能查」那一段只列白名单里的：不开的那几档不许混进去
        val canQuery = note.substringAfter("它能查：").substringBefore("不开放的几档")
        closedNames.forEach { name ->
            assertFalse(canQuery.contains("`$name`"), "页面说明把没开放的 `$name` 说成能查了：$canQuery")
        }
    }

    @Test
    fun `页面说明里「不开的那几档」与真下发的白名单一个都不重叠`() {
        val overlap = closedNames.filter { it in serveWhitelist }
        assertTrue(
            overlap.isEmpty(),
            "这些工具一边被说成能查、一边被说成不开：$overlap —— 两份清单漂了",
        )
        // 反过来：不开的那几档是真的列在页面上（不是只写在代码里）
        val note = agentToolNote(serveWhitelist)
        closedNames.forEach { name ->
            assertTrue(note.contains(name), "「不开的那几档」在页面上没说 `$name`：$note")
        }
    }

    @Test
    fun `页面说明里的工具名都是 core 那份目录里真有的（不编名字）`() {
        val unknown = (serveWhitelist + closedNames).filter { agentToolLabel(it) == "调用工具 $it…" }
        assertTrue(
            unknown.isEmpty(),
            "这些名字在 `:core` 的工具文案表里认不出来（页面说明不许编名字）：$unknown",
        )
        assertEquals("`no_such_tool`（这一端没有它的说明）", agentToolLine("no_such_tool"))
        assertEquals("`get_schedule`（查询课表）", agentToolLine("get_schedule"))
    }

    @Test
    fun `白名单是空的这一档也如实说（不留一句「能查：」的废话）`() {
        val note = agentToolNote(emptyList())
        assertTrue(note.contains("一件工具都没给模型"), note)
        assertFalse(note.contains("它能查："), note)
        assertTrue(note.contains("toolAllowlist 是空的"), note)
    }

    @Test
    fun `说明按传进来的名单现生成：换一份名单就换一段话，不多说一件`() {
        val note = agentToolNote(listOf("calculate", "get_schedule"))
        assertTrue(note.contains(agentToolLine("calculate")), note)
        assertTrue(note.contains(agentToolLine("get_schedule")), note)
        assertFalse(note.contains(agentToolLine("preference")), "名单里没有的工具不该出现在「能查」那一段：$note")
        assertFalse(note.contains(agentToolLine("get_calendar")), note)
    }

    @Test
    fun `三处如实写在页面上：自建日程看不到、改上一条只是再发一次、Key 在 serve 那台机器上`() {
        val note = agentToolNote(serveWhitelist)
        assertTrue(note.contains("自建日程"), note)
        assertTrue(note.contains("再发一次"), note)
        assertTrue(note.contains("运行 serve 的那台机器"), note)
    }
}
