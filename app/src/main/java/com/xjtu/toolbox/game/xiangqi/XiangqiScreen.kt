package com.xjtu.toolbox.game.xiangqi

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameResult
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.game.net.GameKind
import com.xjtu.toolbox.game.net.OnlineConnState
import com.xjtu.toolbox.game.net.OnlineGameEvent
import com.xjtu.toolbox.game.net.OnlineGameSession
import com.xjtu.toolbox.game.net.OnlineLobbyContent
import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.game.xiangqi.engine.EndReason
import com.xjtu.toolbox.game.xiangqi.engine.Side
import com.xjtu.toolbox.game.xiangqi.engine.XiangqiGame
import com.xjtu.toolbox.game.xiangqi.engine.XiangqiStatus
import com.xjtu.toolbox.game.xiangqi.rules.Board
import com.xjtu.toolbox.game.xiangqi.rules.Piece
import com.xjtu.toolbox.game.xiangqi.rules.Position
import com.xjtu.toolbox.ui.WindowSize
import com.xjtu.toolbox.ui.currentWindowSize
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 校徽色。硬编码是有意的：这两个颜色代表西交 / 上交两所学校，不随深浅色主题走——
 * 跟着主题变的话「红方是西交」这层意思就没了。棋盘、面板这些真正属于界面的颜色仍然走 MiuixTheme。
 */
private val XJTU_BLUE = Color(0xFF1E3A8A)
private val SJTU_RED = Color(0xFFC8102E)

/**
 * 棋子文字用传统写法：红方「帅仕相」，黑方「将士象」，兵 / 卒也分开。
 * 两边写同一套字确实更省事，但下过棋的人一眼就能看出不对劲，这点字符不值得省。
 * 这里直接复用规则引擎 Piece.pieceNameMap 的映射，避免两处各维护一份对照表。
 */
private fun pieceText(piece: Int): String = Piece.getNameByValue(piece).toString()

@Composable
fun XiangqiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val game = remember { XiangqiGame() }

    // 棋盘是可变对象，Compose 看不见它内部的变化。用一个自增的 version 当重组触发器，
    // 每次改动完 +1，再从 game 拉一份快照——比把整局状态做成 State 树简单得多。
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Position?>(null) }
    var pendingDraw by remember { mutableStateOf(false) }
    var resultRecorded by remember { mutableIntStateOf(-1) }
    var resultDialogOpen by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }

    val snapshot = remember(version) { game.snapshot() }
    val status = snapshot.status

    // 终局只记一次战绩：状态是由 version 推出来的，重组会反复走到这儿。
    if (status is XiangqiStatus.Over && resultRecorded != version) {
        resultRecorded = version
        resultDialogOpen = true
        // 战绩从红方（西交）视角记：红胜 WIN，黑胜 LOSS，和棋 DRAW。
        val result = when (status.winner) {
            Side.RED -> GameResult.WIN
            Side.BLACK -> GameResult.LOSS
            null -> GameResult.DRAW
        }
        GameStore.recordResult(context, GameIds.XIANGQI, "local", result)
    }

    fun bump() {
        version += 1
    }

    val wide = currentWindowSize() != WindowSize.Compact

    Scaffold(
        topBar = {
            TopAppBar(
                title = "象棋",
                largeTitle = "象棋",
                color = MiuixTheme.colorScheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
      if (online) {
        XiangqiOnlineSection(
            modifier = Modifier.fillMaxSize().padding(padding),
            onExitOnlineMode = { online = false },
        )
      } else {
        val boardBlock: @Composable (Modifier) -> Unit = { modifier ->
            XiangqiBoard(
                game = game,
                version = version,
                selected = selected,
                modifier = modifier,
                onTap = { pos ->
                    val cur = selected
                    val piece = game.pieceAt(pos.x, pos.y)
                    val own = XiangqiGame.sideOf(piece) == snapshot.sideToMove
                    when {
                        // 点自己的子：换选中目标。即使已经选了别的子也一样，
                        // 不然「点错子之后想改选」要先点一次空白，很别扭。
                        own -> selected = pos
                        cur != null && game.move(cur, pos) -> {
                            selected = null
                            bump()
                        }
                        else -> selected = null
                    }
                },
            )
        }

        val panelBlock: @Composable (Modifier) -> Unit = { modifier ->
            ControlPanel(
                modifier = modifier,
                snapshot = snapshot,
                onUndo = {
                    if (game.undo()) {
                        selected = null
                        resultRecorded = -1
                        bump()
                    }
                },
                onResign = {
                    game.resign(snapshot.sideToMove)
                    bump()
                },
                onOfferDraw = { pendingDraw = true },
                onRestart = {
                    game.reset()
                    selected = null
                    resultRecorded = -1
                    bump()
                },
                onGoOnline = { online = true },
            )
        }

        if (wide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    boardBlock(Modifier.widthIn(max = 520.dp))
                }
                panelBlock(Modifier.width(240.dp))
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                boardBlock(Modifier.fillMaxWidth())
                panelBlock(Modifier.fillMaxWidth())
            }
        }

        // 终局提示。按 MIUIX-reference 的 Overlay Rules，必须留在 Scaffold 内容作用域里。
        OverlayDialog(
            show = resultDialogOpen && status is XiangqiStatus.Over,
            title = "对局结束",
            onDismissRequest = { resultDialogOpen = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    (status as? XiangqiStatus.Over)?.let { describeResult(it) }.orEmpty(),
                    style = MiuixTheme.textStyles.body1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        // 关掉弹窗不等于重开：多数人想先回头看一眼最后那步棋是怎么被将死的
                        text = "看看棋盘",
                        modifier = Modifier.weight(1f),
                        onClick = { resultDialogOpen = false },
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            resultDialogOpen = false
                            game.reset()
                            selected = null
                            resultRecorded = -1
                            bump()
                        },
                    ) { Text("再来一局") }
                }
            }
        }

        OverlayDialog(
            show = pendingDraw,
            title = "提和",
            onDismissRequest = { pendingDraw = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${sideName(snapshot.sideToMove)}提出和棋，对方同意吗？",
                    style = MiuixTheme.textStyles.body1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "不同意",
                        modifier = Modifier.weight(1f),
                        onClick = { pendingDraw = false },
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            pendingDraw = false
                            game.agreeDraw()
                            bump()
                        },
                    ) { Text("同意") }
                }
            }
        }
      }
    }
}

private fun sideName(side: Side): String = if (side == Side.RED) "西交（红方）" else "上交（黑方）"

private fun describeResult(over: XiangqiStatus.Over): String {
    val who = over.winner?.let { sideName(it) }
    return when (over.reason) {
        EndReason.CHECKMATE -> "$who 将死对方，获胜。"
        EndReason.STALEMATE -> "对方被困毙（无子可动），$who 获胜。"
        EndReason.RESIGN -> "对方认输，$who 获胜。"
        EndReason.AGREED_DRAW -> "双方同意和棋。"
        // 简化棋例：完整规则要判长将一方负，这里只按三次重复局面判和
        EndReason.REPETITION -> "同一局面出现三次，判和（简化棋例，不判长将负）。"
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    snapshot: com.xjtu.toolbox.game.xiangqi.engine.XiangqiSnapshot,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onOfferDraw: () -> Unit,
    onRestart: () -> Unit,
    onGoOnline: () -> Unit,
) {
    Card(modifier = modifier, cornerRadius = 16.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val playing = snapshot.status is XiangqiStatus.Playing
            Text(
                if (playing) "轮到 ${sideName(snapshot.sideToMove)}" else "对局已结束",
                style = MiuixTheme.textStyles.title3,
                color = if (snapshot.sideToMove == Side.RED) XJTU_BLUE else SJTU_RED,
            )
            if (snapshot.inCheck) {
                Text("将军！", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = snapshot.canUndo, onClick = onUndo) { Text("悔棋") }
                Button(modifier = Modifier.weight(1f), enabled = playing, onClick = onResign) { Text("认输") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = playing, onClick = onOfferDraw) { Text("提和") }
                Button(modifier = Modifier.weight(1f), onClick = onRestart) { Text("重开") }
            }
            TextButton(text = "联机对战", modifier = Modifier.fillMaxWidth(), onClick = onGoOnline)
            Text(
                "同屏双人：两人轮流点屏幕。悔棋一次退一步，谁走错谁点。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun XiangqiBoard(
    game: XiangqiGame,
    version: Int,
    selected: Position?,
    modifier: Modifier,
    onTap: (Position) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val snapshot = remember(version) { game.snapshot() }
    val targets = remember(version, selected) {
        selected?.let { game.legalTargetsAt(it.x, it.y) } ?: emptyList()
    }
    val boardColor = MiuixTheme.colorScheme.surface
    val lineColor = MiuixTheme.colorScheme.onSurface
    val hintColor = MiuixTheme.colorScheme.primary

    // 9 列 10 行的交叉点棋盘：横向 8 格、纵向 9 格，四周各留半格边距，
    // 于是宽高比 = (8+1) : (9+1)。
    Box(
        modifier
            .aspectRatio(9f / 10f)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(version, selected) {
                    detectTapGestures { offset ->
                        val cell = minOf(size.width / 9f, size.height / 10f)
                        val originX = (size.width - cell * 8f) / 2f
                        val originY = (size.height - cell * 9f) / 2f
                        val x = Math.round((offset.x - originX) / cell)
                        val y = Math.round((offset.y - originY) / cell)
                        if (x in 0..8 && y in 0..9) onTap(Position(x, y))
                    }
                }
        ) {
            val cell = minOf(size.width / 9f, size.height / 10f)
            val originX = (size.width - cell * 8f) / 2f
            val originY = (size.height - cell * 9f) / 2f
            fun px(x: Int) = originX + x * cell
            fun py(y: Int) = originY + y * cell

            drawRect(boardColor)

            // 横线九条通到底；竖线在楚河汉界处断开，只有最左最右两条贯通——这是标准画法
            for (y in 0..9) {
                drawLine(lineColor, Offset(px(0), py(y)), Offset(px(8), py(y)), strokeWidth = 1.5f)
            }
            for (x in 0..8) {
                if (x == 0 || x == 8) {
                    drawLine(lineColor, Offset(px(x), py(0)), Offset(px(x), py(9)), strokeWidth = 1.5f)
                } else {
                    drawLine(lineColor, Offset(px(x), py(0)), Offset(px(x), py(4)), strokeWidth = 1.5f)
                    drawLine(lineColor, Offset(px(x), py(5)), Offset(px(x), py(9)), strokeWidth = 1.5f)
                }
            }
            // 九宫斜线
            drawLine(lineColor, Offset(px(3), py(0)), Offset(px(5), py(2)), strokeWidth = 1.5f)
            drawLine(lineColor, Offset(px(5), py(0)), Offset(px(3), py(2)), strokeWidth = 1.5f)
            drawLine(lineColor, Offset(px(3), py(7)), Offset(px(5), py(9)), strokeWidth = 1.5f)
            drawLine(lineColor, Offset(px(5), py(7)), Offset(px(3), py(9)), strokeWidth = 1.5f)

            // 炮位、兵位的十字标记
            val marks = listOf(
                1 to 2, 7 to 2, 1 to 7, 7 to 7,
                0 to 3, 2 to 3, 4 to 3, 6 to 3, 8 to 3,
                0 to 6, 2 to 6, 4 to 6, 6 to 6, 8 to 6,
            )
            marks.forEach { (mx, my) -> drawCrossMark(px(mx), py(my), cell, lineColor) }

            // 楚河汉界
            val riverStyle = TextStyle(
                fontSize = (cell * 0.42f).toSp(),
                color = lineColor.copy(alpha = 0.55f),
                fontWeight = FontWeight.Medium,
            )
            drawCentered(measurer, "楚 河", riverStyle, px(2), (py(4) + py(5)) / 2f)
            drawCentered(measurer, "汉 界", riverStyle, px(6), (py(4) + py(5)) / 2f)

            // 上一步高亮：起点画空心框，终点画实心框，两头都标才看得出「从哪走到哪」
            snapshot.lastMove?.let { m ->
                drawHighlight(px(m.from.x), py(m.from.y), cell, hintColor, filled = false)
                drawHighlight(px(m.to.x), py(m.to.y), cell, hintColor, filled = true)
            }

            // 合法落点提示
            targets.forEach { p ->
                drawCircle(hintColor.copy(alpha = 0.55f), radius = cell * 0.12f, center = Offset(px(p.x), py(p.y)))
            }

            // 棋子
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
                for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                    val piece = snapshot.pieces[y][x]
                    if (!Piece.isValid(piece)) continue
                    val red = Piece.isRed(piece)
                    val side = if (red) XJTU_BLUE else SJTU_RED
                    val center = Offset(px(x), py(y))
                    val r = cell * 0.42f
                    // 将帅位置先用色块占位：素材 game_c9_xjtu / game_c9_sjtu 就绪后换成校徽图片
                    val isKing = piece == Piece.WSHUAI || piece == Piece.BJIANG
                    drawCircle(if (isKing) side else boardColor, radius = r, center = center)
                    drawCircle(side, radius = r, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = cell * 0.06f))
                    if (selected != null && selected.x == x && selected.y == y) {
                        drawCircle(hintColor, radius = r + cell * 0.06f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = cell * 0.08f))
                    }
                    val label = if (isKing) (if (red) "西交" else "上交") else pieceText(piece)
                    drawCentered(
                        measurer,
                        label,
                        TextStyle(
                            fontSize = (cell * (if (isKing) 0.26f else 0.46f)).toSp(),
                            color = if (isKing) Color.White else side,
                            fontWeight = FontWeight.Bold,
                        ),
                        center.x,
                        center.y,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawCentered(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    cx: Float,
    cy: Float,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}

private fun DrawScope.drawCrossMark(cx: Float, cy: Float, cell: Float, color: Color) {
    val gap = cell * 0.1f
    val len = cell * 0.18f
    listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
        drawLine(color, Offset(cx + sx * gap, cy + sy * gap), Offset(cx + sx * (gap + len), cy + sy * gap), strokeWidth = 1.5f)
        drawLine(color, Offset(cx + sx * gap, cy + sy * gap), Offset(cx + sx * gap, cy + sy * (gap + len)), strokeWidth = 1.5f)
    }
}

private fun DrawScope.drawHighlight(cx: Float, cy: Float, cell: Float, color: Color, filled: Boolean) {
    val r = cell * 0.46f
    if (filled) {
        drawCircle(color.copy(alpha = 0.22f), radius = r, center = Offset(cx, cy))
    } else {
        drawCircle(color.copy(alpha = 0.5f), radius = r, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = cell * 0.05f))
    }
}

/**
 * 联机对战：接入 `game/net` 模块，逻辑结构跟五子棋/围棋那两份是同一套。棋盘直接复用
 * [XiangqiBoard]——它本来就只依赖 [XiangqiGame] + 一个 `version` 重组触发器，不关心
 * 对手是 AI、同屏还是网络对面的另一台手机。
 */
@Composable
private fun XiangqiOnlineSection(modifier: Modifier = Modifier, onExitOnlineMode: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { XiangqiOnlineAdapter() }

    var session by remember { mutableStateOf<OnlineGameSession?>(null) }
    var mySide by remember { mutableStateOf(Side.RED) }
    var game by remember { mutableStateOf(XiangqiGame()) }
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Position?>(null) }
    var disconnectedReason by remember { mutableStateOf<String?>(null) }
    var pendingDrawFromPeer by remember { mutableStateOf(false) }

    fun recordIfFinished(status: XiangqiStatus) {
        if (status !is XiangqiStatus.Over) return
        val result = when (status.winner) {
            mySide -> GameResult.WIN
            null -> GameResult.DRAW
            else -> GameResult.LOSS
        }
        GameStore.recordResult(context, GameIds.XIANGQI, "online", result)
    }

    val activeSession = session
    if (activeSession == null) {
        OnlineLobbyContent(
            kind = GameKind.XIANGQI,
            ruleParam = null,
            onSessionReady = { s, isHost, hostFirst, _ ->
                // 象棋固定红先，谁先手（红方）由房主决定，逻辑跟其它两种棋一致。
                mySide = if (isHost == hostFirst) Side.RED else Side.BLACK
                game = XiangqiGame()
                version = 0
                selected = null
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
                    val peerSide = if (mySide == Side.RED) Side.BLACK else Side.RED
                    val move = adapter.decodeMove(ev.code)
                    val legal = move != null && adapter.applyIfLegal(game, move, peerSide.ordinal)
                    if (!legal) {
                        activeSession.reportIllegalMoveAndClose()
                        return@collect
                    }
                    version += 1
                    recordIfFinished(game.status())
                }
                OnlineGameEvent.Resigned -> {
                    game.resign(if (mySide == Side.RED) Side.BLACK else Side.RED)
                    version += 1
                    recordIfFinished(game.status())
                }
                OnlineGameEvent.DrawRequested -> pendingDrawFromPeer = true
                is OnlineGameEvent.DrawAnswered -> if (ev.accepted) {
                    game.agreeDraw()
                    version += 1
                    recordIfFinished(game.status())
                }
                else -> Unit
            }
        }
    }

    val status = game.status()

    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (disconnectedReason != null) {
            Text(disconnectedReason ?: "", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
            Spacer(12.dp)
            TextButton(text = "返回", onClick = onExitOnlineMode)
            return@Column
        }
        Text("我执${sideName(mySide)}", style = MiuixTheme.textStyles.body2)
        val statusText = when {
            status is XiangqiStatus.Over -> describeResult(status)
            game.sideToMove == mySide -> "轮到你走子"
            else -> "等待对方走子"
        }
        Text(statusText, style = MiuixTheme.textStyles.body1)
        if (pendingDrawFromPeer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("对方提议和棋。", style = MiuixTheme.textStyles.body2)
                TextButton(text = "同意", onClick = {
                    pendingDrawFromPeer = false
                    scope.launch { activeSession.answerDraw(true) }
                    game.agreeDraw()
                    version += 1
                    recordIfFinished(game.status())
                })
                TextButton(text = "拒绝", onClick = {
                    pendingDrawFromPeer = false
                    scope.launch { activeSession.answerDraw(false) }
                })
            }
        }
        Spacer(8.dp)
        XiangqiBoard(
            game = game,
            version = version,
            selected = selected,
            modifier = Modifier.fillMaxWidth(),
            onTap = { pos ->
                if (status !is XiangqiStatus.Playing || game.sideToMove != mySide) return@XiangqiBoard
                val cur = selected
                val piece = game.pieceAt(pos.x, pos.y)
                val own = XiangqiGame.sideOf(piece) == mySide
                when {
                    own -> selected = pos
                    cur != null -> {
                        val move = OnlineMove.Step(cur.x, cur.y, pos.x, pos.y)
                        if (adapter.applyIfLegal(game, move, mySide.ordinal)) {
                            selected = null
                            version += 1
                            recordIfFinished(game.status())
                            scope.launch { activeSession.sendLocalMove(adapter.encodeMove(move)) }
                        } else {
                            selected = null
                        }
                    }
                    else -> selected = null
                }
            },
        )
        Spacer(8.dp)
        if (status is XiangqiStatus.Playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "求和", onClick = { scope.launch { activeSession.requestDraw() } })
                TextButton(text = "认输", onClick = {
                    scope.launch { activeSession.resign() }
                    game.resign(mySide)
                    version += 1
                    recordIfFinished(game.status())
                })
            }
        } else {
            TextButton(text = "退出联机", onClick = onExitOnlineMode)
        }
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.height(height))
}
