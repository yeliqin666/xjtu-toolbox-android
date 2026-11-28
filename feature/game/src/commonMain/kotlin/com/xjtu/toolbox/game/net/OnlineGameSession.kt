package com.xjtu.toolbox.game.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** 我方在这局里的角色：房主先发 hello_ack，加入方先发 hello。 */
enum class OnlineRole { HOST, GUEST }

/** 连接生命周期状态，UI 照这个换文案，不允许出现"一直转圈却不说明白"的状态。 */
sealed class OnlineConnState {
    object Handshaking : OnlineConnState()
    object Playing : OnlineConnState()
    /** [reason] 是能直接展示给用户的一句话。 */
    data class Disconnected(val reason: String) : OnlineConnState()
}

/** 对局过程中发生的、UI 需要响应的事件。着法本身也走这里，而不是单独一个回调。 */
sealed class OnlineGameEvent {
    data class RemoteMove(val seq: Int, val code: String) : OnlineGameEvent()
    object UndoRequested : OnlineGameEvent()
    data class UndoAnswered(val accepted: Boolean) : OnlineGameEvent()
    object Resigned : OnlineGameEvent()
    object DrawRequested : OnlineGameEvent()
    data class DrawAnswered(val accepted: Boolean) : OnlineGameEvent()
}

/**
 * 联机对局的协议状态机：握手、着法序号、心跳、断线重连补发，都在这一层，
 * 不认识任何一种棋的规则——那是 [OnlineRuleAdapter] 的事，这里只负责"这条协议消息
 * 该怎么处理"。这样设计是为了让这个类本身能脱离 Android 和具体棋盘写单测
 * （用一个内存里的假 [OnlineTransport] 模拟两端）。
 *
 * 用法：
 * 1. 建立好 [OnlineTransport]（现在只有 BLE 一种）之后 new 一个 session；
 * 2. 调 [start]，它会自己完成 hello 握手（含断线重连时的补发）；
 * 3. 本地走了一步，调 [sendLocalMove]；
 * 4. 收 [OnlineGameEvent.RemoteMove] 之后，调用方**必须**用本地的规则引擎重放校验一遍——
 *    不合法就调 [reportIllegalMoveAndClose]，这是"双方各自用同一规则引擎校验"的落地点，
 *    session 自己不认识棋子，判断不了合法性。
 */
class OnlineGameSession(
    private val role: OnlineRole,
    private val token: String,
    private val gameKind: GameKind,
    private val ruleParam: String?,
    private val hostFirst: Boolean,
    private var transport: OnlineTransport,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val moveLog = NetMoveLog()
    private val heartbeat = HeartbeatTracker()
    private val hostToken = if (role == OnlineRole.HOST) OneTimeToken() else null

    /** 加入方每次握手新生成的一次性随机数：证明绑在它上面，别人抄走也重放不了。 */
    private val guestNonce: String? = if (role == OnlineRole.GUEST) HandshakeAuth.newNonce() else null

    private val _state = MutableStateFlow<OnlineConnState>(OnlineConnState.Handshaking)
    val state: StateFlow<OnlineConnState> = _state

    private val _events = MutableSharedFlow<OnlineGameEvent>(extraBufferCapacity = 16)
    val events = _events

    /** 对方是不是先手：由房主决定并写进 hello_ack，加入方从 ack 里读到。UI 用它来分配棋子颜色。 */
    var resolvedHostFirst: Boolean = hostFirst
        private set

    var resolvedRuleParam: String? = ruleParam
        private set

    private var job: Job? = null
    @Volatile private var closed = false

    /**
     * 读循环、心跳循环靠它判断还要不要继续。以前用 `job?.isActive`：scope 是
     * Main.immediate 这类立即派发的调度器时，launch 里的代码会在 `job =` 赋值之前就跑起来，
     * 那一刻 job 还是 null，心跳循环一进来就判定「不活跃」直接退出，之后永远不发 ping。
     */
    @Volatile private var running = false

    fun start() {
        running = true
        job = scope.launch {
            heartbeat.start(now())
            // 加入方先出声：出示的是"用口令算出来的证明"，不是口令本身（见 [HandshakeAuth]）。
            // 房主验完才回 hello_ack（棋种、规则参数、谁先手都在 ack 里）。
            // 反过来（房主先发、把口令或证明送过去）是不行的：等 readyDeferred 的房主并不知道
            // 连上来的是哪台设备，谁先连上就把东西给谁，第三台设备原样回传一遍就能顶替对手。
            if (role == OnlineRole.GUEST) {
                val nonce = guestNonce ?: error("加入方没有生成 nonce")
                transport.send(
                    NetCodec.encode(NetCodec.hello(nonce, HandshakeAuth.guestProof(token, nonce), gameKind)),
                )
            }
            launch { heartbeatLoop() }
            readLoop()
        }
    }

    private suspend fun readLoop() {
        transport.incoming.collect { line ->
            if (!isActive()) return@collect
            heartbeat.onMessageReceived(now())
            val env = NetCodec.decode(line)
            if (env == null) return@collect // 坏格式的单条消息直接忽略，不因为一条噪声断掉整局
            handle(env)
        }
    }

    private fun isActive(): Boolean = running && !closed

    /**
     * 等握手结束：成功返回 true；超时、口令不对、对方断开都返回 false（此时会话已关闭）。
     *
     * 连接编排层（[OnlineController]）必须等到这里返回 true 才把会话交给 UI——
     * 加入方的先后手、规则参数都在房主的 hello_ack 里，握手前读 [resolvedHostFirst] 拿到的只是占位值，
     * UI 据此分配棋子颜色就会两边对不上。
     */
    suspend fun awaitHandshake(timeoutMs: Long): Boolean {
        val settled = withTimeoutOrNull(timeoutMs) {
            state.first { it !is OnlineConnState.Handshaking }
        }
        if (settled is OnlineConnState.Playing) return true
        if (settled == null) failAndClose("握手超时：连上了对方，但对方没有回应")
        return false
    }

    /**
     * 握手完成前允许处理的消息：握手本身、断线补发的对齐、心跳、以及断开前给对方的一句理由。
     *
     * [NetMsgType.RESUME] 也在里面，是因为房主的 [`Playing`][OnlineConnState.Playing] 要等 hello_ack
     * 真正发出去之后才置位，加入方收到 ack 立刻回的那条 resume 可能赶在置位之前到达；
     * 全新对局 moveLog 是空的，这时补发等于空操作，放过去没有副作用。
     */
    private val allowedBeforePlaying = setOf(
        NetMsgType.HELLO,
        NetMsgType.HELLO_ACK,
        NetMsgType.RESUME,
        NetMsgType.PING,
        NetMsgType.PONG,
        NetMsgType.ERROR,
    )

    private suspend fun handle(env: NetEnvelope) {
        val playing = _state.value is OnlineConnState.Playing
        if (!playing && env.type !in allowedBeforePlaying) {
            // 握手没走完就来着法 / 悔棋 / 认输 / 求和：要么对面是顶替上来的第三台设备，要么协议版本不对。
            // 不能只是"忽略"——handleMove 会把 seq 记进 moveLog，之后对方的正常着法全部被当成跳号，
            // 两边棋盘静默分叉，谁也不会报错。直接断开，让问题当场暴露。
            failAndClose("对方在握手完成前就发来了对局消息，已断开")
            return
        }
        if (playing && (env.type == NetMsgType.HELLO || env.type == NetMsgType.HELLO_ACK)) {
            // 握手只做一次。重复/迟到的握手包放着不管会重跑 onHandshakeDone（重复发 resume），
            // 更糟的是房主侧口令已被消费，再来一条 hello_ack 会把正在下的棋当成校验失败断掉。
            return
        }
        when (env.type) {
            NetMsgType.HELLO -> handleHello(env)
            NetMsgType.HELLO_ACK -> handleHelloAck(env)
            NetMsgType.RESUME -> handleResume(env)
            NetMsgType.MOVE -> handleMove(env)
            NetMsgType.UNDO_REQ -> _events.emit(OnlineGameEvent.UndoRequested)
            NetMsgType.UNDO_ACK -> _events.emit(OnlineGameEvent.UndoAnswered(env.ok == true))
            NetMsgType.RESIGN -> _events.emit(OnlineGameEvent.Resigned)
            NetMsgType.DRAW_REQ -> _events.emit(OnlineGameEvent.DrawRequested)
            NetMsgType.DRAW_ACK -> _events.emit(OnlineGameEvent.DrawAnswered(env.ok == true))
            NetMsgType.PING -> transport.send(NetCodec.encode(NetCodec.simple(NetMsgType.PONG)))
            NetMsgType.PONG -> Unit // 心跳判活只看 heartbeat.onMessageReceived，这里不用额外处理
            NetMsgType.ERROR -> failAndClose("对方断开：" + (env.reason ?: "协议错误"))
            else -> Unit // 未识别的类型：留给未来扩展，忽略而不是断开
        }
    }

    /**
     * 房主侧验证明 + 验棋种，然后才把对局参数交出去。
     *
     * 顺序是"先证明、后记账"：证明不对的请求一律不消费口令（[OneTimeToken.consume]），
     * 否则随便谁连上来乱发一包就能把这张码烧掉，真对手反而进不来——那是个比顶替更容易的拒绝服务。
     */
    private suspend fun handleHello(env: NetEnvelope) {
        if (role != OnlineRole.HOST) return
        val sentNonce = env.nonce
        if (sentNonce == null) {
            rejectHandshake("口令不对", "口令校验失败，可能是别的设备误连")
            return
        }
        // 证明对不上就是"不知道口令"：既不回任何对局参数，也不消费口令
        // （否则随便谁乱发一包就能把这张码烧掉，比顶替还省事）。
        if (!HandshakeAuth.sameProof(HandshakeAuth.guestProof(token, sentNonce), env.proof)) {
            rejectHandshake("口令不对", "口令校验失败，可能是别的设备误连")
            return
        }
        if (env.game != gameKind.wireId) {
            rejectHandshake("棋种不一致", "对方棋种不一致")
            return
        }
        if (hostToken?.consume() != true) {
            rejectHandshake("这张二维码已经被用过了", "这张二维码已经被别的设备用来连过了")
            return
        }
        // 验过才把对局参数交出去，同时回一段绑定双方 nonce 的证明：加入方据此确认对面真是房主，
        // 而不是一台照着广播的 UUID 伪造出来的外设。这之前房主一个字节都没发过。
        val hostNonce = HandshakeAuth.newNonce()
        transport.send(
            NetCodec.encode(
                NetCodec.helloAck(
                    ok = true,
                    nonce = hostNonce,
                    proof = HandshakeAuth.hostProof(token, sentNonce, hostNonce),
                    rule = resolvedRuleParam,
                    hostFirst = resolvedHostFirst,
                ),
            ),
        )
        onHandshakeDone()
    }

    /** 拒绝握手：回一句不带任何秘密的理由（失败包只有 ok / reason），然后断开。 */
    private suspend fun rejectHandshake(reason: String, display: String) {
        runCatching { transport.send(NetCodec.encode(NetCodec.helloAck(false, reason = reason))) }
        failAndClose(display)
    }

    private suspend fun handleHelloAck(env: NetEnvelope) {
        if (role != OnlineRole.GUEST) return
        if (env.ok != true) {
            failAndClose(env.reason ?: "对方拒绝了连接")
            return
        }
        // 先验房主的证明再开棋盘：证明对不上说明对面不知道口令，大概率是冒充房主的外设。
        // 不验就进 Playing，用户会对着一台根本不存在的对手下完一整盘。
        val mine = guestNonce
        val hostNonce = env.nonce
        if (mine == null || hostNonce == null ||
            !HandshakeAuth.sameProof(HandshakeAuth.hostProof(token, mine, hostNonce), env.proof)
        ) {
            failAndClose("对方没能证明自己是房主，已断开（可能有设备在冒充）")
            return
        }
        // 规则参数和先后手由房主在 ack 里给，握手完成前读 resolved* 拿到的只是占位值。
        resolvedRuleParam = env.rule
        resolvedHostFirst = env.hostFirst ?: true
        onHandshakeDone()
    }

    private suspend fun onHandshakeDone() {
        _state.value = OnlineConnState.Playing
        // 握手一结束双方都报一下"我这边已确认到第几步"，用于断线重连续下——
        // 全新对局时 moveLog 是空的，ackSeq=0，等价于什么都不用补发。
        transport.send(NetCodec.encode(NetCodec.resume(moveLog.count)))
    }

    private suspend fun handleResume(env: NetEnvelope) {
        val ackSeq = env.ackSeq ?: 0
        for ((seq, code) in moveLog.sinceExclusive(ackSeq)) {
            transport.send(NetCodec.encode(NetCodec.move(seq, code)))
        }
    }

    private suspend fun handleMove(env: NetEnvelope) {
        val seq = env.seq
        val code = env.move
        if (seq == null || code == null) return
        if (seq != moveLog.count + 1) {
            // 序号跳了：多半是丢包或者顺序被打乱，要一次 resume 来对齐，而不是硬着头皮往下走
            // ——硬走会让两边棋盘分叉，这是协议要不惜断线也要避免的事。
            transport.send(NetCodec.encode(NetCodec.resume(moveLog.count)))
            return
        }
        moveLog.record(seq, code)
        _events.emit(OnlineGameEvent.RemoteMove(seq, code))
    }

    /** 本地这一步已经在本地棋盘上校验、落子完毕，只需要分配序号发出去。 */
    suspend fun sendLocalMove(code: String) {
        val seq = moveLog.count + 1
        moveLog.record(seq, code)
        transport.send(NetCodec.encode(NetCodec.move(seq, code)))
    }

    suspend fun requestDraw() = transport.send(NetCodec.encode(NetCodec.simple(NetMsgType.DRAW_REQ)))
    suspend fun answerDraw(accept: Boolean) =
        transport.send(NetCodec.encode(NetEnvelope(NetMsgType.DRAW_ACK, ok = accept)))

    suspend fun resign() = transport.send(NetCodec.encode(NetCodec.simple(NetMsgType.RESIGN)))

    /** 收到的着法解码后打给规则引擎不合法：按协议要求，立即断开并把理由亮给用户看。 */
    suspend fun reportIllegalMoveAndClose() {
        runCatching { transport.send(NetCodec.encode(NetCodec.error("收到不合法的着法，已断开"))) }
        failAndClose("对方发来一步不合法的着法，已断开连接")
    }

    private suspend fun heartbeatLoop() {
        while (isActive()) {
            delay(500)
            val t = now()
            if (heartbeat.isTimedOut(t)) {
                failAndClose("对方 15 秒没有响应，可能已断开")
                return
            }
            if (heartbeat.shouldSendPing(t)) {
                heartbeat.onPingSent(t)
                runCatching { transport.send(NetCodec.encode(NetCodec.simple(NetMsgType.PING))) }
            }
        }
    }

    private fun failAndClose(reason: String) {
        if (closed) return
        closed = true
        running = false
        _state.value = OnlineConnState.Disconnected(reason)
        transport.close()
        job?.cancel()
    }

    fun close() = failAndClose("已断开连接")
}
