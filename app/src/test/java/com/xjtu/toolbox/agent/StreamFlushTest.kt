package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式写出节流的契约测试。
 *
 * 这个判定同时承担两个相反的责任，任何一边坏掉都是用户可见的 bug：
 * - 太密 → 每个 token 都重排一次整个会话列表（这次优化的起因）；
 * - 太稀或漏掉强制 → 收尾/flush 时把最后一段文字丢掉，回复少字。
 */
class StreamFlushTest {

    private val interval = AgentViewModel.STREAM_FLUSH_INTERVAL_NS

    @Test
    fun `本段首次写入必然放行`() {
        // lastFlushNs = 0 表示这段还没写过，首个增量要立刻上屏，不能先显示空气泡
        assertTrue(streamFlushDue(nowNs = 1L, lastFlushNs = 0L, force = false))
    }

    @Test
    fun `间隔未到时不写`() {
        val last = 1_000_000_000L
        assertFalse(streamFlushDue(nowNs = last + interval - 1, lastFlushNs = last, force = false))
    }

    @Test
    fun `间隔到达时写入`() {
        val last = 1_000_000_000L
        assertTrue(streamFlushDue(nowNs = last + interval, lastFlushNs = last, force = false))
        assertTrue(streamFlushDue(nowNs = last + interval * 3, lastFlushNs = last, force = false))
    }

    @Test
    fun `强制写入无视间隔`() {
        val last = 1_000_000_000L
        // 刚写过 1ns 也要放行：收尾、换段、停止都靠这条保证不丢尾段
        assertTrue(streamFlushDue(nowNs = last + 1, lastFlushNs = last, force = true))
    }

    @Test
    fun `一秒钟的 token 洪峰被压到约 60 次写入`() {
        // 模拟真实流式：每 5ms 一个 token，持续 1 秒。
        // 不节流是 200 次列表重排；节流后应接近 1000/16 ≈ 62 次。
        val tokenInterval = 5_000_000L
        var last = 0L
        var writes = 0
        var t = 0L
        repeat(200) {
            t += tokenInterval
            if (streamFlushDue(t, last, force = false)) {
                writes++
                last = t
            }
        }
        assertTrue("写入次数 $writes 应显著少于 200 次", writes < 80)
        assertTrue("写入次数 $writes 不该低于 50（否则可见卡顿）", writes >= 50)
    }

    @Test
    fun `token 稀疏时每个都写`() {
        // 每个 token 间隔超过一帧：不该有任何延迟感
        var last = 0L
        var writes = 0
        var t = 0L
        repeat(20) {
            t += 50_000_000L   // 50ms
            if (streamFlushDue(t, last, force = false)) {
                writes++
                last = t
            }
        }
        assertEquals(20, writes)
    }
}
