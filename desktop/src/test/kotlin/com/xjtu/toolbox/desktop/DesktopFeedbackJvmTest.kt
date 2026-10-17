package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.feedback.AppFeedbackSource
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.APP_ID
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.APP_SECRET
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.BASE_TOKEN
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.QA_QUESTION
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.REPLY_TABLE
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.REPLY_TEXT
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.SUBMIT_TABLE
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream.Companion.TICKET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 反馈在桌面端的装配验收：桌面注入凭据（[DesktopFeedback] 那一份，或测试里这份指向夹具的），再用两端共用的 `AppFeedbackSource` 走完真实 HTTP 链。 */
class DesktopFeedbackJvmTest {

    @Test
    fun `桌面装配：DesktopFeedback 注入的展示字段是桌面口径，凭据与构建期一致`() {
        DesktopFeedback.install()
        val cfg = assertNotNull(FeedbackApi.config, "install() 之后必须有一份 config")
        assertTrue(cfg.versionLabel.endsWith("(desktop)"), "桌面那一串要标出来：${cfg.versionLabel}")
        assertEquals(
            DesktopBuildConfig.VERSION_NAME,
            cfg.versionLabel.removeSuffix(" (desktop)"),
            "版本串的主干就是桌面包的版本号",
        )
        assertTrue(cfg.deviceLabel.isNotBlank(), "机型列要报宿主机（不假装是手机）：${cfg.deviceLabel}")
        assertTrue(cfg.osLabel.contains("Java"), "系统列带 Java 版本：${cfg.osLabel}")
        assertEquals(
            DesktopBuildConfig.FEEDBACK_APP_ID.isNotBlank() &&
                DesktopBuildConfig.FEEDBACK_APP_SECRET.isNotBlank() &&
                DesktopBuildConfig.FEEDBACK_BASE_TOKEN.isNotBlank(),
            FeedbackApi.isConfigured,
            "凭据配全 ⇔ isConfigured（搬迁前读的是 BuildConfig 那三个非空判据）",
        )
        FeedbackApi.config = null
    }

    @Test
    fun `桌面装配：注入夹具凭据之后，提交、回复、公开问答都走完真实 HTTP 链`() {
        val fake = FakeCampusProxy()
        FakeCampusProxy.installFakeUpstreams()
        FeedbackApi.config = FeedbackApi.Config(
            appId = APP_ID,
            appSecret = APP_SECRET,
            baseToken = BASE_TOKEN,
            submitTable = SUBMIT_TABLE,
            replyTable = REPLY_TABLE,
            versionLabel = "${DesktopBuildConfig.VERSION_NAME} (desktop)",
            deviceLabel = "Linux amd64（示例）",
            osLabel = "Linux 6.1 / Java 21（示例）",
        )
        fake.start()
        try {
            val source = AppFeedbackSource
            assertTrue(source.isConfigured, "注入凭据之后桌面这一条路应当可用")

            runBlocking {
                source.submit(TICKET, "功能坏了", "示例反馈：桌面端的按钮点不动", "", "anon01")
            }
            assertEquals(1, fake.feedback.tokenCalls.get(), "冷缓存下第一发要取一次令牌")
            assertTrue(
                fake.feedback.lastTokenBody.orEmpty().contains(APP_ID) &&
                    fake.feedback.lastTokenBody.orEmpty().contains(APP_SECRET),
                "取令牌那一发要带桌面注入的凭据：${fake.feedback.lastTokenBody}",
            )
            assertEquals(1, fake.feedback.submitCalls.get())
            assertTrue(
                fake.feedback.lastSubmitFields.orEmpty().contains("\"版本\":\"5.1.1 (desktop)\""),
                "版本列应当是桌面口径：${fake.feedback.lastSubmitFields}",
            )

            val replies = runBlocking { source.replies(listOf(TICKET)) }
            assertEquals(REPLY_TEXT, replies.getValue(TICKET).text)

            val qa = runBlocking { source.publicQa() }
            assertEquals(QA_QUESTION, qa.single().question)
        } finally {
            fake.close()
            FeedbackApi.config = null
        }
    }

    @Test
    fun `没配凭据时整条链降级回外链（与 Android 同一条口径）`() {
        FeedbackApi.config = FeedbackApi.Config(
            appId = "",
            appSecret = "",
            baseToken = "",
            submitTable = SUBMIT_TABLE,
            replyTable = REPLY_TABLE,
            versionLabel = "",
            deviceLabel = "",
            osLabel = "",
        )
        assertFalse(AppFeedbackSource.isConfigured, "凭据缺失 ⇒ 不可用")
        FeedbackApi.config = null
    }
}
