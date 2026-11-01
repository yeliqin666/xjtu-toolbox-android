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

    @Test fun oversizedImageSharesKeepOriginalCountAndLimitImportedImages() {
        val target = DeepLinkRouter.sharedTarget(emptyList(), (1..8).map { "content://gallery/$it" })!!
        assertEquals(8, target.imageCount)
        assertEquals(4, target.imageUris.size)
        assertEquals("https://example.org/image.png", DeepLinkRouter.sharedTarget(emptyList(), listOf("https://example.org/image.png"))!!.prompt)
        assertNull(DeepLinkRouter.sharedTarget(listOf(" "), listOf("file:///private/secret")))
    }
}
