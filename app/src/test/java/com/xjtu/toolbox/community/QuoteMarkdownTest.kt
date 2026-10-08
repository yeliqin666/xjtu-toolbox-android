package com.xjtu.toolbox.community

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteMarkdownTest {

    private fun comment(body: String, author: String? = "alice") = GithubDiscussionComment(id = "c", body = body, author = author, isAnswer = false)

    @Test
    fun `标准引用块，at 写在引用外`() {
        assertEquals("> 第一行\n> 第二行\n\n@alice ", quoteMarkdown(comment("第一行\n\n第二行\n")))
    }

    @Test
    fun `超过行数截断并加省略`() {
        assertEquals("> 1\n> 2\n> …\n\n@alice ", quoteMarkdown(comment("1\n2\n3"), maxLines = 2))
    }

    @Test
    fun `跳过它引别人的部分和代码块`() {
        val body = "> @bob 原话\n\n我同意\n```kotlin\nval x = 1\n```\n再补一句"
        assertEquals("> 我同意\n> 再补一句\n\n@alice ", quoteMarkdown(comment(body)))
    }

    @Test
    fun `只有引用和代码时只留 at，作者注销时不留 at`() {
        assertEquals("@alice ", quoteMarkdown(comment("> 别人的话")))
        assertEquals("> 你好\n\n", quoteMarkdown(comment("你好", author = null)))
    }
}
