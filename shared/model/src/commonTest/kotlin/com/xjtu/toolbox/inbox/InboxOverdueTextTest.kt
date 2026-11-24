package com.xjtu.toolbox.inbox

import kotlin.test.Test
import kotlin.test.assertEquals

/** 逾期待办右上角的措辞：报逾期多久，不再出现「-489 分钟后截止」这种负数。 */
class InboxOverdueTextTest {

    private val minute = 60_000L

    @Test
    fun `不到一分钟算刚逾期`() {
        assertEquals("刚刚逾期", InboxRules.overdueText(0))
        assertEquals("刚刚逾期", InboxRules.overdueText(59_999))
    }

    @Test
    fun `一小时内报分钟`() {
        assertEquals("已逾期 1 分钟", InboxRules.overdueText(minute))
        assertEquals("已逾期 59 分钟", InboxRules.overdueText(59 * minute))
    }

    @Test
    fun `一天内报小时和分钟，整点不带零分钟`() {
        assertEquals("已逾期 8 小时 9 分钟", InboxRules.overdueText(489 * minute))
        assertEquals("已逾期 2 小时", InboxRules.overdueText(120 * minute))
    }

    @Test
    fun `超过一天报天数`() {
        assertEquals("已逾期 1 天", InboxRules.overdueText(24 * 60 * minute))
        assertEquals("已逾期 3 天", InboxRules.overdueText((3 * 24 * 60 + 300) * minute))
    }
}
