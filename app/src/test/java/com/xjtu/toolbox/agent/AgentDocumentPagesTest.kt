package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentDocumentPagesTest {
    @Test
    fun `单页不会扩成连续六页 离散页保留请求顺序且去重`() {
        assertEquals(listOf(5), AgentDocumentPages.select("5", 30, 4))
        assertEquals(listOf(7, 1, 3, 4), AgentDocumentPages.select("7,1,3-4,3", 30, 4))
        assertEquals(listOf(1, 2), AgentDocumentPages.select(null, 30, 4))
        assertEquals(listOf(1), AgentDocumentPages.select(null, 1, 4))
    }

    @Test
    fun `不静默纠正越界 反向 非法格式或超量选择`() {
        listOf("0", "31", "8-3", "1-5", "1,3,5,7,9", "第5页", "3-99999999999999999", "5,").forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { AgentDocumentPages.select(raw, 30, 4) }
        }
    }
}
