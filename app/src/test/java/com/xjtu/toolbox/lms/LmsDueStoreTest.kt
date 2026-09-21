package com.xjtu.toolbox.lms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * LmsDueStore 合并规则的契约单测（plan2 §5.4）：新旧覆盖按 fetchedAt，
 * 过期（截止早于「现在减 1 天」）的条目丢弃。
 */
class LmsDueStoreTest {

    private val now = Instant.parse("2027-01-10T00:00:00Z")

    private fun due(
        courseId: Int = 1,
        activityId: Int = 1,
        deadline: String = "2027-01-12T16:00:00Z",
        submitted: Boolean = false,
        fetchedAt: Long = 0L,
        title: String = "作业",
    ) = LmsDue(
        courseId = courseId, courseName = "课程$courseId",
        activityId = activityId, title = title,
        deadline = deadline, submitted = submitted, fetchedAt = fetchedAt,
    )

    @Test
    fun 同一条以fetchedAt较新的为准() {
        val old = due(fetchedAt = 1, submitted = false)
        val fresh = due(fetchedAt = 2, submitted = true)
        val merged = LmsDueStore.mergeDue(listOf(old), listOf(fresh), now)
        assertEquals(1, merged.size)
        assertTrue(merged[0].submitted)
    }

    @Test
    fun 不同课程或活动都保留() {
        val a = due(courseId = 1, activityId = 1)
        val b = due(courseId = 1, activityId = 2)
        val c = due(courseId = 2, activityId = 1)
        val merged = LmsDueStore.mergeDue(listOf(a), listOf(b, c), now)
        assertEquals(3, merged.size)
    }

    @Test
    fun 截止早于现在减一天的被丢弃() {
        val expired = due(deadline = "2027-01-08T00:00:00Z") // 早于 now-1day (01-09)
        val stillValid = due(activityId = 2, deadline = "2027-01-09T12:00:00Z") // 晚于 cutoff
        val merged = LmsDueStore.mergeDue(emptyList(), listOf(expired, stillValid), now)
        assertEquals(listOf(2), merged.map { it.activityId })
    }

    @Test
    fun 截止时间解析不了的保留_宁可多显示不漏提醒() {
        val bad = due(deadline = "not-a-date")
        val merged = LmsDueStore.mergeDue(emptyList(), listOf(bad), now)
        assertEquals(1, merged.size)
    }
}
