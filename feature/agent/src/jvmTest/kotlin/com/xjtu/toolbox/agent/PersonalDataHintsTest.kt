package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalDataHintsTest {
    @Test
    fun `认出手机号、身份证号和学号`() {
        assertEquals(listOf("手机号"), personalDataHints("""{"phone":"13812345678"}"""))
        assertEquals(listOf("身份证号"), personalDataHints("""{"id":"61010219990101123X"}"""))
        assertEquals(listOf("学号"), personalDataHints("""{"sid":"2203112345"}"""))
    }

    @Test
    fun `普通参数不报`() {
        assertEquals(emptyList(), personalDataHints("""{"query":"图书馆开放时间","limit":8,"date":"20261028"}"""))
    }
}
