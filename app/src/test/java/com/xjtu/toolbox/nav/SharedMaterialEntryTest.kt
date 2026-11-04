package com.xjtu.toolbox.nav

import org.junit.Test
import org.junit.Assert.*

class SharedMaterialEntryTest {
    @Test fun textAndImagesGoToLocalPreviewWithoutAutoSending() {
        val target = DeepLinkRouter.sharedTarget(listOf(" 明天开班会 ", "明天开班会"),
            listOf("content://gallery/1", "content://gallery/1", "file:///private/secret"))!!
        assertEquals("明天开班会", target.prompt)
        assertEquals(listOf("content://gallery/1"), target.imageUris)
        assertEquals(AppRoute.Agent, target.route)
        assertTrue(target.sharedMaterial)
        assertFalse(target.autoSend)
    }

    @Test fun subjectTitlesBareLinksOnly() {
        // 微信转发聊天记录：主题与正文首行同义，丢掉
        val chat = DeepLinkRouter.sharedTarget(listOf("立冬 在微信上的聊天记录如下，请查收。\n\n立冬 22:57\n明天开会"), emptyList(),
            subject = "文件传输助手 和 立冬 的聊天记录")!!
        assertFalse(chat.prompt!!.contains("的聊天记录\n"))
        assertTrue(chat.prompt!!.startsWith("立冬 在微信上"))
        // 浏览器分享网页：正文只有链接，主题就是标题
        val link = DeepLinkRouter.sharedTarget(listOf("https://example.org/a"), emptyList(), subject = "教务通知")!!
        assertEquals("教务通知\n\nhttps://example.org/a", link.prompt)
    }

    @Test fun oversizedImageSharesKeepOriginalCountAndLimitImportedImages() {
        val target = DeepLinkRouter.sharedTarget(emptyList(), (1..8).map { "content://gallery/$it" })!!
        assertEquals(8, target.imageCount)
        assertEquals(4, target.imageUris.size)
        assertEquals("https://example.org/image.png", DeepLinkRouter.sharedTarget(emptyList(), listOf("https://example.org/image.png"))!!.prompt)
        assertNull(DeepLinkRouter.sharedTarget(listOf(" "), listOf("file:///private/secret")))
    }
}
