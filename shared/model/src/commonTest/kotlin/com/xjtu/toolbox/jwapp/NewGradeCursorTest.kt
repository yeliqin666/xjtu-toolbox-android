package com.xjtu.toolbox.jwapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewGradeCursorTest {

    private fun item(name: String, code: String?, term: String = "2026-2027-1", score: String = "90") =
        ScoreItem(id = "$term-$name", termCode = term, courseName = name, score = score, courseCode = code)

    private fun terms(vararg items: ScoreItem) =
        items.groupBy { it.termCode }.map { (code, list) -> TermScore(termCode = code, scoreList = list) }

    @Test
    fun firstRun_onlyBuildsBaseline() {
        val adv = NewGradeCursor.advance(null, terms(item("高等数学(MATH100101)", "MATH100101"), item("线性代数", null)))
        assertTrue(adv.baseline)
        assertTrue(adv.fresh.isEmpty())
        assertEquals(2, adv.seen.size)
    }

    @Test
    fun newCourse_reportedOnce() {
        val base = NewGradeCursor.advance(null, terms(item("高等数学", "MATH1")))
        val next = terms(item("高等数学", "MATH1"), item("大学物理", "PHYS1"))
        val first = NewGradeCursor.advance(base.seen, next)
        assertEquals(listOf("大学物理"), first.fresh.map { it.courseName })
        // 同一门第二次见到不再报
        val again = NewGradeCursor.advance(first.seen, next)
        assertTrue(again.fresh.isEmpty())
    }

    @Test
    fun sameCountSwap_isDetected() {
        val base = NewGradeCursor.advance(null, terms(item("高等数学", "MATH1"), item("线性代数", "MATH2")))
        // 撤下线性代数、新出大学物理：总门数不变
        val adv = NewGradeCursor.advance(base.seen, terms(item("高等数学", "MATH1"), item("大学物理", "PHYS1")))
        assertEquals(listOf("PHYS1"), adv.fresh.map { it.courseCode })
        // 撤下的那门之后再出现也不重复报
        val back = NewGradeCursor.advance(adv.seen, terms(item("高等数学", "MATH1"), item("线性代数", "MATH2"), item("大学物理", "PHYS1")))
        assertTrue(back.fresh.isEmpty())
    }

    @Test
    fun sameCourseDifferentTerm_isNew() {
        val base = NewGradeCursor.advance(null, terms(item("体育", "PE1", term = "2025-2026-2")))
        val adv = NewGradeCursor.advance(base.seen, terms(item("体育", "PE1", term = "2025-2026-2"), item("体育", "PE1", term = "2026-2027-1")))
        assertEquals(listOf("2026-2027-1"), adv.fresh.map { it.termCode })
    }

    @Test
    fun cursorsAreIndependentPerAccount() {
        // 宿主按账号各存一份：一个账号的基线不影响另一个
        val a = NewGradeCursor.advance(null, terms(item("高等数学", "MATH1")))
        val b = NewGradeCursor.advance(null, terms(item("高等数学", "MATH1"), item("大学物理", "PHYS1")))
        val aNext = NewGradeCursor.advance(a.seen, terms(item("高等数学", "MATH1"), item("大学物理", "PHYS1")))
        val bNext = NewGradeCursor.advance(b.seen, terms(item("高等数学", "MATH1"), item("大学物理", "PHYS1")))
        assertEquals(1, aNext.fresh.size)
        assertTrue(bNext.fresh.isEmpty())
    }

    @Test
    fun summary_namesCoursesWithoutScores() {
        val one = listOf(item("高等数学(MATH100101)", "MATH100101", score = "95"))
        assertEquals("高等数学 出分了", NewGradeCursor.summary(one))
        val two = one + item("大学物理", "PHYS1", score = "88")
        val text = NewGradeCursor.summary(two)
        assertEquals("高等数学 等 2 门出分了", text)
        assertTrue("95" !in text && "88" !in text)
    }
}
