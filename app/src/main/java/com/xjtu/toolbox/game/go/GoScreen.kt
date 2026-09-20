package com.xjtu.toolbox.game.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameResult
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.ui.isWideLayout
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.min
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = "围棋",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                color = MiuixTheme.colorScheme.background,
            )
        },
    ) { padding ->
        // 宽屏（横屏平板/折叠屏展开）时棋盘和操作面板并排放，窄屏就上下堆叠；
        // 沿用仓库统一的宽屏判定，好和课表、设置等其他页面在同一个阈值上切换。
        if (isWideLayout()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MiuixTheme.colorScheme.background)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GoBoardCanvas(
                    state = state,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                )
                GoSidePanel(
                    state = state,
                    context = context,
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MiuixTheme.colorScheme.background)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GoStatusBar(state)
                Spacer(Modifier.height(12.dp))
                GoBoardCanvas(state = state, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                GoControlRow(state, context)
            }
        }

        if (state.phase == GoPhase.FINISHED) {
            GoResultDialog(state = state, context = context, onDismiss = { state.newGame() })
        }
    }
}

@Composable
private fun GoStatusBar(state: GoGameState) {
    // 读 version 是为了让这个 Composable 在每次落子/提子/悔棋之后重组——
    // GoBoard 是普通可变对象，不靠这个计数器 Compose 感知不到盘面变了。
    @Suppress("UNUSED_EXPRESSION") val v = state.version
    val text = when (state.phase) {
        GoPhase.PLAYING -> "轮到${if (state.turn == Stone.BLACK) "黑棋（西交）" else "白棋（上交）"}"
        GoPhase.SCORING -> "数子中：点棋子标记死活，确认后计算胜负"
        GoPhase.FINISHED -> "对局结束"
    }
    Text(text, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
    val reason = when (state.rejection) {
        GoRejection.OCCUPIED -> "这里已经有子了"
        GoRejection.SUICIDE -> "这手是自杀，禁着"
        GoRejection.KO -> "打劫：不能立即走回上一个全局同形的局面"
        null -> null
    }
    if (reason != null) {
        Text(reason, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
    }
}

@Composable
private fun GoControlRow(state: GoGameState, context: android.content.Context) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            GoPhase.PLAYING -> {
                TextButton(text = "虚手", onClick = { state.pass() })
                TextButton(text = "悔棋", onClick = { state.undo() }, enabled = state.canUndo())
                TextButton(text = "认输", onClick = { state.resign(state.turn) })
            }
            GoPhase.SCORING -> {
                Text(
                    "点棋子切换死活",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Button(
                    onClick = { finishScoring(state, context) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("确认数子") }
            }
            GoPhase.FINISHED -> Unit
        }
    }
}

private fun finishScoring(state: GoGameState, context: android.content.Context) {
    val result = state.confirmScore()
    recordResult(context, result.winner)
}

// 同屏双人没有"哪一方是本机用户"的概念，两个人共用一台设备轮流下棋。
// 战绩这里选择固定按"黑棋视角"记一胜一负——黑棋赢记 WIN，白棋赢（黑棋输）记 LOSS，
// 没有平局（贴 3.75 子后不会打平）。这是本任务里规格没写明、需要自己拍板的一处细节。
private fun recordResult(context: android.content.Context, winner: Stone) {
    val result = if (winner == Stone.BLACK) GameResult.WIN else GameResult.LOSS
    GameStore.recordResult(context, GameIds.GO, "local", result)
}

@Composable
private fun GoSidePanel(state: GoGameState, context: android.content.Context, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GoStatusBar(state)
        GoControlRow(state, context)
        Spacer(Modifier.height(8.dp))
        Text("棋盘大小", style = MiuixTheme.textStyles.footnote1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(9, 13, 19).forEach { size ->
                TextButton(
                    text = "$size",
                    onClick = { state.newGame(size) },
                )
            }
        }
    }
}

@Composable
private fun GoResultDialog(state: GoGameState, context: android.content.Context, onDismiss: () -> Unit) {
    val r = state.result
    val winnerName = if (state.winner == Stone.BLACK) "黑棋（西交）" else "白棋（上交）"
    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "对局结束",
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("$winnerName 获胜", style = MiuixTheme.textStyles.title3)
            if (r != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "黑：${r.blackArea} 子（贴 3.75 子后 ${"%.2f".format(r.blackFinal)}）\n" +
                        "白：${r.whiteArea} 子\n胜 ${"%.2f".format(r.margin)} 子",
                    style = MiuixTheme.textStyles.body2,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text("对方中途认输", style = MiuixTheme.textStyles.body2)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { state.newGame(); onDismiss() }, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("再来一局")
                }
            }
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
private fun GoBoardCanvas(state: GoGameState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_EXPRESSION") val v = state.version // 见 GoStatusBar 里的说明
    val boardColor = Color(0xFFE3C08A) // 棋盘用固定的木色，不是主题相关的语义色，暗色模式下也保持木纹感
    val lineColor = Color(0xFF6B4A26)
    val blackStone = MiuixTheme.colorScheme.onBackground // 深色，代表西交
    val whiteStone = MiuixTheme.colorScheme.surface // 浅色，代表上交
    val highlight = MiuixTheme.colorScheme.primary

    Card(
        modifier = modifier.aspectRatio(1f),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(color = boardColor),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(20.dp)
                .pointerInput(state.boardSize, state.phase) {
                    detectTapGestures { offset ->
                        val size = state.boardSize
                        val cell = this.size.width.toFloat() / (size - 1)
                        val x = (offset.x / cell).roundToInt().coerceIn(0, size - 1)
                        val y = (offset.y / cell).roundToInt().coerceIn(0, size - 1)
                        state.tapIntersection(x, y)
                    }
                },
        ) {
            val n = state.boardSize
            val cell = size.width / (n - 1)

            // 网格线
            for (i in 0 until n) {
                val p = i * cell
                drawLine(lineColor, Offset(p, 0f), Offset(p, size.height), strokeWidth = 2f)
                drawLine(lineColor, Offset(0f, p), Offset(size.width, p), strokeWidth = 2f)
            }

            // 星位
            for (star in starPoints(n)) {
                drawCircle(lineColor, radius = 4f, center = Offset(star.x * cell, star.y * cell))
            }

            val stoneRadius = cell * 0.46f
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val stone = state.board.stoneAt(x, y)
                    if (stone == Stone.EMPTY) continue
                    val center = Offset(x * cell, y * cell)
                    val dead = state.phase == GoPhase.SCORING && GoPoint(x, y) in state.deadStones
                    val color = if (stone == Stone.BLACK) blackStone else whiteStone
                    drawCircle(color, radius = stoneRadius, center = center, alpha = if (dead) 0.35f else 1f)
                    drawCircle(lineColor, radius = stoneRadius, center = center, style = Stroke(width = 1.5f))
                    if (dead) {
                        // 死子打一个叉，比单纯变淡更清楚地表明"这块棋已经判定死了"
                        val d = stoneRadius * 0.6f
                        drawLine(highlight, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeWidth = 3f)
                        drawLine(highlight, Offset(center.x - d, center.y + d), Offset(center.x + d, center.y - d), strokeWidth = 3f)
                    }
                }
            }

            // 最后一手标记：数子阶段盘面已经不再变化，标了也没意义，只在对局中画
            val last = state.board.lastMove
            if (state.phase == GoPhase.PLAYING && last != null) {
                drawCircle(
                    highlight,
                    radius = stoneRadius * 0.32f,
                    center = Offset(last.x * cell, last.y * cell),
                )
            }
        }
    }
}
