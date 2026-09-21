package com.xjtu.toolbox.game.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTrackerTest {

    @Test
    fun `3 秒到点该发 ping`() {
        val t = HeartbeatTracker(pingIntervalMs = 3_000, timeoutMs = 15_000)
        t.start(0)
        assertFalse(t.shouldSendPing(2_999))
        assertTrue(t.shouldSendPing(3_000))
    }

    @Test
    fun `15 秒没消息就判超时`() {
        val t = HeartbeatTracker(pingIntervalMs = 3_000, timeoutMs = 15_000)
        t.start(0)
        assertFalse(t.isTimedOut(14_999))
        assertTrue(t.isTimedOut(15_000))
    }

    @Test
    fun `收到消息会重置超时计时`() {
        val t = HeartbeatTracker(pingIntervalMs = 3_000, timeoutMs = 15_000)
        t.start(0)
        t.onMessageReceived(10_000)
        assertFalse(t.isTimedOut(20_000)) // 距离上次收到消息只过了 10 秒
        assertTrue(t.isTimedOut(25_000))
    }

    @Test
    fun `发过 ping 之后不会立刻又想发`() {
        val t = HeartbeatTracker(pingIntervalMs = 3_000, timeoutMs = 15_000)
        t.start(0)
        t.onPingSent(3_000)
        assertFalse(t.shouldSendPing(3_500))
        assertTrue(t.shouldSendPing(6_000))
    }
}
