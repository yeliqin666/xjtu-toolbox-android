package com.xjtu.toolbox.home

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 打招呼的分档边界。首页「今日」卡与屁岱首屏共用这一份，边界错一格就会出现
 * 「13:00 还说上午好」这类不自然的文案，所以每个分档的**首尾**都钉住。
 */
class GreetingTest {

    @Test
    fun everyBandIncludingItsEdges() {
        assertEquals("夜深了", Greeting.of(LocalTime(0, 0)))
        assertEquals("夜深了", Greeting.of(LocalTime(3, 59)))
        assertEquals("早安", Greeting.of(LocalTime(4, 0)))
        assertEquals("早安", Greeting.of(LocalTime(6, 59)))
        assertEquals("早上好", Greeting.of(LocalTime(7, 0)))
        assertEquals("早上好", Greeting.of(LocalTime(8, 59)))
        assertEquals("上午好", Greeting.of(LocalTime(9, 0)))
        assertEquals("上午好", Greeting.of(LocalTime(10, 59)))
        assertEquals("中午好", Greeting.of(LocalTime(11, 0)))
        assertEquals("中午好", Greeting.of(LocalTime(12, 59)))
        assertEquals("午安", Greeting.of(LocalTime(13, 0)))
        assertEquals("午安", Greeting.of(LocalTime(13, 59)))
        assertEquals("下午好", Greeting.of(LocalTime(14, 0)))
        assertEquals("下午好", Greeting.of(LocalTime(17, 59)))
        assertEquals("晚上好", Greeting.of(LocalTime(18, 0)))
        assertEquals("晚上好", Greeting.of(LocalTime(21, 59)))
        assertEquals("晚安", Greeting.of(LocalTime(22, 0)))
        assertEquals("晚安", Greeting.of(LocalTime(23, 59)))
    }

    /** 默认参数走的是 `Clock.System.now()`，在 test 环境里也不该抛（时区/时钟都没配错）。 */
    @Test
    fun defaultArgumentUsesTheClock() {
        val text = Greeting.of()
        assertEquals(true, text in setOf("夜深了", "早安", "早上好", "上午好", "中午好", "午安", "下午好", "晚上好", "晚安"))
    }
}
