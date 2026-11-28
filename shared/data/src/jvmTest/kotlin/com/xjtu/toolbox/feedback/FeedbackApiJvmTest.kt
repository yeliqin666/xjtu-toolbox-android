package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.BASE
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.DEVICE_LABEL
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.OS_LABEL
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_ANSWER
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_CATEGORY
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_QUESTION
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.REPLY_TEXT
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.TICKET
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.VERSION_LABEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 反馈取数（[FeedbackApi]）的字段级口径：对着 `:testkit` 的 [FeedbackFakeUpstream] 逐行读。 */
class FeedbackApiJvmTest {

    private fun withFakeFeedback(block: (FakeCampusProxy) -> Unit) {
        val fake = FakeCampusProxy()
        FakeCampusProxy.installFakeUpstreams()
        FeedbackApi.config = FeedbackApi.Config(
            baseUrl = BASE,
            versionLabel = VERSION_LABEL,
            deviceLabel = DEVICE_LABEL,
            osLabel = OS_LABEL,
        )
        fake.start()
        try {
            block(fake)
        } finally {
            fake.close()
            FeedbackApi.config = null
        }
    }

    @Test
    fun `提交：八个字段口径（含版本、机型、系统三个由宿主注入的字段）`() = withFakeFeedback { fake ->
        assertTrue(FeedbackApi.isConfigured)
        runBlocking {
            FeedbackApi.submit(
                ticket = TICKET,
                category = "想要新功能",
                content = "示例反馈：希望有个夜间模式",
                contact = "example@example.edu.cn",
                anonId = "anon01",
            )
        }
        assertEquals(1, fake.feedback.submitCalls.get())
        val body = fake.feedback.lastSubmitBody.orEmpty()
        assertTrue(body.contains("\"ticket\":\"$TICKET\""), body)
        assertTrue(body.contains("\"category\":\"想要新功能\""), body)
        assertTrue(body.contains("\"content\":\"示例反馈：希望有个夜间模式\""), body)
        assertTrue(body.contains("\"contact\":\"example@example.edu.cn\""), body)
        assertTrue(body.contains("\"anonId\":\"anon01\""), body)
        assertTrue(body.contains("\"version\":\"$VERSION_LABEL\""), body)
        assertTrue(body.contains("\"device\":\"$DEVICE_LABEL\""), body)
        assertTrue(body.contains("\"system\":\"$OS_LABEL\""), body)

        // 崩溃日志显式传 version 时应覆盖默认版本串
        runBlocking {
            FeedbackApi.submit(TICKET, "崩溃", "示例堆栈", "", "anon01", version = "0.0.0-crash")
        }
        assertTrue(
            fake.feedback.lastSubmitBody.orEmpty().contains("\"version\":\"0.0.0-crash\""),
            "显式传的 version 应当覆盖注入的版本串：${fake.feedback.lastSubmitBody}",
        )
    }

    @Test
    fun `回复：按工单批量查询，只认本地那一条`() = withFakeFeedback { fake ->
        val found = runBlocking { FeedbackApi.replies(listOf(TICKET, "TNOTMINE1")) }
        assertEquals(setOf(TICKET), found.keys, "只该认本地账本里有回复的那一张工单")
        assertEquals(REPLY_TEXT, found.getValue(TICKET).text)
        assertEquals("2026-10-01 12:00", found.getValue(TICKET).time)
        assertEquals("maintainer", found.getValue(TICKET).by)
        assertTrue(fake.feedback.lastRepliesQuery.orEmpty().contains(TICKET))

        val before = fake.feedback.repliesCalls.get()
        assertTrue(runBlocking { FeedbackApi.replies(emptyList()) }.isEmpty())
        assertEquals(before, fake.feedback.repliesCalls.get(), "没有工单号时不该发请求")
    }

    @Test
    fun `上游报错时抛异常让调用方保留缓存`() = withFakeFeedback { fake ->
        fake.feedback.rejectReplies = true
        val error = assertFailsWith<IllegalStateException> { runBlocking { FeedbackApi.replies(listOf(TICKET)) } }
        assertTrue("503" in error.message.orEmpty() || "服务暂不可用" in error.message.orEmpty(), error.message)
        assertEquals(1, fake.feedback.repliesCalls.get())

        fake.feedback.rejectReplies = false
        val ok = runBlocking { FeedbackApi.replies(listOf(TICKET)) }
        assertEquals(REPLY_TEXT, ok.getValue(TICKET).text)
    }

    @Test
    fun `公开问答与实时投票：直连 Worker 端点`() = withFakeFeedback { fake ->
        val qa = runBlocking { FeedbackApi.publicQa() }
        assertEquals(1, qa.size)
        assertEquals(QA_QUESTION, qa.single().question)
        assertEquals(QA_ANSWER, qa.single().answer)
        assertEquals(QA_CATEGORY, qa.single().category)
        assertEquals("maintainer", qa.single().by)

        runBlocking { FeedbackApi.vote("2026-11-27-d10", listOf("面", "饭"), "anon01") }
        assertEquals(1, fake.feedback.voteCalls.get())
        assertTrue(fake.feedback.lastVoteBody.orEmpty().contains("\"pollId\":\"2026-11-27-d10\""))

        val results = runBlocking { FeedbackApi.pollResults(listOf("2026-11-27-d10")) }
        assertEquals(3, results.getValue("2026-11-27-d10").voters)
        assertEquals(2, results.getValue("2026-11-27-d10").counts["面"])
    }

    @Test
    fun `baseUrl 为空或 config 为 null 时 isConfigured 为 false`() {
        FeedbackApi.config = FeedbackApi.Config(
            baseUrl = "",
            versionLabel = "",
            deviceLabel = "",
            osLabel = "",
        )
        assertFalse(FeedbackApi.isConfigured)
        FeedbackApi.config = null
        assertFalse(FeedbackApi.isConfigured)
    }
}
