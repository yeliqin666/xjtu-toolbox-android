package com.xjtu.toolbox.schedule

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [courseColorMap] 的分配规则：同一批课名尽量不同色、与输入顺序无关、
 * 单门课时与 [defaultCourseColor] 一致、超过色板才允许重复。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/schedule/CourseColorMapTest.kt`，随
 * `courseColorMap` 一起搬进 :core —— 留在 :app 会红，因为这一簇要读一次用户自定义色
 * （[CourseColors.of]），而 :app 的**单元测试**跑在 JVM 上、拿到的是 :core 的 android 变体，
 * 那里的 `KeyValueStore` 需要 `initAndroidPlatform` 注入的 `Context`（Android actual 故意
 * 在未注入时报错，而不是静默当成空存储）。搬进 commonTest 之后走 jvm 的内存 store，断言照旧。
 */
class CourseColorMapTest {

    private val names = listOf("高等数学", "大学物理", "线性代数", "大学英语", "程序设计", "思想道德与法治", "工程图学")

    @Test
    fun `同一批课程的默认色互不相同`() {
        val map = courseColorMap(names)
        assertEquals(names.size, map.values.toSet().size)
    }

    @Test
    fun `结果与输入顺序无关`() {
        assertEquals(courseColorMap(names), courseColorMap(names.reversed()))
    }

    @Test
    fun `没有撞色时和单门课的默认色一致`() {
        // 单独一门课不会撞色：思源学堂只认识单门课，取到的必须与课表同色
        for (n in names) assertEquals(defaultCourseColor(n), courseColorMap(listOf(n)).getValue(n))
    }

    @Test
    fun `超过色板数量才允许重复`() {
        val many = (1..COURSE_COLORS.size + 3).map { "课程$it" }
        val map = courseColorMap(many)
        assertEquals(many.size, map.size)
        assertTrue(map.values.toSet().size == COURSE_COLORS.size)
    }

    /** 用户改过的色优先于默认色 —— 这一条正是 `courseColorMap` 要读存储的原因。 */
    @Test
    fun `用户改过的颜色优先`() {
        val name = "改色优先专用课-20261006"
        val custom = Color(0xFF123456)
        CourseColors.set(name, custom)
        try {
            assertEquals(custom, courseColorMap(listOf(name, "大学物理")).getValue(name))
            // 改色不占默认色板的空位：其它课的分配不受影响
            assertEquals(courseColorMap(listOf("大学物理")).getValue("大学物理"),
                courseColorMap(listOf(name, "大学物理")).getValue("大学物理"))
        } finally {
            CourseColors.set(name, null)
        }
    }
}
