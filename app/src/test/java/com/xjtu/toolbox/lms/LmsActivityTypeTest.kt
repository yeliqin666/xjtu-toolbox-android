package com.xjtu.toolbox.lms

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 活动类型枚举要覆盖上游真实存在的类型。
 *
 * 列表取自 2026-09-18 对 34 门课 1631 条活动的实测：`lecture_live` 827、`lesson` 646、
 * `online_video` 44、`material` 34、`forum` 8、`page` 6、`homework` 65、`questionnaire` 1。
 * 漏一种就会落到 UNKNOWN：能被列出来但混在「其他」里，正文也不按类型展示。
 * `exam`（测试）没在实测数据里出现过，取自学堂前端 JS 的活动类型表（zh-CN 里叫「测试」）。
 */
class LmsActivityTypeTest {

    private val upstream: List<Pair<String, LmsActivityType>> = listOf(
        "homework" to LmsActivityType.HOMEWORK,
        "material" to LmsActivityType.MATERIAL,
        "lesson" to LmsActivityType.LESSON,
        "lecture_live" to LmsActivityType.LECTURE_LIVE,
        "page" to LmsActivityType.PAGE,
        "forum" to LmsActivityType.FORUM,
        "questionnaire" to LmsActivityType.QUESTIONNAIRE,
        "online_video" to LmsActivityType.ONLINE_VIDEO,
        "exam" to LmsActivityType.EXAM,
    )

    @Test
    fun fromString_mapsEveryObservedType() {
        upstream.forEach { (raw, expected) ->
            assertEquals(raw, expected, LmsActivityType.fromString(raw))
        }
    }

    @Test
    fun enumHasNoExtraOrMissingType() {
        val declared = LmsActivityType.entries.map { it.value }.toSet()
        assertEquals(upstream.map { it.first }.toSet() + "unknown", declared)
    }

    @Test
    fun fromString_unknownFallsBackInsteadOfThrowing() {
        assertEquals(LmsActivityType.UNKNOWN, LmsActivityType.fromString(""))
        assertEquals(LmsActivityType.UNKNOWN, LmsActivityType.fromString("something_new"))
    }
}
