package com.xjtu.toolbox.lms

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 详情页信息卡那行时间。三个真实样例都取自 2026-09-18 的实测响应（见 PR 描述）：
 * - 数学物理方法/第一章作业：`start_time` 空、`visible_start_at` 有值、deadline == end_time
 * - 大学化学/第一章作业：反过来，只有 `start_time`
 * - 高等数学I-2/第十三次作业：deadline 比 end_time 晚 20 天（4.6% 的那种）
 */
class LmsActivityTimeLineTest {

    private fun homework(
        startTime: String? = null,
        visibleStartAt: String? = null,
        endTime: String? = null,
        deadline: String? = null,
    ) = LmsActivity(
        id = 1,
        type = LmsActivityType.HOMEWORK,
        title = "作业",
        startTime = startTime,
        visibleStartAt = visibleStartAt,
        endTime = endTime,
        deadline = deadline,
    )

    @Test
    fun onlyVisibleStart_usesVisibleLabel() {
        val line = lmsActivityTimeLine(
            homework(
                visibleStartAt = "2026-09-16T00:38:21Z",
                endTime = "2026-09-24T15:59:00Z",
                deadline = "2026-09-24T15:59:00Z",
            )
        )
        assertEquals("可见 2026/09/16 00:38 ~ 截止 2026/09/24 15:59", line)
    }

    @Test
    fun onlyStartTime_usesStartLabel() {
        val line = lmsActivityTimeLine(
            homework(
                startTime = "2026-03-13T07:07:00Z",
                endTime = "2026-03-20T15:59:00Z",
                deadline = "2026-03-20T15:59:00Z",
            )
        )
        assertEquals("开始 2026/03/13 07:07 ~ 截止 2026/03/20 15:59", line)
    }

    /** 关键：deadline 与 end_time 不同时必须用 deadline，否则同屏会出现两个不一样的「截止」。 */
    @Test
    fun deadlineWinsOverEndTime() {
        val line = lmsActivityTimeLine(
            homework(endTime = "2026-06-10T15:59:00Z", deadline = "2026-06-30T11:25:00Z")
        )
        assertEquals("截止 2026/06/30 11:25", line)
    }

    @Test
    fun noDeadline_fallsBackToEndTime() {
        val line = lmsActivityTimeLine(homework(endTime = "2026-09-24T15:59:00Z"))
        assertEquals("截止 2026/09/24 15:59", line)
    }

    @Test
    fun noTimeAtAll_isEmpty() {
        assertEquals("", lmsActivityTimeLine(homework()))
    }

    /** 课堂/直播的 endTime 是下课时间，不能标成「截止」。 */
    @Test
    fun lesson_isUnlabeledRange() {
        val lesson = LmsActivity(
            id = 2,
            type = LmsActivityType.LESSON,
            title = "课堂",
            startTime = "2026-09-15T16:40:00Z",
            endTime = "2026-09-15T17:30:00Z",
            deadline = "2026-09-15T23:00:00Z",
        )
        assertEquals("2026/09/15 16:40 ~ 2026/09/15 17:30", lmsActivityTimeLine(lesson))
    }
}
