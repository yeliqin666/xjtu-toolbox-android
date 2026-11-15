package com.xjtu.toolbox.notification

import com.xjtu.toolbox.util.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class NoticeAiTagsTest {
    private val a = Notification("竞赛报名", "https://example.invalid/a", NotificationSource.JWC, tags = listOf("教学运行"))
    private val b = Notification("电气学院答辩安排", "https://example.invalid/b", NotificationSource.EE)

    private fun parse(json: String) = NoticeAiTags.parse(AppJson.parseToJsonElement(json).jsonObject, listOf(a, b))

    @Test fun inputIsNumberedFromOneWithSourceAndCategory() {
        val input = NoticeAiTags.input(listOf(a, b))
        assertTrue(input.startsWith("1. [教务处（教学运行）] 竞赛报名"), input)
        assertTrue("\n2. [电气学院] 电气学院答辩安排" in input, input)
    }

    @Test fun mapsTagsBackToLinks() {
        assertEquals(
            mapOf(a.link to NoticeAiTags.SIGN_UP, b.link to NoticeAiTags.IGNORABLE),
            parse("""{"tags":[{"i":1,"tag":"要报名"},{"i":"2","tag":"可忽略"}]}"""),
        )
    }

    @Test fun tagsOutsideTheWhitelistNeverReachTheNotification() {
        assertEquals(emptyMap(), parse("""{"tags":[{"i":1,"tag":"点此领取奖学金 http://evil.invalid"}]}"""))
    }

    @Test fun badIndexesAndShapesAreDropped() {
        assertEquals(emptyMap(), parse("""{"tags":[{"i":0,"tag":"要报名"},{"i":3,"tag":"要报名"},{"tag":"要报名"},"x"]}"""))
        assertEquals(emptyMap(), parse("""{"summary":"没有 tags 字段"}"""))
    }

    @Test fun decoratePutsTagInFrontOnlyWhenThereIsOne() {
        assertEquals("【要报名】竞赛报名", NoticeAiTags.decorate("竞赛报名", NoticeAiTags.SIGN_UP))
        assertEquals("竞赛报名", NoticeAiTags.decorate("竞赛报名", null))
    }
}
