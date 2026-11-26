package com.xjtu.toolbox.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChoiceStateTest {
    private val options = listOf("甲", "乙", "丙")

    @Test
    fun `单选：选项和其他只留一个`() {
        val s = ChoiceState(options, multiple = false)
        s.toggle("乙")
        assertEquals(listOf("乙"), s.answers)
        s.toggleOther()
        assertTrue(s.otherMissing)
        assertEquals(emptyList(), s.answers)
        s.editOther("自己的想法")
        assertEquals(listOf("自己的想法"), s.answers)
        s.toggle("甲")
        assertFalse(s.otherOn)
        assertEquals(listOf("甲"), s.answers)
    }

    @Test
    fun `多选：按选项原顺序，其他排最后，「、」换成「，」`() {
        val s = ChoiceState(options, multiple = true)
        s.toggle("丙"); s.toggle("甲"); s.toggleOther(); s.editOther(" 一、二 ")
        // 统计端把答案用「、」连起来再按选项原文切分，自填内容里不能再有「、」
        assertEquals(listOf("甲", "丙", "一，二"), s.answers)
        s.toggleOther()
        assertEquals(listOf("甲", "丙"), s.answers)
    }

    @Test
    fun `改投时还原上次的答案，去掉旧版弹窗加的前缀`() {
        val s = ChoiceState(options, multiple = true, previous = listOf("乙", "其他：别的"))
        assertEquals(setOf("乙"), s.picked)
        assertTrue(s.otherOn)
        assertEquals(listOf("乙", "别的"), s.answers)
    }
}
