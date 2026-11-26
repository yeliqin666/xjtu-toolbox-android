package com.xjtu.toolbox.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

/** 今日里标题开头的日期前缀（#545）。 */
class StripDatePrefixTest {
    @Test
    fun `常见写法都去掉`() {
        assertEquals("小组汇报", stripDatePrefix("2026-11-26-1400 小组汇报"))
        assertEquals("小组汇报", stripDatePrefix("2026-11-26-14:00小组汇报"))
        assertEquals("作业", stripDatePrefix("2026.11.26 作业"))
        assertEquals("讲座", stripDatePrefix("2026年11月26日 讲座"))
        assertEquals("实验报告", stripDatePrefix("2026/1/5｜实验报告"))
    }

    @Test
    fun `不在开头、或去掉后为空就不动`() {
        assertEquals("第 3 次作业 2026-11-26", stripDatePrefix("第 3 次作业 2026-11-26"))
        assertEquals("2026-11-26", stripDatePrefix("2026-11-26"))
        assertEquals("2048 合成", stripDatePrefix("2048 合成"))
    }
}
