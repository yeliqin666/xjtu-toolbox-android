package com.xjtu.toolbox.game.gomoku

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
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameResult
import com.xjtu.toolbox.game.GameStore
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
 * 五子棋：西交 vs 上交。
 *
 * 素材 `R.drawable.game_c9_xjtu` / `game_c9_sjtu`（校徽棋子）还没到位，先用纯色圆
 * + 文字占位（西交深蓝「西」、上交红「上」）。等美术把校徽素材放进来，把
 * [StonePiece] 里的 Canvas 换成 Image(painterResource(...)) 就行，别的都不用动。
 */

private val XjtuBlue = Color(0xFF1B3B6F)
private val SjtuRed = Color(0xFFC8161D)

private enum class GomokuMode { AI, LOCAL }

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
            val move = withContext(Dispatchers.Default) {
                ai.findMove(ui.board, aiPlayer, difficulty, deadline)
            }
            val board = ui.board
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
        if (isWideLayout()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                    GomokuBoardView(ui, onCellTap = ::onCellTap)
                }
                Column(Modifier.width(280.dp).fillMaxHeight()) {
                    GomokuSidePanel(
                        mode = mode, onModeChange = { mode = it },
                        difficulty = difficulty, onDifficultyChange = { difficulty = it },
                        playerIsXjtu = playerIsXjtu, onPlayerSideChange = { playerIsXjtu = it },
                        ui = ui, taunt = taunt,
                        onUndo = ::undo, onRestart = ::resetGame,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GomokuSidePanel(
                    mode = mode, onModeChange = { mode = it },
                    difficulty = difficulty, onDifficultyChange = { difficulty = it },
                    playerIsXjtu = playerIsXjtu, onPlayerSideChange = { playerIsXjtu = it },
                    ui = ui, taunt = taunt,
                    onUndo = ::undo, onRestart = ::resetGame,
                )
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    GomokuBoardView(ui, onCellTap = ::onCellTap)
                }
            }
        }
    }
}

@Composable
private fun GomokuSidePanel(
    mode: GomokuMode,
    onModeChange: (GomokuMode) -> Unit,
    difficulty: GomokuDifficulty,
    onDifficultyChange: (GomokuDifficulty) -> Unit,
    playerIsXjtu: Boolean,
    onPlayerSideChange: (Boolean) -> Unit,
    ui: GomokuUiState,
    taunt: String?,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("对局设置", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)

            AppSegmentedTabs(
                tabs = listOf("人机对战", "同屏双人"),
                selectedTabIndex = if (mode == GomokuMode.AI) 0 else 1,
                onTabSelected = { onModeChange(if (it == 0) GomokuMode.AI else GomokuMode.LOCAL) },
                embedded = true,
            )

            if (mode == GomokuMode.AI) {
                Text("上交 AI 难度", style = MiuixTheme.textStyles.body2)
                AppSegmentedTabs(
                    tabs = GomokuDifficulty.entries.map { it.label },
                    selectedTabIndex = GomokuDifficulty.entries.indexOf(difficulty),
                    onTabSelected = { onDifficultyChange(GomokuDifficulty.entries[it]) },
                    embedded = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我执：", style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = if (playerIsXjtu) "西交（先手）" else "上交（后手）",
                        onClick = { onPlayerSideChange(!playerIsXjtu) },
                    )
                }
            }

            GomokuStatusLine(ui = ui, mode = mode)

            taunt?.let {
                Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (ui.outcome == GomokuOutcome.XJTU_WIN) {
                Text(GomokuTexts.XJTU_WIN, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = XjtuBlue)
            }
            if (ui.outcome == GomokuOutcome.DRAW) {
                Text(GomokuTexts.DRAW_TEXT, style = MiuixTheme.textStyles.body2)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onUndo, modifier = Modifier.weight(1f)) { Text("悔棋") }
                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("重开") }
            }
        }
    }
}

@Composable
private fun GomokuStatusLine(ui: GomokuUiState, mode: GomokuMode) {
    val text = when {
        ui.thinking -> "上交正在思考…"
        ui.outcome != GomokuOutcome.ONGOING -> "本局已结束"
        ui.toMove == GOMOKU_XJTU -> "轮到西交落子"
        else -> "轮到上交落子"
    }
    Text(text, style = MiuixTheme.textStyles.body1)
}

@Composable
private fun GomokuBoardView(ui: GomokuUiState, onCellTap: (Int, Int) -> Unit) {
    val size = ui.board.size
    val last = ui.board.lastMove()
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxWidth()
            .padding(4.dp)
            .background(MiuixTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .pointerInput(ui.board, ui.outcome, ui.thinking) {
                    detectBoardTap(size) { row, col -> onCellTap(row, col) }
                },
        ) {
            val step = this.size.width / (size - 1)
            val lineColor = Color.Gray.copy(alpha = 0.6f)
            for (i in 0 until size) {
                drawLine(lineColor, Offset(0f, i * step), Offset(this.size.width, i * step), strokeWidth = 1.5f, cap = StrokeCap.Round)
                drawLine(lineColor, Offset(i * step, 0f), Offset(i * step, this.size.height), strokeWidth = 1.5f, cap = StrokeCap.Round)
            }
            val radius = step * 0.42f
            for (r in 0 until size) {
                for (c in 0 until size) {
                    val stone = ui.board.stoneAt(r, c)
                    if (stone == GOMOKU_EMPTY) continue
                    val center = Offset(c * step, r * step)
                    val color = if (stone == GOMOKU_XJTU) XjtuBlue else SjtuRed
                    drawCircle(color, radius, center)
                    if (last?.first == r && last.second == c) {
                        drawCircle(Color.White, radius * 0.28f, center)
                    }
                }
            }
        }
    }
}

/** 把点击坐标换算成最近的交叉点行列号——棋子下在交叉点上，不是格子里。 */
private suspend fun PointerInputScope.detectBoardTap(
    boardSize: Int,
    onTap: (Int, Int) -> Unit,
) {
    detectTapGestures { offset ->
        val step = size.width / (boardSize - 1)
        if (step <= 0) return@detectTapGestures
        val col = ((offset.x / step) + 0.5f).toInt().coerceIn(0, boardSize - 1)
        val row = ((offset.y / step) + 0.5f).toInt().coerceIn(0, boardSize - 1)
        onTap(row, col)
    }
}
