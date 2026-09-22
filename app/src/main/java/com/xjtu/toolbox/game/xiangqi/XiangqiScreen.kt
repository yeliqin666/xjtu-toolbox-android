package com.xjtu.toolbox.game.xiangqi

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
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
 * 棋子文字用传统写法：红方「帅仕相」，黑方「将士象」，兵 / 卒也分开。
 * 两边写同一套字确实更省事，但下过棋的人一眼就能看出不对劲，这点字符不值得省。
 * 这里直接复用规则引擎 Piece.pieceNameMap 的映射，避免两处各维护一份对照表。
 */
private fun pieceText(piece: Int): String = Piece.getNameByValue(piece).toString()

@Composable
fun XiangqiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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

    fun restart() {
        game.reset()
        selected = null
        resultRecorded = -1
        bump()
    }

    val wide = currentWindowSize() != WindowSize.Compact

    Scaffold(
        topBar = {
            TopAppBar(
                title = "象棋",
                color = MiuixTheme.colorScheme.background,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
            XiangqiOnlineSection(
                modifier = Modifier.fillMaxSize(),
                onExitOnlineMode = { online = false },
            )
            return@Column
        }

        val playing = status is XiangqiStatus.Playing
        val boardBlock: @Composable (Modifier) -> Unit = { modifier ->
            XiangqiBoard(
                game = game,
                version = version,
                selected = selected,
                modifier = modifier,
                onTap = { pos ->
                    if (!playing) return@XiangqiBoard
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
        val versus: @Composable () -> Unit = {
            VersusBar(
                left = Contender("西交", "红方 · 先行", StonePaints.Xjtu),
                right = Contender("上交", "黑方", StonePaints.Sjtu),
                activeLeft = if (playing) snapshot.sideToMove == Side.RED else null,
            )
        }
        val hint: @Composable () -> Unit = {
            when {
                snapshot.inCheck && playing -> GameHint("将军！", color = MiuixTheme.colorScheme.error)
                status is XiangqiStatus.Over -> GameHint(describeResult(status))
                selected != null -> GameHint("点亮点走子，点其它己方棋子换选")
                else -> GameHint("点己方棋子选中，再点落点")
            }
        }
        val actions: @Composable () -> Unit = {
            GameActionRow(
                listOf(
                    GameAction("悔棋", enabled = snapshot.canUndo) {
                        if (game.undo()) {
                            selected = null
                            resultRecorded = -1
                            bump()
                        }
                    },
                    GameAction("提和", enabled = playing) { pendingDraw = true },
                    GameAction("认输", enabled = playing) {
                        game.resign(snapshot.sideToMove)
                        bump()
                    },
                    GameAction("重开", primary = !playing, onClick = ::restart),
                ),
            )
        }

        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    boardBlock(Modifier.fillMaxHeight().widthIn(max = 560.dp))
                }
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                versus()
                hint()
                boardBlock(Modifier.fillMaxWidth())
                Box(Modifier.height(16.dp))
                actions()
                Box(Modifier.height(24.dp))
            }
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
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = {
                            resultDialogOpen = false
                            restart()
                        },
                    ) { Text("再来一局", color = MiuixTheme.colorScheme.onPrimary) }
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
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = {
                            pendingDraw = false
                            game.agreeDraw()
                            bump()
                        },
                    ) { Text("同意", color = MiuixTheme.colorScheme.onPrimary) }
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

/**
 * 木纹棋盘 + 象牙棋子。刚走的那颗子从起点滑到终点；选中的子微微抬起。
 * 西交（红方）的字用西交蓝、上交（黑方）用上交红——颜色代表学校，不随主题走。
 */
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
    val wood = woodColors()
    val hintColor = Color(0xFF2E7D32)
    val lastColor = Color(0xFFFFB300)
    val slide = rememberDropProgress(snapshot.lastMove)
    val lift = animateFloatAsState(if (selected != null) 1f else 0f, tween(160), label = "lift")

    // 9 列 10 行的交叉点棋盘：横向 8 格、纵向 9 格，四周各留半格边距，
    // 于是宽高比 = (8+1) : (9+1)。
    Box(modifier.aspectRatio(9f / 10f).woodBoard(wood)) {
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
            val line = wood.line
            val thin = (cell * 0.035f).coerceIn(1.2f, 2.4f)

            // 外框加粗一道
            drawRect(
                line,
                topLeft = Offset(px(0) - cell * 0.12f, py(0) - cell * 0.12f),
                size = androidx.compose.ui.geometry.Size(px(8) - px(0) + cell * 0.24f, py(9) - py(0) + cell * 0.24f),
                style = Stroke(width = thin * 2f),
            )
            // 横线九条通到底；竖线在楚河汉界处断开，只有最左最右两条贯通——这是标准画法
            for (y in 0..9) {
                drawLine(line, Offset(px(0), py(y)), Offset(px(8), py(y)), strokeWidth = thin)
            }
            for (x in 0..8) {
                if (x == 0 || x == 8) {
                    drawLine(line, Offset(px(x), py(0)), Offset(px(x), py(9)), strokeWidth = thin)
                } else {
                    drawLine(line, Offset(px(x), py(0)), Offset(px(x), py(4)), strokeWidth = thin)
                    drawLine(line, Offset(px(x), py(5)), Offset(px(x), py(9)), strokeWidth = thin)
                }
            }
            // 九宫斜线
            drawLine(line, Offset(px(3), py(0)), Offset(px(5), py(2)), strokeWidth = thin)
            drawLine(line, Offset(px(5), py(0)), Offset(px(3), py(2)), strokeWidth = thin)
            drawLine(line, Offset(px(3), py(7)), Offset(px(5), py(9)), strokeWidth = thin)
            drawLine(line, Offset(px(5), py(7)), Offset(px(3), py(9)), strokeWidth = thin)

            // 炮位、兵位的十字标记
            val marks = listOf(
                1 to 2, 7 to 2, 1 to 7, 7 to 7,
                0 to 3, 2 to 3, 4 to 3, 6 to 3, 8 to 3,
                0 to 6, 2 to 6, 4 to 6, 6 to 6, 8 to 6,
            )
            marks.forEach { (mx, my) -> drawCrossMark(px(mx), py(my), cell, line, thin) }

            // 楚河汉界
            val riverStyle = TextStyle(
                fontSize = (cell * 0.46f).toSp(),
                color = line.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold,
                letterSpacing = (cell * 0.12f).toSp(),
            )
            drawCentered(measurer, "楚河", riverStyle, px(2), (py(4) + py(5)) / 2f)
            drawCentered(measurer, "汉界", riverStyle, px(6), (py(4) + py(5)) / 2f)

            // 上一步：起点一圈虚影，终点在棋子下垫一层暖色
            val lastMove = snapshot.lastMove
            lastMove?.let { m ->
                drawCircle(lastColor.copy(alpha = 0.55f), radius = cell * 0.2f, center = Offset(px(m.from.x), py(m.from.y)), style = Stroke(width = cell * 0.05f))
                drawCircle(lastColor.copy(alpha = 0.45f), radius = cell * 0.5f, center = Offset(px(m.to.x), py(m.to.y)))
            }

            // 棋子
            val r = cell * 0.44f
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
                for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                    val piece = snapshot.pieces[y][x]
                    if (!Piece.isValid(piece)) continue
                    val red = Piece.isRed(piece)
                    val sideColor = if (red) XjtuColor else SjtuColor
                    var center = Offset(px(x), py(y))
                    // 刚走的这颗从起点滑过来
                    if (lastMove != null && lastMove.to.x == x && lastMove.to.y == y && slide.value < 1f) {
                        val from = Offset(px(lastMove.from.x), py(lastMove.from.y))
                        center = from + (center - from) * slide.value.coerceIn(0f, 1.05f)
                    }
                    val isSelected = selected != null && selected.x == x && selected.y == y
                    val isKing = piece == Piece.WSHUAI || piece == Piece.BJIANG
                    val scale = if (isSelected) 1f + 0.08f * lift.value else 1f
                    val paint = if (isKing) (if (red) StonePaints.Xjtu else StonePaints.Sjtu) else StonePaints.Ivory
                    drawStone(center, r, paint, scale = scale, lift = if (isSelected) 1f + 1.6f * lift.value else 1f)
                    val rr = r * scale
                    drawCircle(
                        if (isKing) Color.White.copy(alpha = 0.7f) else sideColor,
                        radius = rr * 0.8f,
                        center = center,
                        style = Stroke(width = rr * 0.06f),
                    )
                    if (isSelected) {
                        drawCircle(hintColor, radius = rr + cell * 0.06f, center = center, style = Stroke(width = cell * 0.06f))
                    }
                    val label = if (isKing) (if (red) "西交" else "上交") else pieceText(piece)
                    drawCentered(
                        measurer,
                        label,
                        TextStyle(
                            fontSize = (cell * (if (isKing) 0.3f else 0.5f) * scale).toSp(),
                            color = if (isKing) Color.White else sideColor,
                            fontWeight = FontWeight.Black,
                        ),
                        center.x,
                        center.y,
                    )
                }
            }

            // 合法落点：空位画绿点，能吃的子外圈画绿环
            targets.forEach { p ->
                val c = Offset(px(p.x), py(p.y))
                if (Piece.isValid(snapshot.pieces[p.y][p.x])) {
                    drawCircle(hintColor, radius = r + cell * 0.04f, center = c, style = Stroke(width = cell * 0.07f))
                } else {
                    drawCircle(hintColor.copy(alpha = 0.75f), radius = cell * 0.13f, center = c)
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

private fun DrawScope.drawCrossMark(cx: Float, cy: Float, cell: Float, color: Color, width: Float) {
    val gap = cell * 0.1f
    val len = cell * 0.18f
    listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
        // 贴边的位置只画朝里的半边
        drawLine(color, Offset(cx + sx * gap, cy + sy * gap), Offset(cx + sx * (gap + len), cy + sy * gap), strokeWidth = width)
        drawLine(color, Offset(cx + sx * gap, cy + sy * gap), Offset(cx + sx * gap, cy + sy * (gap + len)), strokeWidth = width)
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

    // 离开联机（退出、返回、切模式）时把连接关掉，否则 socket / 蓝牙会一直占着直到心跳超时
    DisposableEffect(Unit) { onDispose { session?.close() } }

    val activeSession = session
    if (activeSession == null) {
        OnlineLobbyContent(
            sessionScope = scope,
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
            modifier = modifier,
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
    val playing = status is XiangqiStatus.Playing && disconnectedReason == null
    val meRed = mySide == Side.RED

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VersusBar(
            left = Contender("西交", if (meRed) "我 · 红方" else "对手 · 红方", StonePaints.Xjtu),
            right = Contender("上交", if (meRed) "对手 · 黑方" else "我 · 黑方", StonePaints.Sjtu),
            activeLeft = if (playing) game.sideToMove == Side.RED else null,
            modifier = Modifier.padding(top = 4.dp),
        )
        val statusText = when {
            disconnectedReason != null -> disconnectedReason
            status is XiangqiStatus.Over -> describeResult(status)
            game.sideToMove == mySide -> "轮到你走子"
            else -> "等待对方走子…"
        }
        GameHint(statusText, color = if (disconnectedReason != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                    game.agreeDraw()
                    version += 1
                    recordIfFinished(game.status())
                })
            }
        }
        XiangqiBoard(
            game = game,
            version = version,
            selected = selected,
            modifier = Modifier.fillMaxWidth(),
            onTap = { pos ->
                if (!playing || game.sideToMove != mySide) return@XiangqiBoard
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
        Box(Modifier.height(16.dp))
        if (playing) {
            GameActionRow(
                listOf(
                    GameAction("求和") { scope.launch { activeSession.requestDraw() } },
                    GameAction("认输") {
                        scope.launch { activeSession.resign() }
                        game.resign(mySide)
                        version += 1
                        recordIfFinished(game.status())
                    },
                ),
            )
        } else {
            GameActionRow(listOf(GameAction("退出联机", primary = true, onClick = onExitOnlineMode)))
        }
        Box(Modifier.height(24.dp))
    }
}
