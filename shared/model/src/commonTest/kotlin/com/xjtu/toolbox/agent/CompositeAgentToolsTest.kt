package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CompositeAgentToolsTest {
    private class Fixed(private val names: List<String>, private val tag: String) : AgentTools {
        override val toolDefinitions: String =
            names.joinToString(",", "[", "]") { """{"type":"function","function":{"name":"$it","description":"","parameters":{}}}""" }
        override fun rateLimitKeyOf(toolName: String): String? = tag
        override suspend fun execute(name: String, argsJson: String): String = "$tag:$name"
        override fun memoryBlock(): String = "mem:$tag"
    }

    @Test
    fun `清单首尾相接，同名以前面为准，执行找到报了它的那份`() = runTest {
        val c = CompositeAgentTools(Fixed(listOf("a", "b"), "main"), listOf(Fixed(listOf("b", "todo"), "extra")))
        val defs = com.xjtu.toolbox.util.AppJson.parseToJsonElement(c.toolDefinitions).toString()
        assertEquals(3, Regex("\"name\"").findAll(defs).count())
        assertEquals("main:b", c.execute("b", "{}"))
        assertEquals("extra:todo", c.execute("todo", "{}"))
        assertEquals("extra", c.rateLimitKeyOf("todo"))
        assertEquals("main:nope", c.execute("nope", "{}"))
        assertTrue(c.memoryBlock() == "mem:main")
    }
}