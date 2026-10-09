package com.xjtu.toolbox.lms

import org.junit.Assert.assertEquals
import org.junit.Test

/** 作业描述转纯文本：段落、换行要留住（#127：多段描述被压成一行）。 */
class LmsHtmlTextTest {

    @Test
    fun paragraphsWithInlineSpans_keepLineBreaks() {
        val html = "<p><span>1. 完成习题</span></p><p><span>2. 提交报告</span></p>"
        assertEquals("1. 完成习题\n2. 提交报告", htmlToPlainText(html))
    }

    @Test
    fun br_breaksLine() {
        assertEquals("第一行\n第二行", htmlToPlainText("<p>第一行<br>第二行</p>"))
    }

    @Test
    fun sourceWhitespace_isNotALineBreak() {
        assertEquals("截止 周五", htmlToPlainText("<p>截止\n      周五</p>"))
    }

    @Test
    fun manyEmptyParagraphs_collapseToOneBlankLine() {
        assertEquals("上\n\n下", htmlToPlainText("<p>上</p><p></p><p>&nbsp;</p><p></p><div><p>下</p></div>"))
    }
}
