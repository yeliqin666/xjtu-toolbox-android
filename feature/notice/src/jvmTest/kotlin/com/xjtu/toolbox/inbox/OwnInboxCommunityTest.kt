package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.community.CommunityActivity
import com.xjtu.toolbox.community.CommunityActivityKind
import com.xjtu.toolbox.nav.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 社区回复 / @ 的收纳条目。2026-11-17 的反馈是「点开跳去了 GitHub」——
 * 社区本来就是 App 里自带的页面，跳出去等于让用户再登一次 GitHub，所以要落到 App 内的那一帖。
 */
class OwnInboxCommunityTest {

    private fun activity(url: String, number: Int = 0) = CommunityActivity(
        id = "c1", kind = CommunityActivityKind.REPLY, author = "bob",
        title = "选课求助", body = "看这里", url = url, createdAt = "2026-11-17T01:00:00Z", number = number,
    )

    @Test
    fun `社区回复点开直达 App 内的那一帖`() {
        val item = OwnInbox.community(activity("https://github.com/o/n/discussions/42", number = 42))
        assertEquals(AppRoute.CommunityPost(42).id, item.route)
        assertEquals("community:c1", item.id)
        assertEquals(InboxCategories.COMMUNITY, item.category)
    }

    @Test
    fun `拿不到帖子编号时才退回网页`() {
        val item = OwnInbox.community(activity("https://github.com/o/n/discussions/42"))
        assertEquals(AppRoute.Browser("https://github.com/o/n/discussions/42").id, item.route)
    }
}
