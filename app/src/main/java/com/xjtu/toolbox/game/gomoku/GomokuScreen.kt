package com.xjtu.toolbox.game.gomoku

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xjtu.toolbox.game.ui.Contender
import com.xjtu.toolbox.game.ui.GameAction
import com.xjtu.toolbox.game.ui.GameActionRow
import com.xjtu.toolbox.game.ui.GameHint
import com.xjtu.toolbox.game.ui.SjtuColor
import com.xjtu.toolbox.game.ui.StonePaints
import com.xjtu.toolbox.game.ui.VersusBar
import com.xjtu.toolbox.game.ui.XjtuColor
import com.xjtu.toolbox.game.ui.drawStone
import com.xjtu.toolbox.game.ui.rememberDropProgress
import com.xjtu.toolbox.game.ui.woodBoard
import com.xjtu.toolbox.game.ui.woodColors
import kotlin.math.abs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameResult
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.game.net.GameKind
import com.xjtu.toolbox.game.net.OnlineConnState
import com.xjtu.toolbox.game.net.OnlineGameEvent
import com.xjtu.toolbox.game.net.OnlineGameSession
import com.xjtu.toolbox.game.net.OnlineLobbyContent
import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.isWideLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 五子棋：西交 vs 上交。棋盘、棋子、对阵栏的画法和围棋/象棋共用 `game/ui/BoardGameKit.kt`。
 */
private enum class GomokuMode { AI, LOCAL, ONLINE }

private data class GomokuUiState(
    val board: GomokuBoard = GomokuBoard(),
    val toMove: Int = GOMOKU_XJTU,
    val outcome: GomokuOutcome = GomokuOutcome.ONGOING,
    val thinking: Boolean = false,
)

@Composable
fun GomokuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember { GomokuAi() }

    var mode by remember { mutableStateOf(GomokuMode.AI) }
    var difficulty by remember { mutableStateOf(GomokuDifficulty.HARD) }
    // 玩家默认执西交，可以切换执子方——切换只影响谁先手对应哪一方，不影响西交先行的规则。
    var playerIsXjtu by remember { mutableStateOf(true) }

    var ui by remember { mutableStateOf(GomokuUiState()) }
    var taunt by remember { mutableStateOf<String?>(null) }

    val difficultyKey = if (mode == GomokuMode.LOCAL) "local" else when (difficulty) {
        GomokuDifficulty.EASY -> "easy"
        GomokuDifficulty.HARD -> "hard"
        GomokuDifficulty.HELL -> "hell"
    }

    fun resetGame() {
        ui = GomokuUiState()
        taunt = null
    }

    // 模式/难度/执子方一变，旧局面就没意义了，直接重开。
    LaunchedEffect(mode, difficulty, playerIsXjtu) { resetGame() }

    fun recordIfFinished(outcome: GomokuOutcome) {
        if (mode != GomokuMode.AI) {
            if (outcome == GomokuOutcome.ONGOING) return
            // 同屏双人没有「AI 对手」，胜负记在 local 档，谁赢都算一场，不区分西交上交。
            if (outcome != GomokuOutcome.DRAW) {
                GameStore.recordResult(context, GameIds.GOMOKU, "local", GameResult.WIN)
            }
            return
        }
        val aiPlayer = if (playerIsXjtu) GOMOKU_SJTU else GOMOKU_XJTU
        val result = when {
            outcome == GomokuOutcome.DRAW -> GameResult.DRAW
            (outcome == GomokuOutcome.XJTU_WIN && aiPlayer == GOMOKU_XJTU) ||
                (outcome == GomokuOutcome.SJTU_WIN && aiPlayer == GOMOKU_SJTU) -> GameResult.LOSS
            outcome != GomokuOutcome.ONGOING -> GameResult.WIN
            else -> return
        }
        GameStore.recordResult(context, GameIds.GOMOKU, difficultyKey, result)
    }

    fun applyOutcome(outcome: GomokuOutcome) {
        ui = ui.copy(outcome = outcome)
        if (outcome == GomokuOutcome.SJTU_WIN) {
            taunt = GomokuTexts.sjtuWinTaunt(ui.board.moveCount())
        }
        recordIfFinished(outcome)
    }

    fun maybeTriggerAiMove() {
        if (mode != GomokuMode.AI || ui.outcome != GomokuOutcome.ONGOING) return
        val aiPlayer = if (playerIsXjtu) GOMOKU_SJTU else GOMOKU_XJTU
        if (ui.toMove != aiPlayer) return

        ui = ui.copy(thinking = true)
        scope.launch {
            val timeoutMillis = difficulty.timeoutMillis
            val deadline = if (timeoutMillis > 0) System.nanoTime() + timeoutMillis * 1_000_000L else null
            // 给 AI 一份拷贝去搜：它会在棋盘上反复试落/撤回，界面同时在读同一份就会崩
            val searchBoard = ui.board.copy()
            val startCount = searchBoard.moveCount()
            val move = withContext(Dispatchers.Default) {
                ai.findMove(searchBoard, aiPlayer, difficulty, deadline)
            }
            val board = ui.board
            // AI 想的时候用户点了「重开」或切了模式：这一手作废，别下到新棋盘上
            if (board.moveCount() != startCount || !ui.thinking) return@launch
            board.place(move.first, move.second, aiPlayer)
            val outcome = board.outcomeAfter(move.first, move.second, aiPlayer)
            ui = ui.copy(toMove = board.opponentOf(aiPlayer), thinking = false)
            applyOutcome(outcome)
        }
    }

    fun onCellTap(row: Int, col: Int) {
        if (ui.outcome != GomokuOutcome.ONGOING || ui.thinking) return
        if (mode == GomokuMode.AI) {
            val aiPlayer = if (playerIsXjtu) GOMOKU_SJTU else GOMOKU_XJTU
            if (ui.toMove == aiPlayer) return
        }
        val board = ui.board
        if (!board.place(row, col, ui.toMove)) return
        val player = ui.toMove
        val outcome = board.outcomeAfter(row, col, player)
        ui = ui.copy(toMove = board.opponentOf(player))
        applyOutcome(outcome)
        maybeTriggerAiMove()
    }

    fun undo() {
        if (ui.thinking) return
        val board = ui.board
        // 人机模式悔棋要把 AI 那步也一起撤掉，不然等于玩家白送一步给自己。
        val steps = if (mode == GomokuMode.AI && ui.outcome == GomokuOutcome.ONGOING) 2 else 1
        var undone = 0
        repeat(steps) { if (board.undoLast() != null) undone++ }
        if (undone == 0) return
        val nextToMove = if (undone % 2 == 0) ui.toMove else board.opponentOf(ui.toMove)
        ui = ui.copy(toMove = nextToMove, outcome = GomokuOutcome.ONGOING)
        taunt = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "五子棋",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") }
                },
                color = MiuixTheme.colorScheme.background,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppSegmentedTabs(
                tabs = listOf("人机对战", "同屏双人", "联机对战"),
                selectedTabIndex = mode.ordinal,
                onTabSelected = { mode = GomokuMode.entries[it] },
            )
            if (mode == GomokuMode.ONLINE) {
                GomokuOnlineSection(onExitOnlineMode = { mode = GomokuMode.AI })
                return@Column
            }

            val aiIsXjtu = mode == GomokuMode.AI && !playerIsXjtu
            val aiIsSjtu = mode == GomokuMode.AI && playerIsXjtu
            val aiDetail = "AI · ${difficulty.label}"
            val versus: @Composable () -> Unit = {
                VersusBar(
                    left = Contender("西交", if (aiIsXjtu) aiDetail else if (mode == GomokuMode.AI) "你 · 先手" else "先手", StonePaints.Xjtu),
                    right = Contender("上交", if (aiIsSjtu) aiDetail else if (mode == GomokuMode.AI) "你 · 后手" else "后手", StonePaints.Sjtu),
                    activeLeft = if (ui.outcome == GomokuOutcome.ONGOING) ui.toMove == GOMOKU_XJTU else null,
                    thinking = ui.thinking,
                )
            }
            val hint: @Composable () -> Unit = {
                val (text, color) = when (ui.outcome) {
                    GomokuOutcome.XJTU_WIN -> GomokuTexts.XJTU_WIN to XjtuColor
                    GomokuOutcome.SJTU_WIN -> (taunt ?: "上交获胜") to SjtuColor
                    GomokuOutcome.DRAW -> GomokuTexts.DRAW_TEXT to MiuixTheme.colorScheme.onSurfaceVariantSummary
                    GomokuOutcome.ONGOING -> "先连成五子者胜" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
                GameHint(text, color = color)
            }
            val settings: @Composable () -> Unit = {
                if (mode == GomokuMode.AI) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            AppSegmentedTabs(
                                tabs = GomokuDifficulty.entries.map { it.label },
                                selectedTabIndex = GomokuDifficulty.entries.indexOf(difficulty),
                                onTabSelected = { difficulty = GomokuDifficulty.entries[it] },
                                embedded = true,
                            )
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainer)
                                .clickable { playerIsXjtu = !playerIsXjtu }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                if (playerIsXjtu) "我执西交 ⇄" else "我执上交 ⇄",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (playerIsXjtu) XjtuColor else SjtuColor,
                            )
                        }
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                GameActionRow(
                    listOf(
                        GameAction("悔棋", enabled = !ui.thinking && ui.board.moveCount() > 0, onClick = ::undo),
                        GameAction("重开", primary = true, onClick = ::resetGame),
                    ),
                )
            }

            if (isWideLayout()) {
                Row(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        GomokuBoardView(ui, Modifier.fillMaxHeight(), onCellTap = ::onCellTap)
                    }
                    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        settings()
                        versus()
                        hint()
                        actions()
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
                    settings()
                    Spacer(Modifier.height(8.dp))
                    versus()
                    hint()
                    GomokuBoardView(ui, Modifier.fillMaxWidth(), onCellTap = ::onCellTap)
                    Spacer(Modifier.height(16.dp))
                    actions()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 最后一手如果连成了五子，返回那条线的两端；否则 null。 */
private fun winningLine(board: GomokuBoard): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
    val (r0, c0) = board.lastMove() ?: return null
    val p = board.stoneAt(r0, c0)
    if (p == GOMOKU_EMPTY) return null
    for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
        var a = r0 to c0
        while (board.inBounds(a.first - dr, a.second - dc) && board.stoneAt(a.first - dr, a.second - dc) == p) {
            a = (a.first - dr) to (a.second - dc)
        }
        var b = r0 to c0
        while (board.inBounds(b.first + dr, b.second + dc) && board.stoneAt(b.first + dr, b.second + dc) == p) {
            b = (b.first + dr) to (b.second + dc)
        }
        val count = maxOf(abs(b.first - a.first), abs(b.second - a.second)) + 1
        if (count >= 5) return a to b
    }
    return null
}

/**
 * 木纹棋盘 + 西交蓝 / 上交红的立体棋子；最后一手弹一下，连成五子时画一道连线。
 * [GomokuBoard] 是原地修改的，所以把 moveCount 读进绘制，每落一子必重画。
 */
@Composable
private fun GomokuBoardView(ui: GomokuUiState, modifier: Modifier, onCellTap: (Int, Int) -> Unit) {
    val wood = woodColors()
    val n = ui.board.size
    val moves = ui.board.moveCount()
    val last = ui.board.lastMove()
    val drop = rememberDropProgress(moves)
    val line = if (ui.outcome == GomokuOutcome.XJTU_WIN || ui.outcome == GomokuOutcome.SJTU_WIN) winningLine(ui.board) else null
    val lineColor = Color(0xFFFFD54F)
    // 胜负一出，连成五子的那条线从一端画到另一端（在绘制阶段读，不重组）
    val lineGrow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(line) {
        if (line == null) lineGrow.snapTo(0f)
        else lineGrow.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }

    Box(modifier.aspectRatio(1f).woodBoard(wood)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(n, ui.outcome, ui.thinking, moves) {
                    detectTapGestures { offset ->
                        val cell = size.width.toFloat() / n
                        val col = ((offset.x - cell / 2f) / cell + 0.5f).toInt().coerceIn(0, n - 1)
                        val row = ((offset.y - cell / 2f) / cell + 0.5f).toInt().coerceIn(0, n - 1)
                        onCellTap(row, col)
                    }
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val v = moves
            val cell = size.width / n
            val o = cell / 2f
            val end = size.width - o
            val thin = (cell * 0.04f).coerceIn(1f, 2.2f)
            for (i in 0 until n) {
                val p = o + i * cell
                val edge = i == 0 || i == n - 1
                drawLine(wood.line, Offset(p, o), Offset(p, end), strokeWidth = if (edge) thin * 1.8f else thin, cap = StrokeCap.Round)
                drawLine(wood.line, Offset(o, p), Offset(end, p), strokeWidth = if (edge) thin * 1.8f else thin, cap = StrokeCap.Round)
            }
            // 天元和四个星位
            listOf(3 to 3, 3 to 11, 7 to 7, 11 to 3, 11 to 11).forEach { (r, c) ->
                if (r < n && c < n) drawCircle(wood.line, radius = cell * 0.1f, center = Offset(o + c * cell, o + r * cell))
            }

            val radius = cell * 0.45f
            for (r in 0 until n) {
                for (c in 0 until n) {
                    val stone = ui.board.stoneAt(r, c)
                    if (stone == GOMOKU_EMPTY) continue
                    val isLast = last?.first == r && last.second == c
                    drawStone(
                        Offset(o + c * cell, o + r * cell), radius,
                        if (stone == GOMOKU_XJTU) StonePaints.Xjtu else StonePaints.Sjtu,
                        scale = if (isLast) 0.55f + 0.45f * drop.value else 1f,
                    )
                }
            }
            if (last != null && line == null) {
                drawCircle(Color.White.copy(alpha = 0.9f), radius = radius * 0.26f * drop.value, center = Offset(o + last.second * cell, o + last.first * cell))
            }
            if (line != null) {
                val (a, b) = line
                val start = Offset(o + a.second * cell, o + a.first * cell)
                val finish = Offset(o + b.second * cell, o + b.first * cell)
                drawLine(
                    lineColor,
                    start,
                    androidx.compose.ui.geometry.lerp(start, finish, lineGrow.value),
                    strokeWidth = cell * 0.16f,
                    cap = StrokeCap.Round,
                    alpha = 0.9f,
                )
            }
        }
    }
}

/**
 * 联机对战：接入 `game/net` 模块。房主/加入方握手成功之前走 [OnlineLobbyContent]（选创建/
 * 加入房间、二维码/扫码），握手成功之后本地维护一份 [GomokuBoard]，对方的着法用
 * [GomokuOnlineAdapter] 校验后重放到这份棋盘上——这就是"双方各自用同一规则引擎校验每一步"。
 */
@Composable
private fun GomokuOnlineSection(onExitOnlineMode: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { GomokuOnlineAdapter() }

    var session by remember { mutableStateOf<OnlineGameSession?>(null) }
    var myStone by remember { mutableIntStateOf(GOMOKU_XJTU) }
    var board by remember { mutableStateOf(GomokuBoard()) }
    var toMove by remember { mutableIntStateOf(GOMOKU_XJTU) }
    var outcome by remember { mutableStateOf(GomokuOutcome.ONGOING) }
    var disconnectedReason by remember { mutableStateOf<String?>(null) }
    var pendingDrawFromPeer by remember { mutableStateOf(false) }

    fun recordIfFinished(o: GomokuOutcome) {
        if (o == GomokuOutcome.ONGOING) return
        val iWon = (o == GomokuOutcome.XJTU_WIN && myStone == GOMOKU_XJTU) ||
            (o == GomokuOutcome.SJTU_WIN && myStone == GOMOKU_SJTU)
        val result = when {
            o == GomokuOutcome.DRAW -> GameResult.DRAW
            iWon -> GameResult.WIN
            else -> GameResult.LOSS
        }
        GameStore.recordResult(context, GameIds.GOMOKU, "online", result)
    }

    // 离开联机（退出、返回、切模式）时把连接关掉，否则 socket / 蓝牙会一直占着直到心跳超时
    DisposableEffect(Unit) { onDispose { session?.close() } }

    val activeSession = session
    if (activeSession == null) {
        OnlineLobbyContent(
            sessionScope = scope,
            kind = GameKind.GOMOKU,
            ruleParam = null,
            onSessionReady = { s, isHost, hostFirst, _ ->
                // 谁先手由房主决定；「我是先手」当且仅当"我是房主"和"房主先手"一致。
                myStone = if (isHost == hostFirst) GOMOKU_XJTU else GOMOKU_SJTU
                board = GomokuBoard()
                toMove = GOMOKU_XJTU
                outcome = GomokuOutcome.ONGOING
                disconnectedReason = null
                session = s
            },
            onCancel = onExitOnlineMode,
        )
        return
    }

    LaunchedEffect(activeSession) {
        launch {
            activeSession.state.collect { st ->
                if (st is OnlineConnState.Disconnected) disconnectedReason = st.reason
            }
        }
        activeSession.events.collect { ev ->
            when (ev) {
                is OnlineGameEvent.RemoteMove -> {
                    val peerStone = board.opponentOf(myStone)
                    val move = adapter.decodeMove(ev.code)
                    val legal = toMove == peerStone && move != null && adapter.applyIfLegal(board, move, peerStone)
                    if (!legal) {
                        activeSession.reportIllegalMoveAndClose()
                        return@collect
                    }
                    val placed = move as OnlineMove.Place
                    val o = board.outcomeAfter(placed.row, placed.col, peerStone)
                    toMove = board.opponentOf(peerStone)
                    outcome = o
                    recordIfFinished(o)
                }
                OnlineGameEvent.Resigned -> {
                    val o = if (myStone == GOMOKU_XJTU) GomokuOutcome.XJTU_WIN else GomokuOutcome.SJTU_WIN
                    outcome = o
                    recordIfFinished(o)
                }
                OnlineGameEvent.DrawRequested -> pendingDrawFromPeer = true
                is OnlineGameEvent.DrawAnswered -> if (ev.accepted) {
                    outcome = GomokuOutcome.DRAW
                    recordIfFinished(GomokuOutcome.DRAW)
                }
                else -> Unit
            }
        }
    }

    fun onCellTap(row: Int, col: Int) {
        if (outcome != GomokuOutcome.ONGOING || toMove != myStone) return
        val move = OnlineMove.Place(row, col)
        if (!adapter.applyIfLegal(board, move, myStone)) return
        val o = board.outcomeAfter(row, col, myStone)
        toMove = board.opponentOf(myStone)
        outcome = o
        recordIfFinished(o)
        scope.launch { activeSession.sendLocalMove(adapter.encodeMove(move)) }
    }

    val uiState = GomokuUiState(board = board, toMove = toMove, outcome = outcome, thinking = false)
    val finished = outcome != GomokuOutcome.ONGOING || disconnectedReason != null
    val meXjtu = myStone == GOMOKU_XJTU

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VersusBar(
            left = Contender("西交", if (meXjtu) "我 · 先手" else "对手 · 先手", StonePaints.Xjtu),
            right = Contender("上交", if (meXjtu) "对手 · 后手" else "我 · 后手", StonePaints.Sjtu),
            activeLeft = if (finished) null else toMove == GOMOKU_XJTU,
            modifier = Modifier.padding(top = 4.dp),
        )
        val hint = when {
            disconnectedReason != null -> disconnectedReason
            outcome == GomokuOutcome.XJTU_WIN -> GomokuTexts.XJTU_WIN
            outcome == GomokuOutcome.SJTU_WIN -> "上交获胜"
            outcome == GomokuOutcome.DRAW -> "平局"
            toMove == myStone -> "轮到你落子"
            else -> "等待对方落子…"
        }
        GameHint(hint, color = if (disconnectedReason != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (pendingDrawFromPeer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("对方提议和棋", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                TextButton(text = "拒绝", onClick = {
                    pendingDrawFromPeer = false
                    scope.launch { activeSession.answerDraw(false) }
                })
                TextButton(text = "同意", onClick = {
                    pendingDrawFromPeer = false
                    scope.launch { activeSession.answerDraw(true) }
                    outcome = GomokuOutcome.DRAW
                    recordIfFinished(GomokuOutcome.DRAW)
                })
            }
        }
        GomokuBoardView(uiState, Modifier.fillMaxWidth(), onCellTap = ::onCellTap)
        Spacer(Modifier.height(16.dp))
        if (!finished) {
            GameActionRow(
                listOf(
                    GameAction("求和") { scope.launch { activeSession.requestDraw() } },
                    GameAction("认输") {
                        scope.launch { activeSession.resign() }
                        val o = if (myStone == GOMOKU_XJTU) GomokuOutcome.SJTU_WIN else GomokuOutcome.XJTU_WIN
                        outcome = o
                        recordIfFinished(o)
                    },
                ),
            )
        } else {
            GameActionRow(listOf(GameAction("退出联机", primary = true, onClick = onExitOnlineMode)))
        }
        Spacer(Modifier.height(24.dp))
    }
}
