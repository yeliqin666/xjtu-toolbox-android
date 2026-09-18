package com.xjtu.toolbox.lms

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 活动正文的取法。上游两种载体，走错一个就是「页面点进去空白」：
 * - 作业 / 课件 → `data.description`
 * - 页面型（课程简介、教学进度、课程考核构成…）→ `data.content`
 */
class LmsActivityBodyTest {

    private fun data(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun homework_usesDescription() {
        val body = lmsActivityBody(data("""{"description":"<p>第一章习题</p>","content":""}"""))
        assertEquals("<p>第一章习题</p>", body)
    }

    @Test
    fun page_usesContent() {
        // 页面型的 description 实测长度 0，正文只在 content 里
        val body = lmsActivityBody(data("""{"description":"","content":"<p>总评成绩100%=平时成绩40%…</p>"}"""))
        assertEquals("<p>总评成绩100%=平时成绩40%…</p>", body)
    }

    @Test
    fun blankDescription_fallsBackToContent() {
        val body = lmsActivityBody(data("""{"description":"   ","content":"<p>教学进度</p>"}"""))
        assertEquals("<p>教学进度</p>", body)
    }

    @Test
    fun forum_usesDescription() {
        val body = lmsActivityBody(data("""{"description":"<p>Please post one question here.</p>"}"""))
        assertEquals("<p>Please post one question here.</p>", body)
    }

    @Test
    fun nothingToShow_returnsNull() {
        assertNull(lmsActivityBody(data("""{"description":"","content":null}""")))
        assertNull(lmsActivityBody(data("""{}""")))
        assertNull(lmsActivityBody(null))
    }
}
