package com.xjtu.toolbox.game.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `房主在口令验过之前不给对方任何消息`() = runBlocking {
        // 房主这一侧只坐着一个"抢先连上 GATT 的第三台设备"：它不知道该出示什么口令，
        // 只是干等着收。只要房主肯主动发一条消息，口令就等于送出去了。
        val fromGuest = Channel<String>(Channel.UNLIMITED)
        val toGuest = Channel<String>(Channel.UNLIMITED)
        val host = OnlineGameSession(
            OnlineRole.HOST, "REAL-TOKEN", GameKind.GOMOKU, ruleParam = "{\"size\":19}",
            hostFirst = false, transport = FakeTransport(outbound = toGuest, inbound = fromGuest), scope = scope,
        )
        host.start()
        delay(200)
        assertTrue("房主在验完口令前不能主动发消息", toGuest.tryReceive().isFailure)

        // 没有口令的设备只能瞎猜，猜错就该被拒。
        fromGuest.send(NetCodec.encode(NetCodec.hello("GUESS", GameKind.GOMOKU)))
        val hostState = withTimeout(5_000) { host.state.first { it is OnlineConnState.Disconnected } }
        assertTrue((hostState as OnlineConnState.Disconnected).reason.contains("口令"))
        // 回给它的拒绝里也不能夹带口令或对局参数。
        val rejection = toGuest.tryReceive().getOrNull()
        if (rejection != null) {
            assertFalse(rejection.contains("REAL-TOKEN"))
            assertFalse(rejection.contains("size"))
        }
    }

    @Test
    fun `加入方从 hello_ack 拿到房主的规则参数和先后手`() = runBlocking {
        val (hostT, guestT) = connectedPair()
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN1", GameKind.GO, ruleParam = "{\"size\":19}",
            hostFirst = false, transport = hostT, scope = scope,
        )
        val guest = OnlineGameSession(
            OnlineRole.GUEST, "TOKEN1", GameKind.GO, ruleParam = null,
            hostFirst = true, transport = guestT, scope = scope,
        )
        host.start()
        guest.start()

        withTimeout(5_000) {
            host.state.first { it is OnlineConnState.Playing }
            guest.state.first { it is OnlineConnState.Playing }
            assertEquals("{\"size\":19}", guest.resolvedRuleParam)
            assertFalse("先后手由房主决定，不能停在占位值 true 上", guest.resolvedHostFirst)
        }
    }

    @Test
    fun `握手没完成就收到着法直接断开`() = runBlocking {
        val fromGuest = Channel<String>(Channel.UNLIMITED)
        val toGuest = Channel<String>(Channel.UNLIMITED)
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = FakeTransport(outbound = toGuest, inbound = fromGuest), scope = scope,
        )
        host.start()
        // 绕过握手直接来一步棋。要是让它记进 moveLog，后面对方的正常着法会全部被当成跳号。
        fromGuest.send(NetCodec.encode(NetCodec.move(1, "7,7")))

        val hostState = withTimeout(5_000) { host.state.first { it is OnlineConnState.Disconnected } }
        assertTrue((hostState as OnlineConnState.Disconnected).reason.contains("握手"))
    }

    @Test
    fun `重复的 hello_ack 不会打断已经开下的对局`() = runBlocking {
        val guestToHost = Channel<String>(Channel.UNLIMITED)
        val hostToGuest = Channel<String>(Channel.UNLIMITED)
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = FakeTransport(outbound = hostToGuest, inbound = guestToHost), scope = scope,
        )
        val guest = OnlineGameSession(
            OnlineRole.GUEST, "TOKEN1", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = FakeTransport(outbound = guestToHost, inbound = hostToGuest), scope = scope,
        )
        host.start()
        guest.start()
        withTimeout(5_000) {
            host.state.first { it is OnlineConnState.Playing }
            guest.state.first { it is OnlineConnState.Playing }
        }

        // 迟到的重复握手包：房主侧口令早就被消费过，按老逻辑会当成校验失败把对局断掉。
        guestToHost.send(NetCodec.encode(NetCodec.helloAck(true, hostFirst = true)))
        delay(200)
        assertTrue(host.state.value is OnlineConnState.Playing)
        // 还能正常走子，说明会话语义没被这条重复包影响。events 没有 replay，先订阅再发。
        val moveArrived = async { withTimeout(5_000) { host.events.first { it is OnlineGameEvent.RemoteMove } } }
        guest.sendLocalMove("7,7")
        // 用 assertTrue 收尾（而不是 await() 的返回值）：用例方法必须返回 Unit，
        // 否则 JUnit4 会在测例类初始化时抛 InvalidTestClassError。
        assertTrue(moveArrived.await() is OnlineGameEvent.RemoteMove)
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
