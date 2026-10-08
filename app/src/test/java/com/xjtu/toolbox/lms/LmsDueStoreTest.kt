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
    fun 多门课的作业合并后一个都不丢() {
        val existing = listOf(due(courseId = 1, activityId = 1), due(courseId = 2, activityId = 2))
        val incoming = (1..11).map { due(courseId = it, activityId = 100 + it, fetchedAt = 5) }
        val merged = LmsDueStore.mergeDue(existing, incoming, now)
        assertEquals(13, merged.size)
    }

    @Test
    fun 只收带截止的作业_资料和没截止的不收() {
        val course = LmsCourseSummary(id = 9, name = "国际学术交流英语")
        val acts = listOf(
            LmsActivity(id = 1, type = LmsActivityType.HOMEWORK, title = "Self-introduction Video", deadline = "2026-10-25T15:59:00Z"),
            LmsActivity(id = 2, type = LmsActivityType.HOMEWORK, title = "没截止", deadline = null),
            LmsActivity(id = 3, type = LmsActivityType.MATERIAL, title = "课件", deadline = "2026-10-25T15:59:00Z"),
            LmsActivity(id = 4, type = LmsActivityType.HOMEWORK, title = "只有 end_time", endTime = "2026-12-20T15:59:00Z"),
        )
        val items = LmsDueCollector.dueItems(listOf(course to acts), fetchedAt = 7)
        assertEquals(listOf(1, 4), items.map { it.activityId })
        assertTrue(items.all { it.courseId == 9 && it.courseName == "国际学术交流英语" && !it.submitted && it.fetchedAt == 7L })
        assertEquals("2026-12-20T15:59:00Z", items[1].deadline)
    }

    @Test
    fun 截止时间解析不了的保留_宁可多显示不漏提醒() {
        val bad = due(deadline = "not-a-date")
        val merged = LmsDueStore.mergeDue(emptyList(), listOf(bad), now)
        assertEquals(1, merged.size)
    }
}
