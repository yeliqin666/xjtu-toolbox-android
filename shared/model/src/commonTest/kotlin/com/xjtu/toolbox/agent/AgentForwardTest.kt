package com.xjtu.toolbox.agent

import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.notification.Notification
import com.xjtu.toolbox.notification.NotificationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class AgentForwardTest {
    private fun notice(title: String, tags: List<String> = emptyList()) = Notification(
        title = title, link = "https://example.invalid/$title", source = NotificationSource.JWC, tags = tags,
        date = LocalDate(2026, 11, 4),
    )

    @Test fun everySnapshotIsMarkedAsExternalData() {
        val course = LmsCourseSummary(id = 7, name = "高数")
        listOf(
            AgentForward.notice(notice("选课")),
            AgentForward.notices(listOf(notice("a"), notice("b"))),
            AgentForward.inboxNotice(InboxItem(id = "notice:https://example.invalid/x", category = "notice", source = "教务处", title = "x")),
            AgentForward.lmsActivity(course, LmsActivity(id = 9, title = "作业一"), "作业"),
            AgentForward.webPage("标题", "https://example.invalid", "正文"),
        ).forEach { assertTrue(it.snapshot.endsWith(ToolReply.EXTERNAL_DATA), it.snapshot) }
    }

    @Test fun noticeCarriesLinkAndCategory() {
        val forward = AgentForward.notice(notice("选课通知", listOf("教学运行")))
        assertTrue("选课通知" in forward.text)
        assertTrue("https://example.invalid/选课通知" in forward.snapshot)
        assertTrue("教学运行" in forward.snapshot)
    }

    @Test fun singleNoticeInTheBarReadsLikeOneNotice() {
        val one = notice("选课")
        assertEquals(AgentForward.notice(one), AgentForward.notices(listOf(one)))
    }

    @Test fun inboxLinkComesFromTheId() {
        val forward = AgentForward.inboxNotice(InboxItem(id = "notice:https://example.invalid/x", category = "notice", source = "教务处", title = "x"))
        assertTrue("链接：https://example.invalid/x" in forward.snapshot)
    }

    @Test fun lmsDescriptionLosesHtml() {
        val forward = AgentForward.lmsActivity(
            LmsCourseSummary(id = 7, name = "高数"),
            LmsActivity(id = 9, title = "作业一", description = "<p>交 <b>PDF</b></p>"),
            "作业",
        )
        assertTrue("交 PDF" in forward.snapshot)
        assertFalse("<p>" in forward.snapshot)
    }

    @Test fun longPagesAreCutAndSaySo() {
        val forward = AgentForward.webPage("t", "https://example.invalid", "哈".repeat(AgentForward.MAX_PAGE_CHARS + 100))
        assertTrue("只带了开头" in forward.snapshot)
        assertEquals(AgentForward.MAX_PAGE_CHARS, forward.snapshot.count { it == '哈' })
    }

    @Test fun unreadablePageAsksTheModelNotToMakeThingsUp() {
        assertTrue("如实说" in AgentForward.webPage("t", "https://example.invalid", "  ").snapshot)
    }
}
