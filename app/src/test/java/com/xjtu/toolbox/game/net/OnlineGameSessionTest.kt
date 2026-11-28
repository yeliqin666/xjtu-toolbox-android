package com.xjtu.toolbox.game.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        fromGuest.send(NetCodec.encode(NetCodec.hello(HandshakeAuth.newNonce(), "00".repeat(32), GameKind.GOMOKU)))
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
    fun `加入方不会把口令交给冒充房主的设备`() = runBlocking {
        // 加入方只按服务 UUID 找房主，而这个 UUID 是公开广播的——对面完全可能是一台伪造的外设。
        // 所以它发出去的第一包必须是"用口令算的证明"，不能是口令本身。
        val guestToHost = Channel<String>(Channel.UNLIMITED)
        val hostToGuest = Channel<String>(Channel.UNLIMITED)
        val guest = OnlineGameSession(
            OnlineRole.GUEST, "REAL-TOKEN", GameKind.GOMOKU, ruleParam = null,
            hostFirst = true, transport = FakeTransport(outbound = guestToHost, inbound = hostToGuest), scope = scope,
        )
        guest.start()

        val hello = withTimeout(5_000) { guestToHost.receive() }
        assertFalse("口令本身不能出现在链路上", hello.contains("REAL-TOKEN"))
        assertTrue("至少要带上一次性的 nonce 和证明", hello.contains("proof"))

        // 假房主不知道该出示什么：它拿不到真口令，只能编一段证明应答。
        // 加入方验不过就必须断开，绝不能把棋盘打开——那样用户会对着一台不存在的对手下完一整盘。
        hostToGuest.send(
            NetCodec.encode(
                NetCodec.helloAck(
                    ok = true,
                    nonce = HandshakeAuth.newNonce(),
                    proof = HandshakeAuth.hostProof("GUESSED-TOKEN", "whatever", HandshakeAuth.newNonce()),
                    rule = "{\"size\":19}",
                    hostFirst = false,
                ),
            ),
        )

        val state = withTimeout(5_000) { guest.state.first { it !is OnlineConnState.Handshaking } }
        assertTrue("假房主不能把加入方带进 Playing", state is OnlineConnState.Disconnected)
        assertTrue((state as OnlineConnState.Disconnected).reason.contains("冒充"))
        // 假房主塞进来的对局参数一条也不能生效
        assertNull(guest.resolvedRuleParam)
        assertTrue(guest.resolvedHostFirst)
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
    fun `已开下的对局忽略重复和迟到的握手包`() = runBlocking {
        val guestToHost = Channel<String>(Channel.UNLIMITED)
        val hostToGuest = Channel<String>(Channel.UNLIMITED)
        val host = OnlineGameSession(
            OnlineRole.HOST, "TOKEN1", GameKind.GOMOKU, ruleParam = "{\"size\":9}",
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
        assertEquals("{\"size\":9}", guest.resolvedRuleParam)

        // 1) 往**房主**重发一条 hello：证明本身完全合法（口令对、nonce 新），只是口令早就被消费过。
        //    没有"Playing 之后忽略握手包"这道门，房主会把它当成校验失败，把正在下的棋断掉。
        val lateNonce = HandshakeAuth.newNonce()
        guestToHost.send(
            NetCodec.encode(NetCodec.hello(lateNonce, HandshakeAuth.guestProof("TOKEN1", lateNonce), GameKind.GOMOKU)),
        )
        delay(200)
        assertTrue("迟到的 hello 不能把正在下的对局打断", host.state.value is OnlineConnState.Playing)

        // 2) 往**加入方**重发一条参数不同的 hello_ack。证明是编的也无所谓——门禁在验证明之前就该拦住它，
        //    否则房主下发的规则参数和先后手会被改写，两边按不同的盘开局。
        hostToGuest.send(
            NetCodec.encode(
                NetCodec.helloAck(
                    ok = true,
                    nonce = HandshakeAuth.newNonce(),
                    proof = "00".repeat(32),
                    rule = "{\"size\":19}",
                    hostFirst = false,
                ),
            ),
        )
        delay(200)
        assertTrue("迟到的 hello_ack 不能让加入方掉线", guest.state.value is OnlineConnState.Playing)
        assertEquals("已确认的规则参数不能被改写", "{\"size\":9}", guest.resolvedRuleParam)
        assertTrue("已确认的先后手不能被改写", guest.resolvedHostFirst)

        // 3) 对局还得能正常走：会话语义没被这两条迟到包破坏。
        // events 没有 replay：必须先把订阅建好再发。用 UNDISPATCHED 让 collect 在挂起前就完成订阅，
        // 否则 runBlocking 的线程在 sendLocalMove 之前没有真正的挂起点，订阅协程还没跑，这一步就被漏掉。
        val received = java.util.concurrent.CopyOnWriteArrayList<OnlineGameEvent>()
        val collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            guest.events.collect { received.add(it) }
        }
        host.sendLocalMove("7,7")
        withTimeout(5_000) {
            while (received.none { it is OnlineGameEvent.RemoteMove }) yield()
        }
        collectJob.cancel()
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
