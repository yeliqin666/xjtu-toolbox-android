package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.APP_ID
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.APP_SECRET
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.BASE_TOKEN
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.DEVICE_LABEL
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.OS_LABEL
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_ANSWER
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_CATEGORY
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_PRIVATE_QUESTION
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_QUESTION
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.REPLY_TABLE
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.REPLY_TEXT
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.SUBMIT_TABLE
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.TICKET
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.VERSION_LABEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 反馈取数（[FeedbackApi]）的字段级口径：对着 `:testkit` 的 [FeedbackFakeUpstream] 逐行读。 */
class FeedbackApiJvmTest {

    private fun withFakeFeedback(block: (FakeCampusProxy) -> Unit) {
        val fake = FakeCampusProxy()
        // 两件进程级前置必须在碰 HttpClients（FeedbackApi.client 是 by lazy）之前装。
        FakeCampusProxy.installFakeUpstreams()
        FeedbackApi.config = FeedbackApi.Config(
            appId = APP_ID,
            appSecret = APP_SECRET,
            baseToken = BASE_TOKEN,
            submitTable = SUBMIT_TABLE,
            replyTable = REPLY_TABLE,
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
    fun `登录形态：app_id + app_secret 换租户令牌，有效期内只用一张`() = withFakeFeedback { fake ->
        assertTrue(FeedbackApi.isConfigured, "凭据配全了就该认成可用")

        // ⚠️ 令牌缓存是进程级单例，整个 jvmTest 共用一个 JVM ⇒ 只断言第二次提交不再取令牌。
        val before = fake.feedback.tokenCalls.get()
        runBlocking {
            FeedbackApi.submit(TICKET, "功能坏了", "示例反馈：页面上的按钮点不动", "", "anon01")
        }
        val afterFirst = fake.feedback.tokenCalls.get()
        assertTrue(
            afterFirst == before || afterFirst == before + 1,
            "提交最多换一张令牌：$before -> $afterFirst",
        )
        if (afterFirst > before) {
            // 冷缓存那一发：请求体里必须带 app_id 与 app_secret（就是这条链的「登录凭据」）
            assertTrue(
                fake.feedback.lastTokenBody.orEmpty().contains(APP_ID) &&
                    fake.feedback.lastTokenBody.orEmpty().contains(APP_SECRET),
                "取令牌那一发的请求体里必须带 app_id 与 app_secret：${fake.feedback.lastTokenBody}",
            )
        }
        assertEquals(1, fake.feedback.submitCalls.get())

        // 第二次提交：令牌还在有效期内（expire 7200，提前 5 分钟过期）⇒ 不该再换一张
        runBlocking {
            FeedbackApi.submit(TICKET, "功能坏了", "第二条", "", "anon01")
        }
        assertEquals(afterFirst, fake.feedback.tokenCalls.get(), "令牌有效期内不该重复取")
        assertEquals(2, fake.feedback.submitCalls.get())
    }

    @Test
    fun `提交：fields 的八个列口径（含版本、机型、系统三个由宿主注入的列）`() = withFakeFeedback { fake ->
        runBlocking {
            FeedbackApi.submit(
                ticket = TICKET,
                category = "想要新功能",
                content = "示例反馈：希望有个夜间模式",
                contact = "example@example.edu.cn",
                anonId = "anon01",
            )
        }
        val fields = fake.feedback.lastSubmitFields.orEmpty()
        assertTrue(fields.contains("\"工单号\":\"$TICKET\""), fields)
        assertTrue(fields.contains("\"类型\":\"想要新功能\""), fields)
        assertTrue(fields.contains("\"内容\":\"示例反馈：希望有个夜间模式\""), fields)
        assertTrue(fields.contains("\"联系方式\":\"example@example.edu.cn\""), fields)
        assertTrue(fields.contains("\"匿名ID\":\"anon01\""), fields)
        // 没传 version ⇒ 用宿主注入的那一串（Android 是 "5.1.1 (89)"，桌面是 "5.1.1 (desktop)"）
        assertTrue(fields.contains("\"版本\":\"$VERSION_LABEL\""), fields)
        assertTrue(fields.contains("\"机型\":\"$DEVICE_LABEL\""), fields)
        assertTrue(fields.contains("\"系统\":\"$OS_LABEL\""), fields)

        // 崩溃日志那条路会显式传崩溃时的版本 ⇒ 覆盖掉注入的版本串
        runBlocking {
            FeedbackApi.submit(TICKET, "崩溃", "示例堆栈", "", "anon01", version = "0.0.0-crash")
        }
        assertTrue(
            fake.feedback.lastSubmitFields.orEmpty().contains("\"版本\":\"0.0.0-crash\""),
            "显式传的 version 应当覆盖注入的版本串：${fake.feedback.lastSubmitFields}",
        )
    }

    @Test
    fun `回复：富文本片段数组拼成纯文本，工单号只认本地那一条`() = withFakeFeedback { fake ->
        val found = runBlocking { FeedbackApi.replies(listOf(TICKET, "TNOTMINE1")) }
        assertEquals(setOf(TICKET), found.keys, "只该认本地账本里的那一张工单")
        assertEquals(REPLY_TEXT, found.getValue(TICKET).text)
        assertEquals("2026-10-01 12:00", found.getValue(TICKET).time)
        assertTrue(fake.feedback.lastSearchBody.orEmpty().contains(TICKET), fake.feedback.lastSearchBody.orEmpty())
        assertTrue(fake.feedback.lastSearchBody.orEmpty().contains("工单号"), fake.feedback.lastSearchBody.orEmpty())

        val before = fake.feedback.searchCalls.get()
        assertTrue(runBlocking { FeedbackApi.replies(emptyList()) }.isEmpty())
        assertEquals(before, fake.feedback.searchCalls.get(), "没有工单号时不该发请求")
    }


    @Test
    fun `filter 被飞书拒（1254018）：本版只记一行日志、不重试（搬动前后同构）`() = withFakeFeedback { fake ->
        fake.feedback.rejectFilter = true
        val found = runBlocking { FeedbackApi.replies(listOf(TICKET)) }
        // 钉的是现有行为：`continue` 回到 do/while 条件，pageToken 刚被置空 ⇒ 循环当场结束。
        assertEquals(1, fake.feedback.searchCalls.get(), "被拒之后不再重试")
        assertEquals(emptyMap(), found, "兜底没生效 ⇒ 这一版拿不到回复")

        // 反向对照：上游不拒时同一条链拿得到回复（证明上面是兜底没生效，不是解析坏了）
        fake.feedback.rejectFilter = false
        fake.feedback.searchCalls.set(0)
        val ok = runBlocking { FeedbackApi.replies(listOf(TICKET)) }
        assertEquals(1, fake.feedback.searchCalls.get())
        assertEquals(REPLY_TEXT, ok.getValue(TICKET).text)
    }

    @Test
    fun `公开问答：只认勾了「公开」的那一条`() = withFakeFeedback { fake ->
        val qa = runBlocking { FeedbackApi.publicQa() }
        assertEquals(1, qa.size, "夹具里只有一条勾了公开：$qa")
        val only = qa.single()
        assertEquals(QA_QUESTION, only.question)
        assertEquals(QA_ANSWER, only.answer)
        assertEquals(QA_CATEGORY, only.category)
        assertFalse(
            qa.any { it.question == QA_PRIVATE_QUESTION },
            "没勾公开的那条绝不能出现在屏上（那是「绝不退回整表拉取」那条判据的反面）",
        )
        assertTrue(fake.feedback.lastSearchBody.orEmpty().contains("公开"), fake.feedback.lastSearchBody.orEmpty())
    }

    @Test
    fun `凭据没配全时 isConfigured 为 false（整条链降级回外链）`() {
        FeedbackApi.config = FeedbackApi.Config(
            appId = "",
            appSecret = APP_SECRET,
            baseToken = BASE_TOKEN,
            submitTable = SUBMIT_TABLE,
            replyTable = REPLY_TABLE,
            versionLabel = "",
            deviceLabel = "",
            osLabel = "",
        )
        assertFalse(FeedbackApi.isConfigured, "app_id 空 ⇒ 不可用（搬迁前 BuildConfig 那三个判据同义）")
        FeedbackApi.config = null
        assertFalse(FeedbackApi.isConfigured, "没注入凭据 ⇒ 不可用")
    }
}
