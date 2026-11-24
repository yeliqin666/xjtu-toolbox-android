package com.xjtu.toolbox.community

import com.xjtu.toolbox.community.CommunityActivityKind.MENTION
import com.xjtu.toolbox.community.CommunityActivityKind.REPLY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CommunityActivityTest {
    private fun item(id: String, kind: CommunityActivityKind, at: String = "2026-10-27T00:00:0${id.last()}Z") =
        CommunityActivity(id, kind, "someone", "帖子", "正文", "https://example.invalid/$id", at)

    private val both = setOf(REPLY, MENTION)

    @Test
    fun firstRunOnlyRecordsBaseline() {
        val delta = communityDelta(listOf(item("r1", REPLY), item("m1", MENTION)), both, emptyMap(), emptySet())
        assertTrue(delta.fresh.isEmpty())
        assertEquals(both, delta.baselined)
        assertEquals(listOf("r1"), delta.seen[REPLY])
        assertEquals(listOf("m1"), delta.seen[MENTION])
    }

    @Test
    fun onlyUnseenItemsAreFreshAndEachIsPushedOnce() {
        val seen = mapOf(REPLY to listOf("r1"), MENTION to listOf("m1"))
        val items = listOf(item("r1", REPLY), item("r2", REPLY), item("m1", MENTION), item("r2", MENTION))
        val delta = communityDelta(items, both, seen, both)
        assertEquals(listOf("r2"), delta.fresh.map { it.id })
        val again = communityDelta(items, both, delta.seen, delta.baselined)
        assertTrue(again.fresh.isEmpty())
    }

    @Test
    fun switchesAreIndependent() {
        val items = listOf(item("r2", REPLY), item("m2", MENTION))
        val replyOnly = communityDelta(items, setOf(REPLY), emptyMap(), both)
        assertEquals(listOf("r2"), replyOnly.fresh.map { it.id })
        assertFalse(MENTION in replyOnly.seen)
        // 后打开的那项单独建基线，不把历史 @ 补推出来
        val mentionLater = communityDelta(items, both, replyOnly.seen, setOf(REPLY))
        assertTrue(mentionLater.fresh.isEmpty())
        assertEquals(both, mentionLater.baselined)
    }

    @Test
    fun mentionNeedsAWholeLogin() {
        assertTrue(mentions("求助 @Alice 看看", "alice"))
        assertTrue(mentions("@alice", "alice"))
        assertFalse(mentions("@alice-bob", "alice"))
        assertFalse(mentions("mail@alice.com 不是 @", "alice"))
        assertFalse(mentions("github.com/alice", "alice"))
    }

    @Test
    fun graphQlActivityClassifiesRepliesAndMentionsAndSkipsMine() = runTest {
        var sent = ""
        val repo = GraphQlGithubDiscussionsRepository(
            token = { "t" },
            transport = { _, _, body -> sent = body; GithubHttpResponse(200, RESPONSE) },
        )
        val items = repo.activity("o", "n", "me").getOrThrow()
        assertTrue("author:me" in sent && "\\\"@me\\\"" in sent, sent)
        assertEquals(
            listOf("c1" to REPLY, "r1" to REPLY, "d2" to MENTION, "c3" to MENTION),
            items.map { it.id to it.kind },
        )
        assertEquals("我的帖子", items.first().title)
        // 帖子编号一并带回来：收纳里点开要在 App 内直达那一帖，不再丢给浏览器
        assertTrue("number" in sent, "查询必须带 number 字段：$sent")
        assertEquals(42, items.first().number)
        assertEquals(7, items.first { it.kind == MENTION }.number)
    }

    @Test
    fun discussionNumberAndAnchorComeFromTheUrl() {
        assertEquals(42, discussionNumberOf("https://github.com/o/n/discussions/42"))
        assertEquals(42, discussionNumberOf("https://github.com/o/n/discussions/42#discussioncomment-123"))
        assertEquals(null, discussionNumberOf("https://github.com/o/n/issues/42"))
        assertEquals(null, discussionNumberOf("https://github.com/o/n/discussions/"))
        assertEquals("discussioncomment-123", discussionCommentAnchor("https://github.com/o/n/discussions/42#discussioncomment-123"))
        assertEquals(null, discussionCommentAnchor("https://github.com/o/n/discussions/42"))
    }

    private companion object {
        fun node(id: String, login: String, body: String, extra: String = "") =
            """{"id":"$id","url":"u/$id","body":"$body","createdAt":"2026-10-27T00:00:00Z","author":{"login":"$login"}$extra}"""

        fun post(id: String, login: String, body: String, comments: String) =
            node(id, login, body, ""","number":${if (login == "me") 42 else 7},"title":"${if (login == "me") "我的帖子" else "别人的帖子"}","comments":{"nodes":[$comments]}""")

        fun comment(id: String, login: String, body: String, replies: String = "") =
            node(id, login, body, ""","replies":{"nodes":[$replies]}""")

        val RESPONSE = """{"data":{
            "mine":{"nodes":[${post("d1", "me", "正文", comment("c1", "bob", "好帖", node("r1", "carol", "同意") + "," + node("r0", "me", "谢谢")) + "," + comment("c0", "ME", "自己顶"))}]},
            "at":{"nodes":[${post("d2", "bob", "@me 看看", comment("c3", "carol", "+1 @me") + "," + comment("c4", "dave", "无关"))}]}
        }}"""
    }
}
