package com.xjtu.toolbox.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDocumentCacheTest {
    @Test
    fun `同附件复用 超时重取且账号隔离`() = runBlocking {
        var time = 0L
        var calls = 0
        val cache = AgentDocumentCache(now = { time })
        suspend fun read(key: String) = cache.getOrLoad(key) {
            calls++
            AgentDocumentCache.Document("课件.pdf", byteArrayOf(1))
        }
        read("accountA:file1")
        read("accountA:file1")
        assertEquals(1, calls)
        read("accountB:file1")
        assertEquals(2, calls)
        time = 600_001
        read("accountA:file1")
        assertEquals(3, calls)
    }

    @Test
    fun `失败不缓存 超过预算按最近使用淘汰`() = runBlocking {
        val cache = AgentDocumentCache(maxBytes = 4)
        assertNull(cache.getOrLoad("bad") { null })
        cache.getOrLoad("bad") { AgentDocumentCache.Document("ok", byteArrayOf(1)) }
        cache.getOrLoad("next") { AgentDocumentCache.Document("next", ByteArray(4)) }
        var reloaded = false
        cache.getOrLoad("bad") { reloaded = true; AgentDocumentCache.Document("ok", byteArrayOf(1)) }
        assertEquals(true, reloaded)
    }
}
