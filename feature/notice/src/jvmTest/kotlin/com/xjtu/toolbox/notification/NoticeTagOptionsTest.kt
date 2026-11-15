package com.xjtu.toolbox.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class NoticeTagOptionsTest {
    private fun notice(vararg tags: String) =
        Notification("t", "https://example.invalid/t", NotificationSource.JWC, tags = tags.toList())

    @Test fun mostCommonFirstThenByName() {
        assertEquals(
            listOf("教学运行", "实践", "考试"),
            noticeTagOptions(listOf(notice("考试"), notice("教学运行"), notice("实践"), notice("教学运行"))),
        )
    }

    @Test fun noticesWithoutTagsOfferNothing() {
        assertEquals(emptyList(), noticeTagOptions(listOf(notice())))
    }
}
