package com.xjtu.toolbox.lms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** `deadlineInstant()` / `remaining()` 的口径测试。 */
class LmsDueFormatTest {

    private fun at(iso: String) = Instant.parse(iso)

    @Test
    fun parsesAllThreeUpstreamInstantShapes() {
        val z = LmsActivity(deadline = "2026-09-14T00:00:00Z").deadlineInstant()
        assertEquals(at("2026-09-14T00:00:00Z"), z)

        // 带偏移量
        assertEquals(z, LmsActivity(deadline = "2026-09-14T08:00:00+08:00").deadlineInstant())

        assertEquals(z, LmsActivity(deadline = "2026-09-14T08:00:00+08:00[Asia/Shanghai]").deadlineInstant())
    }

    @Test
    fun deadlineWinsOverEndTimeAndGarbageIsNull() {
        // 列表接口的 `deadline` 优先（实测 4.6% 与 endTime 不同，且都是 endTime 更早）
        assertEquals(
            at("2026-09-14T00:00:00Z"),
            LmsActivity(deadline = "2026-09-14T00:00:00Z", endTime = "2026-09-20T00:00:00Z").deadlineInstant(),
        )
        assertEquals(
            at("2026-09-20T00:00:00Z"),
            LmsActivity(deadline = "   ", endTime = "2026-09-20T00:00:00Z").deadlineInstant(),
        )
        assertNull(LmsActivity(deadline = null, endTime = null).deadlineInstant())
        assertNull(LmsActivity(deadline = "明天").deadlineInstant())
    }

    @Test
    fun wordingFallsIntoTheThreeBuckets() {
        val now = at("2026-09-14T00:00:00Z")
        assertEquals("不到 1 小时", remaining(now, at("2026-09-14T00:59:00Z")))
        assertEquals("剩 5 小时", remaining(now, at("2026-09-14T05:30:00Z")))
        assertEquals("剩 1 天", remaining(now, at("2026-09-15T00:00:00Z")))
        assertEquals("剩 1 天 3 小时", remaining(now, at("2026-09-15T03:30:00Z")))
        assertEquals("剩 2 天 1 小时", remaining(now, at("2026-09-16T01:00:00Z")))
    }
}
