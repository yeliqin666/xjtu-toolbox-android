package com.xjtu.toolbox.game.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用内存里的一对 [Channel] 模拟"已经连通"的传输层，不依赖真实 socket/GATT——
 * [OnlineGameSession] 本身就是照着能这样测试来设计的（见它类头的注释）。
 */
private class FakeTransport(
    private val outbound: Channel<String>,
    private val inbound: Channel<String>,
) : OnlineTransport {
    override val channelLabel: String = "fake"
    override val incoming: Flow<String> = inbound.receiveAsFlow()
    override suspend fun send(line: String) {
        outbound.send(line)
    }
    override fun close() {
        outbound.close()
        inbound.close()
    }
}

/** 建一对首尾相连的假传输：a 发的东西 b 收得到，反过来也一样。 */
private fun connectedPair(): Pair<OnlineTransport, OnlineTransport> {
    val aToB = Channel<String>(Channel.UNLIMITED)
    val bToA = Channel<String>(Channel.UNLIMITED)
    return FakeTransport(outbound = aToB, inbound = bToA) to FakeTransport(outbound = bToA, inbound = aToB)
}

class OnlineGameSessionTest {

    private val scopeJob = Job()
    private val scope = CoroutineScope(scopeJob)

    @After
    fun tearDown() {
        scopeJob.cancel()
    }

    @Test
    fun `握手成功后双方都进入 Playing`() = runBlocking {
        val (hostT, guestT) = connectedPair()
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = hostT, scope = scope,
        )
        val guest = OnlineGameSession(
            OnlineRole.GUEST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = guestT, scope = scope,
        )
        host.start()
        guest.start()

        withTimeout(5_000) {
            assertTrue(host.state.first { it is OnlineConnState.Playing } is OnlineConnState.Playing)
            assertTrue(guest.state.first { it is OnlineConnState.Playing } is OnlineConnState.Playing)
        }
    }

    @Test
    fun `口令不对，房主拒绝并断开`() = runBlocking {
        val (hostT, guestT) = connectedPair()
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN-RIGHT", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = hostT, scope = scope,
        )
        val guest = OnlineGameSession(
            OnlineRole.GUEST, "TOKEN-WRONG", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = guestT, scope = scope,
        )
        host.start()
        guest.start()

        withTimeout(5_000) {
            val hostState = host.state.first { it is OnlineConnState.Disconnected }
            assertTrue((hostState as OnlineConnState.Disconnected).reason.contains("口令"))
        }
    }

    @Test
    fun `本地着法能同步到对方并带上递增序号`() = runBlocking {
        val (hostT, guestT) = connectedPair()
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = hostT, scope = scope,
        )
        val guest = OnlineGameSession(
            OnlineRole.GUEST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = guestT, scope = scope,
        )

        // 在 start() 之前就订阅 events：guest.events 是没有 replay 的 SharedFlow，
        // 订阅晚了会错过已经发生的事件，所以从对象一造出来就开始收集，不留竞态窗口。
        // 收集协程跑在 scope 的默认调度器（多线程）上，下面的 while 在测试线程里同时遍历，
        // 普通 ArrayList 会抛 ConcurrentModificationException，得用线程安全的列表。
        val received = java.util.concurrent.CopyOnWriteArrayList<OnlineGameEvent>()
        val collectJob = scope.launch { guest.events.collect { received.add(it) } }

        host.start()
        guest.start()
        withTimeout(5_000) {
            host.state.first { it is OnlineConnState.Playing }
            guest.state.first { it is OnlineConnState.Playing }

            host.sendLocalMove("7,7")
            host.sendLocalMove("7,8")
            while (received.count { it is OnlineGameEvent.RemoteMove } < 2) {
                kotlinx.coroutines.yield()
            }
        }
        collectJob.cancel()

        val moves = received.filterIsInstance<OnlineGameEvent.RemoteMove>()
        assertEquals(listOf(1, 2), moves.map { it.seq })
        assertEquals(listOf("7,7", "7,8"), moves.map { it.code })
    }
}
