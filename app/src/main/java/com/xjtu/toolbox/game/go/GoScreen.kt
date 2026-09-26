package com.xjtu.toolbox.game.go

import com.xjtu.toolbox.ui.components.BackButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import com.xjtu.toolbox.game.ui.Contender
import com.xjtu.toolbox.game.ui.GameAction
import com.xjtu.toolbox.game.ui.GameActionRow
import com.xjtu.toolbox.game.ui.GameHint
import com.xjtu.toolbox.game.ui.StonePaints
import com.xjtu.toolbox.game.ui.VersusBar
import com.xjtu.toolbox.game.ui.drawStone
import com.xjtu.toolbox.game.ui.rememberDropProgress
import com.xjtu.toolbox.game.ui.woodBoard
import com.xjtu.toolbox.game.ui.woodColors
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameResult
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.game.net.GameKind
import com.xjtu.toolbox.game.net.OnlineConnState
import com.xjtu.toolbox.game.net.OnlineGameEvent
import com.xjtu.toolbox.game.net.OnlineGameSession
import com.xjtu.toolbox.game.net.LobbyChip
import com.xjtu.toolbox.game.net.LobbyOptionRow
import com.xjtu.toolbox.game.net.OnlineLobbyContent
import com.xjtu.toolbox.game.net.OnlineLobbyState
import com.xjtu.toolbox.game.net.rememberOnlineLobbyState
import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.isWideLayout
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 围棋（同屏双人）。
 *
 * 不做 AI：像样的离线围棋 AI 需要神经网络模型，体积和推理开销都不适合塞进这个 App；
 * 弱 AI（比如简单的贪心提子）下出来的棋会很难看，体验还不如干脆不做——这也是为什么
 * [GoGameState] 里完全没有"电脑走一步"的接口，黑白双方从设计上就都是真人，轮流点同一块屏幕。
 */
@Composable
fun GoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state = remember { GoGameState(9) }
    var online by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 联机对局和大厅挂在整页上：切到同屏双人再切回来，连接和棋局都还在。
    val match = remember { GoOnlineMatch() }
    val lobby = rememberOnlineLobbyState(scope)
    DisposableEffect(match) { onDispose { match.session?.close() } }
    // 收对方着法也放在整页：人在别的 tab 时对方落子不能丢（events 没有重放）。
    LaunchedEffect(match.session) { match.session?.let { match.collect(context, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "围棋",
                navigationIcon = {
                    BackButton(onBack)
                },
                color = MiuixTheme.colorScheme.background,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppSegmentedTabs(
                tabs = listOf("同屏双人", "联机对战"),
                selectedTabIndex = if (online) 1 else 0,
                onTabSelected = { online = it == 1 },
            )
            if (online) {
                GoOnlineSection(
                    match = match,
                    lobby = lobby,
                    scope = scope,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (isWideLayout()) {
                // 宽屏（横屏平板/折叠屏展开）棋盘和操作面板并排
                Row(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        GoLocalBoard(state, Modifier.fillMaxHeight().aspectRatio(1f))
                    }
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GoVersus(state)
                        GoHintLine(state)
                        GoActions(state, context)
                        GoSizePicker(state)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GoVersus(state)
                    GoHintLine(state)
                    GoLocalBoard(state, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    GoActions(state, context)
                    Spacer(Modifier.height(12.dp))
                    GoSizePicker(state)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (state.phase == GoPhase.FINISHED) {
            GoResultDialog(state = state, context = context, onDismiss = { state.newGame() })
        }
    }
}

@Composable
private fun GoVersus(state: GoGameState) {
    VersusBar(
        left = Contender("黑 · 西交", "执黑先行", StonePaints.GoBlack, accent = MiuixTheme.colorScheme.onSurface),
        right = Contender("白 · 上交", "贴 3.75 子", StonePaints.GoWhite, accent = MiuixTheme.colorScheme.onSurfaceVariantSummary),
        activeLeft = if (state.phase == GoPhase.PLAYING) state.turn == Stone.BLACK else null,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun GoHintLine(state: GoGameState) {
    val reason = when (state.rejection) {
        GoRejection.OCCUPIED -> "这里已经有子了"
        GoRejection.SUICIDE -> "这手是自杀，禁着"
        GoRejection.KO -> "打劫：不能立即提回，先去别处走一手"
        null -> null
    }
    val text = reason ?: when (state.phase) {
        GoPhase.PLAYING -> "点交叉点落子 · 双方连续虚手进入数子"
        GoPhase.SCORING -> "数子：点棋子标记死子，再点一次取消"
        GoPhase.FINISHED -> "对局结束"
    }
    GameHint(text, color = if (reason != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun GoActions(state: GoGameState, context: android.content.Context) {
    // 读 version：GoBoard 是普通可变对象，canUndo() 的结果要跟着每一手刷新
    @Suppress("UNUSED_VARIABLE") val v = state.version
    when (state.phase) {
        GoPhase.PLAYING -> GameActionRow(
            listOf(
                GameAction("虚手") { state.pass() },
                GameAction("悔棋", enabled = state.canUndo()) { state.undo() },
                GameAction("认输") { state.resign(state.turn) },
            ),
        )
        GoPhase.SCORING -> GameActionRow(
            listOf(GameAction("确认数子", primary = true) { finishScoring(state, context) }),
        )
        GoPhase.FINISHED -> GameActionRow(
            listOf(GameAction("再来一局", primary = true) { state.newGame() }),
        )
    }
}

@Composable
private fun GoSizePicker(state: GoGameState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "棋盘",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(9, 13, 19).forEach { size ->
                val selected = state.boardSize == size
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainer
                        )
                        .clickable { if (!selected) state.newGame(size) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "$size 路",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun finishScoring(state: GoGameState, context: android.content.Context) {
    val result = state.confirmScore()
    recordResult(context, result.winner)
}

// 同屏双人没有"哪一方是本机用户"的概念，两个人共用一台设备轮流下棋。
// 战绩这里选择固定按"黑棋视角"记一胜一负——黑棋赢记 WIN，白棋赢（黑棋输）记 LOSS，
// 没有平局（贴 3.75 子后不会打平）。
private fun recordResult(context: android.content.Context, winner: Stone) {
    val result = if (winner == Stone.BLACK) GameResult.WIN else GameResult.LOSS
    GameStore.recordResult(context, GameIds.GO, "local", result)
}

@Composable
private fun GoResultDialog(state: GoGameState, context: android.content.Context, onDismiss: () -> Unit) {
    val r = state.result
    val winnerName = if (state.winner == Stone.BLACK) "黑棋（西交）" else "白棋（上交）"
    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "$winnerName 获胜",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (r != null) {
                    "黑 ${r.blackArea} 子，贴 3.75 子后 ${"%.2f".format(r.blackFinal)}\n" +
                        "白 ${r.whiteArea} 子\n胜 ${"%.2f".format(r.margin)} 子"
                } else {
                    "对方中途认输"
                },
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = { state.newGame(); onDismiss() },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("再来一局", color = MiuixTheme.colorScheme.onPrimary) }
        }
    }
    // 认输场景没有经过数子阶段（confirmScore 里已经记过一次），没有 ScoreResult 就是这种情况，
    // 用 LaunchedEffect 只在赢家第一次确定时记一次，避免每次重组都重复写 SharedPreferences。
    if (r == null && state.winner != null) {
        LaunchedEffect(state.winner) {
            recordResult(context, state.winner!!)
        }
    }
}

/** 9/13/19 路盘各自的星位坐标（0-indexed）。 */
private fun starPoints(size: Int): List<GoPoint> = when (size) {
    9 -> listOf(2, 4, 6).let { a -> a.flatMap { x -> a.map { y -> GoPoint(x, y) } } }
    13 -> listOf(3, 6, 9).let { a -> a.flatMap { x -> a.map { y -> GoPoint(x, y) } } }
    19 -> listOf(3, 9, 15).let { a -> a.flatMap { x -> a.map { y -> GoPoint(x, y) } } }
    else -> emptyList()
}

@Composable
private fun GoLocalBoard(state: GoGameState, modifier: Modifier) {
    GoBoardView(
        board = state.board,
        version = state.version,
        dead = if (state.phase == GoPhase.SCORING) state.deadStones.toSet() else emptySet(),
        showLastMove = state.phase == GoPhase.PLAYING,
        modifier = modifier,
        onTap = { x, y -> state.tapIntersection(x, y) },
    )
}

/**
 * 木纹棋盘 + 立体黑白子。
 *
 * [version] 必须每一手都变：[GoBoard] 是普通可变对象，绘制 lambda 不读一个会变的值的话，
 * Compose 会复用上一次的 lambda、不重画——以前「落了子却看不到棋子」就是这个原因。
 */
@Composable
private fun GoBoardView(
    board: GoBoard,
    version: Int,
    dead: Set<GoPoint>,
    showLastMove: Boolean,
    modifier: Modifier,
    onTap: (Int, Int) -> Unit,
) {
    val wood = woodColors()
    val n = board.size
    val last = board.lastMove
    val drop = rememberDropProgress(last)
    val markColor = Color(0xFFE53935)

    Box(modifier.aspectRatio(1f).woodBoard(wood)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(n, onTap) {
                    detectTapGestures { offset ->
                        val cell = size.width.toFloat() / n
                        val x = ((offset.x - cell / 2f) / cell).roundToInt().coerceIn(0, n - 1)
                        val y = ((offset.y - cell / 2f) / cell).roundToInt().coerceIn(0, n - 1)
                        onTap(x, y)
                    }
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val v = version
            val cell = size.width / n
            val o = cell / 2f
            val end = size.width - o
            val thin = (cell * 0.035f).coerceIn(1.2f, 2.4f)

            for (i in 0 until n) {
                val p = o + i * cell
                val edge = i == 0 || i == n - 1
                drawLine(wood.line, Offset(p, o), Offset(p, end), strokeWidth = if (edge) thin * 1.8f else thin)
                drawLine(wood.line, Offset(o, p), Offset(end, p), strokeWidth = if (edge) thin * 1.8f else thin)
            }
            for (star in starPoints(n)) {
                drawCircle(wood.line, radius = cell * 0.09f, center = Offset(o + star.x * cell, o + star.y * cell))
            }

            val stoneRadius = cell * 0.47f
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val stone = board.stoneAt(x, y)
                    if (stone == Stone.EMPTY) continue
                    val center = Offset(o + x * cell, o + y * cell)
                    val isDead = GoPoint(x, y) in dead
                    val isLast = last != null && last.x == x && last.y == y
                    drawStone(
                        center, stoneRadius,
                        if (stone == Stone.BLACK) StonePaints.GoBlack else StonePaints.GoWhite,
                        alpha = if (isDead) 0.4f else 1f,
                        scale = if (isLast) 0.6f + 0.4f * drop.value else 1f,
                    )
                    if (isDead) {
                        val d = stoneRadius * 0.5f
                        drawLine(markColor, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeWidth = cell * 0.08f)
                        drawLine(markColor, Offset(center.x - d, center.y + d), Offset(center.x + d, center.y - d), strokeWidth = cell * 0.08f)
                    }
                }
            }

            if (showLastMove && last != null && board.stoneAt(last.x, last.y) != Stone.EMPTY) {
                drawCircle(
                    markColor,
                    radius = stoneRadius * 0.24f * drop.value,
                    center = Offset(o + last.x * cell, o + last.y * cell),
                )
            }
        }
    }
}

/** 联机能选的路数。二维码之后的 hello 里带着它，加入方按房主的来。 */
private val ONLINE_GO_SIZES = listOf(9, 13, 19)

/**
 * 联机对局的全部状态。放在整页（[GoScreen]）里记住而不是联机区块里：
 * 以前切个 tab 联机区块就离开组合，连接被关、棋局清空。
 */
private class GoOnlineMatch {
    val adapter = GoOnlineAdapter()
    /** 房主开房前选的路数；对局中的真实路数看 [board].size。 */
    var hostSize by mutableIntStateOf(9)
    var session by mutableStateOf<OnlineGameSession?>(null)
    var myColor by mutableStateOf(Stone.BLACK)
    var board by mutableStateOf(GoBoard(9))
    var turn by mutableStateOf(Stone.BLACK)
    var consecutivePasses by mutableIntStateOf(0)
    var result by mutableStateOf<ScoreResult?>(null)
    var resignedWinner by mutableStateOf<Stone?>(null)
    var disconnectedReason by mutableStateOf<String?>(null)
    var pendingDrawFromPeer by mutableStateOf(false)
    // 每成功走一手（含虚手）+1，棋盘靠它重画——GoBoard 是原地修改的可变对象
    var moveCount by mutableIntStateOf(0)
    private var recorded = false

    fun begin(s: OnlineGameSession, iAmFirst: Boolean, size: Int) {
        // 围棋固定黑先，谁先手（黑棋）由房主决定，逻辑跟五子棋一致。
        myColor = if (iAmFirst) Stone.BLACK else Stone.WHITE
        board = GoBoard(size)
        turn = Stone.BLACK
        consecutivePasses = 0
        result = null
        resignedWinner = null
        disconnectedReason = null
        pendingDrawFromPeer = false
        moveCount = 0
        recorded = false
        session = s
    }

    fun leave() {
        session?.close()
        session = null
    }

    val peerColor: Stone get() = if (myColor == Stone.BLACK) Stone.WHITE else Stone.BLACK

    fun recordIfFinished(context: android.content.Context) {
        val winner = result?.winner ?: resignedWinner ?: return
        if (recorded) return
        recorded = true
        GameStore.recordResult(context, GameIds.GO, "online", if (winner == myColor) GameResult.WIN else GameResult.LOSS)
    }

    suspend fun collect(context: android.content.Context, s: OnlineGameSession): Unit = kotlinx.coroutines.coroutineScope {
        launch {
            s.state.collect { st ->
                if (st is OnlineConnState.Disconnected) disconnectedReason = st.reason
            }
        }
        s.events.collect { ev ->
            when (ev) {
                is OnlineGameEvent.RemoteMove -> {
                    val move = adapter.decodeMove(ev.code)
                    val legal = turn == peerColor && move != null &&
                        adapter.applyIfLegal(board, move, peerColor.ordinal)
                    if (!legal) {
                        s.reportIllegalMoveAndClose()
                        return@collect
                    }
                    consecutivePasses = if (move is OnlineMove.Pass) consecutivePasses + 1 else 0
                    moveCount++
                    turn = if (turn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                    if (consecutivePasses >= 2) result = GoScoring.score(board, emptySet())
                }
                OnlineGameEvent.Resigned -> resignedWinner = myColor
                OnlineGameEvent.DrawRequested -> pendingDrawFromPeer = true
                is OnlineGameEvent.DrawAnswered -> if (ev.accepted) result = GoScoring.score(board, emptySet())
                else -> Unit
            }
            recordIfFinished(context)
        }
    }
}

/**
 * 联机对战：接入 `game/net` 模块，逻辑结构跟五子棋那份是同一套（见 `GomokuScreen.kt`）。
 *
 * 路数由房主开房前选（9 / 13 / 19），写进 hello 的规则参数，加入方握手后按房主的来。
 *
 * 数子阶段的"点棋子标死活"是同屏双人才有的手动步骤，联机对局不同步这一步——双方棋盘
 * 保证完全一致，连续两次虚手之后直接按"全部按活子处理"跑一次确定性数子
 * （[GoScoring.score] 传空的死子集合），两边算出来的结果必然一样，不需要为了同步"点哪块死"
 * 专门加一种协议消息。这是相对同屏双人体验的一处简化，认输/求和仍然是实时的。
 */
@Composable
private fun GoOnlineSection(
    match: GoOnlineMatch,
    lobby: OnlineLobbyState,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val activeSession = match.session
    if (activeSession == null) {
        OnlineLobbyContent(
            state = lobby,
            kind = GameKind.GO,
            ruleParam = "${match.hostSize}",
            onSessionReady = { s, isHost, hostFirst, rule ->
                // 加入方的 rule 来自房主的 hello；认不出就退回 9 路（两边都会按 hello 里的值建盘）
                val size = rule?.toIntOrNull()?.takeIf { it in ONLINE_GO_SIZES } ?: match.hostSize.takeIf { isHost } ?: 9
                match.begin(s, iAmFirst = isHost == hostFirst, size = size)
            },
            onCancel = {},
            modifier = modifier,
            hostOptions = {
                LobbyOptionRow("棋盘") {
                    ONLINE_GO_SIZES.forEach { size ->
                        LobbyChip("$size 路", match.hostSize == size) { match.hostSize = size }
                    }
                }
            },
        )
        return
    }

    val board = match.board
    val myColor = match.myColor
    val turn = match.turn
    val result = match.result
    val resignedWinner = match.resignedWinner
    val disconnectedReason = match.disconnectedReason

    fun sendMove(move: OnlineMove) {
        scope.launch { activeSession.sendLocalMove(match.adapter.encodeMove(move)) }
    }

    fun onIntersectionTap(x: Int, y: Int) {
        if (match.result != null || match.resignedWinner != null || match.turn != myColor || disconnectedReason != null) return
        val move = OnlineMove.Place(x, y)
        if (!match.adapter.applyIfLegal(board, move, myColor.ordinal)) return
        match.consecutivePasses = 0
        match.moveCount++
        match.turn = match.peerColor
        sendMove(move)
    }

    fun onPassTap() {
        if (match.result != null || match.resignedWinner != null || match.turn != myColor || disconnectedReason != null) return
        match.adapter.applyIfLegal(board, OnlineMove.Pass, myColor.ordinal)
        match.consecutivePasses += 1
        match.moveCount++
        match.turn = match.peerColor
        if (match.consecutivePasses >= 2) match.result = GoScoring.score(board, emptySet())
        match.recordIfFinished(context)
        sendMove(OnlineMove.Pass)
    }

    val statusText = when {
        disconnectedReason != null -> disconnectedReason
        result != null -> "对局结束：${if (result.winner == Stone.BLACK) "黑棋" else "白棋"}胜 ${"%.2f".format(result.margin)} 子"
        resignedWinner != null -> "对局结束：${if (resignedWinner == Stone.BLACK) "黑棋" else "白棋"}胜（对方认输）"
        turn == myColor -> "轮到你落子 · ${board.size} 路"
        else -> "等待对方落子… · ${board.size} 路"
    }
    val finished = result != null || resignedWinner != null || disconnectedReason != null
    val meBlack = myColor == Stone.BLACK

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VersusBar(
            left = Contender(if (meBlack) "我 · 黑" else "我 · 白", "本机", if (meBlack) StonePaints.GoBlack else StonePaints.GoWhite, accent = MiuixTheme.colorScheme.primary),
            right = Contender(if (meBlack) "对手 · 白" else "对手 · 黑", "联机", if (meBlack) StonePaints.GoWhite else StonePaints.GoBlack, accent = MiuixTheme.colorScheme.onSurfaceVariantSummary),
            activeLeft = if (finished) null else turn == myColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        GameHint(
            statusText,
            color = if (disconnectedReason != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (match.pendingDrawFromPeer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("对方提议按现有盘面数子", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                TextButton(text = "拒绝", onClick = {
                    match.pendingDrawFromPeer = false
                    scope.launch { activeSession.answerDraw(false) }
                })
                TextButton(text = "同意", onClick = {
                    match.pendingDrawFromPeer = false
                    scope.launch { activeSession.answerDraw(true) }
                    match.result = GoScoring.score(board, emptySet())
                    match.recordIfFinished(context)
                })
            }
        }
        GoBoardView(
            board = board,
            version = match.moveCount,
            dead = emptySet(),
            showLastMove = !finished,
            modifier = Modifier.fillMaxWidth(),
            onTap = ::onIntersectionTap,
        )
        Spacer(Modifier.height(16.dp))
        if (!finished) {
            GameActionRow(
                listOf(
                    GameAction("虚手", enabled = turn == myColor, onClick = ::onPassTap),
                    GameAction("求和") { scope.launch { activeSession.requestDraw() } },
                    GameAction("认输") {
                        scope.launch { activeSession.resign() }
                        match.resignedWinner = match.peerColor
                        match.recordIfFinished(context)
                    },
                ),
            )
        } else {
            GameActionRow(listOf(GameAction("退出联机", primary = true, onClick = { match.leave(); lobby.reset() })))
        }
        Spacer(Modifier.height(24.dp))
    }
}
