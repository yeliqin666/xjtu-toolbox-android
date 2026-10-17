package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.nav.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InboxRoutesTest {
    @Test
    fun `系统通知点进的页面对应哪类收纳`() {
        assertEquals(InboxCategories.GRADE, InboxCategories.ofRoute(AppRoute.JwappScore))
        assertEquals(InboxCategories.LMS, InboxCategories.ofRoute(AppRoute.Lms(3)))
        assertNull(InboxCategories.ofRoute(AppRoute.Settings))
    }
}