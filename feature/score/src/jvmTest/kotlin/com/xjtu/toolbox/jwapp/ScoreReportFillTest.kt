package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.score.ReportedGrade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreReportFillTest {
    private fun row(id: String, name: String, credit: Double = 2.0, score: String = "暂不能查看") =
        ScoreItem(id = id, termCode = TERM, courseName = name, score = score, coursePoint = credit)

    private fun rep(name: String, credit: Double = 2.0, score: String = "90") =
        ReportedGrade(courseName = name, coursePoint = credit, score = score, gpa = 4.0, term = TERM)

    @Test
    fun `看不到成绩的几种写法`() {
        listOf("", "null", "--", "暂不能查看", "未评教", "请先评教后查看").forEach { assertTrue(ScoreReportFill.isHidden(it), it) }
        listOf("95", "A+", "优秀", "通过", "不及格").forEach { assertFalse(ScoreReportFill.isHidden(it), it) }
    }

    @Test
    fun `课程号、全半角、空格标点都抹平后对上`() {
        val hidden = listOf(row("1", "科研训练-I(PHYS546609)", 1.0), row("2", "Python数据挖掘与案例应用(MATH545807)"))
        val report = listOf(rep("科研训练 Ⅰ", 1.0, "A+"), rep("Python 数据挖掘与案例应用", score = "95"))
        val m = ScoreReportFill.match(hidden, emptyList(), report)
        assertEquals("A+", m["1"]?.score)
        assertEquals("95", m["2"]?.score)
    }

    @Test
    fun `同名课分不开就不填，学分能分开就填`() {
        val hidden = listOf(row("1", "体育(PHED900150)", 1.0), row("2", "体育(PHED900160)", 1.0))
        val report = listOf(rep("体育", 1.0, "80"), rep("体育", 1.0, "85"))
        assertTrue(ScoreReportFill.match(hidden, emptyList(), report).isEmpty())

        val byCredit = ScoreReportFill.match(listOf(row("1", "物理实验", 1.0)), emptyList(),
            listOf(rep("物理实验", 2.0, "70"), rep("物理实验", 1.0, "88")))
        assertEquals("88", byCredit["1"]?.score)
    }

    @Test
    fun `已有成绩的课不会被借走，对不上的不硬填`() {
        val visible = listOf(row("v", "量子力学(PHYS201028)", 4.0, score = "91"))
        val hidden = listOf(row("1", "量子力学专题", 4.0))
        val report = listOf(rep("量子力学", 4.0, "91"), rep("固体物理", 4.0, "92"))
        assertTrue(ScoreReportFill.match(hidden, visible, report).isEmpty())
    }

    @Test
    fun `补上的行换成报表成绩并标来源`() {
        val filled = ScoreReportFill.fill(row("1", "光电子学(PHYS401009)", 4.0), rep("光电子学", 4.0, "97"))
        assertEquals("97", filled.score)
        assertEquals(97.0, filled.scoreValue)
        assertEquals(ScoreSource.REPORT, filled.source)
        assertEquals("1", filled.id)
    }

    private companion object { const val TERM = "2025-2026-1" }
}
