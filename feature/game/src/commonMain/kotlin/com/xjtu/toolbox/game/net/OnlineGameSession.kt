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
    private val hostToken = if (role == OnlineRole.HOST) OneTimeToken(token) else null

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
            // 加入方先发 hello：口令由**扫过二维码的那一端**出示，房主验完才回 hello_ack
            // （棋种、规则参数、谁先手都在 ack 里）。
            // 反过来（房主先发 hello 把口令送过去）是不行的：等 readyDeferred 的房主并不知道
            // 连上来的是哪台设备，谁先连上就把口令给谁，第三台设备拿到口令原样回传一遍就能顶替对手。
            if (role == OnlineRole.GUEST) {
                transport.send(NetCodec.encode(NetCodec.hello(token, gameKind)))
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

    private suspend fun handle(env: NetEnvelope) {
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
     * 房主侧验口令：加入方出示的 [NetEnvelope.token] 必须与二维码里那串一致，
     * 且一局只用一次（[OneTimeToken]）——之后再有别的设备拿同一份二维码来连，一律拒绝。
     */
    private suspend fun handleHello(env: NetEnvelope) {
        if (role != OnlineRole.HOST) return
        // 先验口令、再看棋种：口令是"你是不是扫过这张码"的唯一凭据，棋种只是参数兼容性检查。
        val tokenOk = hostToken?.tryConsume(env.token) == true
        val gameOk = env.game == gameKind.wireId
        if (!tokenOk || !gameOk) {
            transport.send(NetCodec.encode(NetCodec.helloAck(false, reason = if (!tokenOk) "口令不对" else "棋种不一致")))
            failAndClose(if (!tokenOk) "口令校验失败，可能是别的设备误连" else "对方棋种不一致")
            return
        }
        // 校验通过才把对局参数交出去；这之前房主没发过任何消息，口令也就不会被"顺手抄走"。
        transport.send(
            NetCodec.encode(NetCodec.helloAck(true, rule = resolvedRuleParam, hostFirst = resolvedHostFirst)),
        )
        onHandshakeDone()
    }

    private suspend fun handleHelloAck(env: NetEnvelope) {
        if (role != OnlineRole.GUEST) return
        if (env.ok != true) {
            failAndClose(env.reason ?: "对方拒绝了连接")
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
