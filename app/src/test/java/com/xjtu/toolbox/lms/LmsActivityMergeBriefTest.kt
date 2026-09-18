package com.xjtu.toolbox.lms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 列表级字段并进详情的回归测试。
 *
 * 上游 `/api/activities/{id}` 的 79 个键里**没有 `deadline`**，只有列表
 * `/api/courses/{id}/activities` 有——所以详情页的截止时间必须靠 [mergeBrief] 从列表并进来，
 * 否则只能退回 `endTime`（实测 65 份作业里 3 份与真截止不同，方向都是提前）。
 */
class LmsActivityMergeBriefTest {

    private val listItem = LmsActivity(
        id = 1173338,
        type = LmsActivityType.HOMEWORK,
        title = "第十三次作业",
        startTime = null,
        visibleStartAt = "2026-09-16T00:38:21Z",
        endTime = "2026-06-10T15:59:00Z",
        deadline = "2026-06-30T11:25:00Z",
    )

    /** 详情响应：没有 deadline，其余同列表。 */
    private val detail = LmsActivity(
        id = 1173338,
        type = LmsActivityType.HOMEWORK,
        title = "第十三次作业",
        endTime = "2026-06-10T15:59:00Z",
    )

    @Test
    fun merge_takesDeadlineFromList() {
        val merged = detail.mergeBrief(listItem)
        assertEquals("2026-06-30T11:25:00Z", merged.deadline)
    }

    @Test
    fun merge_takesVisibleStartFromList() {
        val merged = detail.mergeBrief(listItem)
        assertEquals("2026-09-16T00:38:21Z", merged.visibleStartAt)
        // 别把"可见"当成"开始作答"
        assertNull(merged.startTime)
    }

    @Test
    fun merge_withoutList_fallsBackToEndTime() {
        val merged = detail.mergeBrief(null)
        assertEquals("2026-06-10T15:59:00Z", merged.deadline)
    }

    @Test
    fun merge_keepsDetailValues() {
        val detailed = detail.copy(deadline = "2026-07-01T00:00:00Z", startTime = "2026-06-01T00:00:00Z")
        val merged = detailed.mergeBrief(listItem)
        assertEquals("2026-07-01T00:00:00Z", merged.deadline)
        assertEquals("2026-06-01T00:00:00Z", merged.startTime)
    }

    @Test
    fun merge_ignoresBlankFields() {
        val merged = detail.mergeBrief(listItem.copy(deadline = "  ", visibleStartAt = ""))
        assertEquals("2026-06-10T15:59:00Z", merged.deadline)
        assertNull(merged.visibleStartAt)
    }

    @Test
    fun merge_briefWithoutDeadline_stillHasEndTimeFallback() {
        val merged = detail.mergeBrief(listItem.copy(deadline = null))
        assertEquals("2026-06-10T15:59:00Z", merged.deadline)
    }
}
