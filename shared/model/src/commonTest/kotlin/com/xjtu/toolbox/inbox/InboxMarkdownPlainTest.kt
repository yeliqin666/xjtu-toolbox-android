package com.xjtu.toolbox.inbox

import kotlin.test.Test
import kotlin.test.assertEquals

class InboxMarkdownPlainTest {
    @Test
    fun stripsCommonMarkdown() {
        val md = """
            ## 今日简报
            - **8:00** 主楼 A-201 *高数*
            * 交 `实验报告`，见[思源](https://lms.xjtu.edu.cn)
            ---
            > 余额 12.5 元
        """.trimIndent()
        assertEquals(
            "今日简报\n· 8:00 主楼 A-201 高数\n· 交 实验报告，见思源\n余额 12.5 元",
            InboxRules.markdownToPlain(md),
        )
    }

    @Test
    fun keepsPlainTextAndArithmetic() {
        assertEquals("3*4 间隔 2 小时", InboxRules.markdownToPlain("3*4 间隔 2 小时"))
    }
}
