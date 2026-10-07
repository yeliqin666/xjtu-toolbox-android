package com.xjtu.toolbox.schedule

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [XjtuTime] 的学年/学期换算。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/schedule/XjtuTimeTest.kt`：`XjtuTime` 搬进
 * commonMain、`java.time` 换成 `kotlinx-datetime` 之后，那边按 `java.time.LocalDate`
 * 编译出的调用在运行期 `NoSuchMethodError`（交接文档 §1.1 的 10 个失败之一）。
 * 断言逐条原样搬来，只换了壳（kotlin.test + kotlinx.datetime）。
 */
class XjtuTimeTest {

    @Test
    fun `九月开学推到秋季学期`() {
        assertEquals("2026-2027-1", XjtuTime.expectedTermCode(LocalDate(2026, 9, 14)))
    }

    @Test
    fun `一月仍算上一学年的秋季学期`() {
        assertEquals("2025-2026-1", XjtuTime.expectedTermCode(LocalDate(2026, 1, 10)))
    }

    @Test
    fun `三月推到春季学期`() {
        assertEquals("2025-2026-2", XjtuTime.expectedTermCode(LocalDate(2026, 3, 2)))
    }

    @Test
    fun `六月底仍是春季学期`() {
        assertEquals("2025-2026-2", XjtuTime.expectedTermCode(LocalDate(2026, 6, 30)))
    }

    @Test
    fun `七八月分不清短学期与暑假返回空`() {
        assertNull(XjtuTime.expectedTermCode(LocalDate(2026, 7, 15)))
        assertNull(XjtuTime.expectedTermCode(LocalDate(2026, 8, 20)))
    }
}
