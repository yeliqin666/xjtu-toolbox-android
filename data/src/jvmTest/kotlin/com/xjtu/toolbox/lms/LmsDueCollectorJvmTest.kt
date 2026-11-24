package com.xjtu.toolbox.lms

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/** [LmsDueCollector.refreshIfStale] 的节流规则：桌面和 serve 每次读待交缓存前都会调它。 */
class LmsDueCollectorJvmTest {

    private var siteCalls = 0
    private val offlineSite: suspend () -> com.xjtu.toolbox.auth.SiteSession = {
        siteCalls++
        error("学堂登不上")
    }

    @BeforeTest
    fun reset() {
        LmsDueCollector.resetRefreshClock()
        siteCalls = 0
    }

    @AfterTest
    fun cleanup() = LmsDueCollector.resetRefreshClock()

    @Test
    fun `没有账号时不采集`() = runBlocking {
        assertFalse(LmsDueCollector.refreshIfStale("", offlineSite))
        assertEquals(0, siteCalls)
    }

    @Test
    fun `TTL 内第二次调用不再请求学堂，失败那轮也算数`() = runBlocking {
        assertTrue(LmsDueCollector.refreshIfStale("2200000001", offlineSite))
        assertFalse(LmsDueCollector.refreshIfStale("2200000001", offlineSite))
        assertEquals(1, siteCalls)
    }

    @Test
    fun `节流按账号分开`() = runBlocking {
        assertTrue(LmsDueCollector.refreshIfStale("2200000001", offlineSite))
        assertTrue(LmsDueCollector.refreshIfStale("2200000002", offlineSite))
        assertEquals(2, siteCalls)
    }

    private fun due(deadline: String) = LmsDue(
        courseId = 1, courseName = "通用学术英语", activityId = 3, title = "Assignment 3",
        deadline = deadline, fetchedAt = 0L,
    )

    @Test
    fun `刚截止的作业仍查提交态（#503）`() {
        val now = java.time.Instant.parse("2026-11-23T15:00:00Z")
        // 11-22 23:59 截止，过去不到一天：交了要能认出来
        assertTrue(LmsDueCollector.checksSubmission(due("2026-11-22T15:59:00Z"), now))
        assertTrue(LmsDueCollector.checksSubmission(due("2026-11-30T15:59:00Z"), now))
    }

    @Test
    fun `截止超过一周的不再逐条查`() {
        val now = java.time.Instant.parse("2026-11-23T15:00:00Z")
        assertFalse(LmsDueCollector.checksSubmission(due("2026-11-15T15:59:00Z"), now))
    }

    @Test
    fun `截止时间解析不了时照样查`() {
        assertTrue(LmsDueCollector.checksSubmission(due("不是时间"), java.time.Instant.parse("2026-11-23T15:00:00Z")))
    }

    @Test
    fun `过了 TTL 再采一轮`() = runBlocking {
        assertTrue(LmsDueCollector.refreshIfStale("2200000001", offlineSite, ttl = Duration.ZERO))
        assertTrue(LmsDueCollector.refreshIfStale("2200000001", offlineSite, ttl = Duration.ZERO))
        assertEquals(2, siteCalls)
    }
}