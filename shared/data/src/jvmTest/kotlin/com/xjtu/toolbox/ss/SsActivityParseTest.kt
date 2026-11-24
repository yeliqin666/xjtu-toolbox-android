package com.xjtu.toolbox.ss

import com.xjtu.toolbox.util.safeParseJson
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsActivityParseTest {

    @Test
    fun 列表数据无论直接是数组还是包一层都认() {
        assertEquals(2, SsActivityApi.rows("""[{"HDDM":"a"},{"HDDM":"b"}]""".safeParseJson()).size)
        assertEquals(1, SsActivityApi.rows("""{"rows":[{"HDDM":"a"}],"x":1}""".safeParseJson()).size)
        assertEquals("a", SsActivityApi.single("""{"HDDM":"a","HDMC":"讲座"}""".safeParseJson())?.get("HDDM")?.toString()?.trim('"'))
    }

    @Test
    fun 时间串只认到分钟() {
        assertEquals(LocalDateTime(2026, 11, 20, 8, 0), parseSsTime("2026-11-20 08:00"))
        assertEquals(LocalDateTime(2026, 11, 20, 0, 0), parseSsTime("2026-11-20"))
        assertNull(parseSsTime("2026.10.01-12.06"))
    }

    @Test
    fun 报名时间外和已报名都拦住() {
        val base = SsActivity("1", "高等数学期末复习讲座", "学业", "数学学院", "2026-12-01 19:00", "", SsActivityStatus.UPCOMING, 10, "3", null)
        val d = SsActivityDetail(
            base, "", "", "", needApply = true, applyMode = SsApplyMode.ONE_CLICK,
            applyStart = "2026-11-20 08:00", applyEnd = "2026-11-30 22:00", quota = 100,
            needReview = false, applied = false, canCancel = true, onsiteSignIn = true,
        )
        assertNull(d.applyBlocker(LocalDateTime(2026, 11, 25, 12, 0)))
        assertTrue(d.applyBlocker(LocalDateTime(2026, 11, 19, 12, 0))!!.startsWith("报名还没开始"))
        assertTrue(d.applyBlocker(LocalDateTime(2026, 12, 1, 0, 0))!!.startsWith("报名已截止"))
        assertEquals("已经报过名了", d.copy(applied = true).applyBlocker(LocalDateTime(2026, 11, 25, 12, 0)))
        assertTrue(base.likelyGroupProof)
    }
}
