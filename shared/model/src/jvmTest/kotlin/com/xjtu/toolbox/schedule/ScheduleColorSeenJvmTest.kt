package com.xjtu.toolbox.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 「日程里出现过的课名」那张小表：只增不减、去重、没有新名字就不写。 */
class ScheduleColorSeenJvmTest {

    @Test
    fun `记下的课名去重后只增不减`() {
        val course = "测试课·${System.nanoTime()}"
        ScheduleColorSeen.note(listOf(course, course, "  $course  ", ""))
        assertTrue(ScheduleColorSeen.names().contains(course), "课名应当被记下来（同名只留一份）")
        assertEquals(1, ScheduleColorSeen.names().count { it == course })

        // 同一个课名再记一次：不重复、也不该再写一遍
        val before = ScheduleColorSeen.names()
        ScheduleColorSeen.note(listOf(course))
        assertEquals(before, ScheduleColorSeen.names())

        // 空集合什么都不做
        ScheduleColorSeen.note(emptyList())
        assertEquals(before, ScheduleColorSeen.names())
    }
}
