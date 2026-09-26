package com.xjtu.toolbox.game.g2048

import com.xjtu.toolbox.ui.components.BackButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.rememberHaptics
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * GPA 2048：把经典 2048 的数字换成西交大 4.3 制的绩点等级链（F → A+），规则照搬 2048。
 *
 * 画面上的方块和 [GameState] 的棋盘是两层：棋盘是规则真相，方块（[UiTile]）带着稳定 id，
 * 每次滑动按 [traceMove] 算出每块去哪，于是能做滑动、合成弹跳、新块冒出的动画。
 *
 * 玩法加了两样：每局 3 次撤销；「盘面绩点」——场上所有方块绩点的平均，越合越高。
 */

private val DRAG_THRESHOLD_DP = 24.dp
private const val UNDO_LIMIT = 3
private const val SLIDE_MS = 110
private val GAP = 10.dp

private enum class TileKind { STILL, SPAWN, MERGED, GHOST }

@Immutable
private data class UiTile(val id: Long, val level: Int, val index: Int, val kind: TileKind, val stamp: Int)

private data class Snapshot(val state: GameState, val tiles: List<UiTile>)

@Composable
fun Gpa2048Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var nextId by remember { mutableStateOf(0L) }
    var stamp by remember { mutableIntStateOf(0) }

    fun tilesOf(board: Board, kind: TileKind): List<UiTile> =
        board.cells.mapIndexedNotNull { i, v -> v?.let { UiTile(nextId++, it, i, kind, stamp) } }

    var state by remember { mutableStateOf(newGame(Random(System.nanoTime()))) }
    var tiles by remember { mutableStateOf(tilesOf(state.board, TileKind.SPAWN)) }
    var history by remember { mutableStateOf(listOf<Snapshot>()) }
    var undoLeft by remember { mutableIntStateOf(UNDO_LIMIT) }
    var best by remember { mutableIntStateOf(0) }
    var lastGain by remember { mutableIntStateOf(0) }
    var gainStamp by remember { mutableIntStateOf(0) }
    var winDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { best = GameStore.bestScore(context, GameIds.G2048) }
    LaunchedEffect(state.score) {
        GameStore.submitScore(context, GameIds.G2048, state.score)
        best = maxOf(best, state.score)
    }
    // 合成用的「影子」方块滑到位就撤掉
    LaunchedEffect(stamp) {
        delay(SLIDE_MS + 40L)
        if (tiles.any { it.kind == TileKind.GHOST }) tiles = tiles.filter { it.kind != TileKind.GHOST }
    }

    fun restart() {
        stamp++
        state = newGame(Random(System.nanoTime()))
        tiles = tilesOf(state.board, TileKind.SPAWN)
        history = emptyList()
        undoLeft = UNDO_LIMIT
        winDismissed = false
        lastGain = 0
    }

    fun handleMove(direction: Direction) {
        val before = state
        if (before.isOver) return
        val result = move(before.board, direction)
        if (!result.moved) return
        val after = applyMove(before, direction, Random.Default)
        val trace = traceMove(before.board, direction)

        stamp++
        val current = tiles.filter { it.kind != TileKind.GHOST }.associateBy { it.index }
        val next = mutableListOf<UiTile>()
        trace.groupBy { it.to }.forEach { (to, group) ->
            val first = current[group[0].from] ?: return@forEach
            if (group.size == 2) {
                val second = current[group[1].from] ?: return@forEach
                next += second.copy(index = to, kind = TileKind.GHOST, stamp = stamp)
                next += first.copy(index = to, level = first.level + 1, kind = TileKind.MERGED, stamp = stamp)
            } else {
                next += first.copy(index = to, kind = TileKind.STILL, stamp = stamp)
            }
        }
        val spawnIndex = after.board.cells.indices.firstOrNull { result.board.cells[it] == null && after.board.cells[it] != null }
        if (spawnIndex != null) {
            next += UiTile(nextId++, after.board.cells[spawnIndex]!!, spawnIndex, TileKind.SPAWN, stamp)
        }

        history = (history + Snapshot(before, tiles.filter { it.kind != TileKind.GHOST })).takeLast(UNDO_LIMIT)
        state = after
        tiles = next
        if (result.scoreGained > 0) {
            lastGain = result.scoreGained
            gainStamp++
            haptics.tick()
        }
        if (after.isOver) haptics.error()
        if (after.hasWon && !before.hasWon) haptics.success()
    }

    fun undo() {
        val snap = history.lastOrNull() ?: return
        if (undoLeft <= 0) return
        stamp++
        history = history.dropLast(1)
        state = snap.state
        tiles = snap.tiles.map { it.copy(kind = TileKind.STILL, stamp = stamp) }
        undoLeft--
        lastGain = 0
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "GPA 2048",
                navigationIcon = {
                    BackButton(onBack)
                },
                color = MiuixTheme.colorScheme.background,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .readableWidth()
                .onKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionLeft -> Direction.LEFT
                        Key.DirectionRight -> Direction.RIGHT
                        Key.DirectionUp -> Direction.UP
                        Key.DirectionDown -> Direction.DOWN
                        else -> null
                    }
                    if (direction != null) {
                        handleMove(direction); true
                    } else false
                }
                .focusRequester(focusRequester)
                .focusable()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            ScoreRow(
                score = state.score,
                best = best,
                boardGpa = boardGpa(state.board),
                lastGain = lastGain,
                gainStamp = gainStamp,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Gpa2048Texts.HOWTO,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { undo() }, enabled = undoLeft > 0 && history.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销",
                            tint = if (undoLeft > 0 && history.isNotEmpty()) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(" $undoLeft", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                IconButton(onClick = { restart() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新开始", modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(6.dp))

            BoardView(
                tiles = tiles,
                onSwipe = ::handleMove,
                overlay = {
                    when {
                        state.isOver -> ResultOverlay(
                            title = Gpa2048Texts.gameOverTitle(boardGpa(state.board)),
                            subtitle = "本局 ${state.score} 分",
                            primary = Gpa2048Texts.RESTART to { restart() },
                            secondary = if (undoLeft > 0 && history.isNotEmpty()) Gpa2048Texts.undoLeft(undoLeft) to { undo() } else null,
                        )
                        state.hasWon && !winDismissed -> ResultOverlay(
                            title = Gpa2048Texts.WIN_TITLE,
                            subtitle = Gpa2048Texts.WIN_BODY,
                            primary = Gpa2048Texts.WIN_CONTINUE to { winDismissed = true },
                            secondary = "重新开始" to { restart() },
                        )
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            LevelLadder(highest = state.board.highestIndex() ?: 0)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 场上方块绩点的平均值，两位小数；空盘 0。 */
private fun boardGpa(board: Board): String {
    val values = board.cells.filterNotNull().map { GpaScale.LEVELS[it].gpa }
    if (values.isEmpty()) return "0.00"
    return "%.2f".format(values.average())
}

@Composable
private fun ScoreRow(score: Int, best: Int, boardGpa: String, lastGain: Int, gainStamp: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            ScoreCard(Gpa2048Texts.SCORE, score.toString(), highlight = true)
            // 加分飘字：每次合成从分数卡上冒出来再淡掉
            if (lastGain > 0) {
                val anim = remember(gainStamp) { Animatable(0f) }
                LaunchedEffect(gainStamp) { anim.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
                Text(
                    "+$lastGain",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 6.dp)
                        .graphicsLayer {
                            translationY = -18.dp.toPx() * anim.value
                            alpha = 1f - anim.value
                        },
                )
            }
        }
        ScoreCard(Gpa2048Texts.BEST, best.toString(), modifier = Modifier.weight(1f))
        ScoreCard(Gpa2048Texts.BOARD_GPA, boardGpa, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ScoreCard(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val primary = MiuixTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (highlight) Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.78f)))
                else Brush.linearGradient(listOf(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.surfaceContainer))
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            color = if (highlight) Color.White.copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) Color.White else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun BoardView(
    tiles: List<UiTile>,
    onSwipe: (Direction) -> Unit,
    overlay: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { DRAG_THRESHOLD_DP.toPx() }
    val dark = LocalIsDarkTheme.current
    val boardColor = if (dark) Color(0xFF1C2330) else Color(0xFFE6ECF5)
    val emptyColor = if (dark) Color(0xFF263041) else Color(0xFFF4F7FC)

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 520.dp)
        val cell: Dp = (side - GAP * (BOARD_SIZE + 1)) / BOARD_SIZE
        val cellPx = with(density) { cell.roundToPx() }
        val gapPx = with(density) { GAP.roundToPx() }

        Box(
            Modifier
                .size(side)
                .shadow(8.dp, RoundedCornerShape(22.dp), clip = false, ambientColor = Color(0x220B4F9C), spotColor = Color(0x220B4F9C))
                .clip(RoundedCornerShape(22.dp))
                .background(boardColor)
                .pointerInput(Unit) {
                    // 过了阈值就立刻响应，不等手指抬起：连滑的时候跟手很多
                    var dragX = 0f
                    var dragY = 0f
                    var fired = false
                    detectDragGestures(
                        onDragStart = { dragX = 0f; dragY = 0f; fired = false },
                        onDrag = { change, amount ->
                            change.consume()
                            dragX += amount.x
                            dragY += amount.y
                            if (!fired) {
                                resolveSwipeDirection(dragX, dragY, thresholdPx)?.let { fired = true; onSwipe(it) }
                            }
                        },
                    )
                },
        ) {
            for (i in 0 until BOARD_SIZE * BOARD_SIZE) {
                val r = i / BOARD_SIZE
                val c = i % BOARD_SIZE
                Box(
                    Modifier
                        .offset { IntOffset(gapPx + c * (cellPx + gapPx), gapPx + r * (cellPx + gapPx)) }
                        .size(cell)
                        .clip(RoundedCornerShape(14.dp))
                        .background(emptyColor),
                )
            }
            // 影子先画，压在留下来的那块下面
            tiles.sortedBy { if (it.kind == TileKind.GHOST) 0 else 1 }.forEach { tile ->
                androidx.compose.runtime.key(tile.id) {
                    TileView(tile, cell, cellPx, gapPx)
                }
            }
            overlay()
        }
    }
}

@Composable
private fun TileView(tile: UiTile, cell: Dp, cellPx: Int, gapPx: Int) {
    val r = tile.index / BOARD_SIZE
    val c = tile.index % BOARD_SIZE
    val target = IntOffset(gapPx + c * (cellPx + gapPx), gapPx + r * (cellPx + gapPx))
    val pos by animateIntOffsetAsState(target, tween(SLIDE_MS, easing = FastOutSlowInEasing), label = "tile")

    val scale = remember { Animatable(if (tile.kind == TileKind.SPAWN) 0f else 1f) }
    LaunchedEffect(tile.stamp, tile.kind) {
        when (tile.kind) {
            TileKind.SPAWN -> {
                delay(SLIDE_MS.toLong())
                scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
            }
            TileKind.MERGED -> {
                delay(SLIDE_MS - 20L)
                scale.animateTo(1.14f, tween(80))
                scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            }
            // 连滑太快时上一段弹跳/冒出还没播完就被打断，直接归位，别卡在半大不小
            else -> scale.snapTo(1f)
        }
    }

    val style = tileStyle(tile.level, LocalIsDarkTheme.current)
    val level = GpaScale.LEVELS[tile.level]
    Box(
        Modifier
            .offset { pos }
            .size(cell)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .shadow(if (tile.level >= 7) 6.dp else 2.dp, RoundedCornerShape(14.dp), ambientColor = style.glow, spotColor = style.glow)
            .clip(RoundedCornerShape(14.dp))
            .background(style.brush),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                level.letter,
                fontSize = (cell.value * 0.36f).sp,
                fontWeight = FontWeight.Black,
                color = style.fg,
                lineHeight = (cell.value * 0.4f).sp,
            )
            Text(
                if (level.gpa == 0.0) "0" else level.gpa.toString(),
                fontSize = (cell.value * 0.15f).sp,
                fontWeight = FontWeight.SemiBold,
                color = style.fg.copy(alpha = 0.8f),
            )
        }
    }
}

private class TileStyle(val brush: Brush, val fg: Color, val glow: Color)

/** F 灰、D~C 蓝、B 青绿、B+~A 暖色，A+ 紫粉渐变。 */
private fun tileStyle(level: Int, dark: Boolean): TileStyle {
    fun solid(bg: Long, fg: Color) = TileStyle(Brush.linearGradient(listOf(Color(bg), Color(bg))), fg, Color(bg).copy(alpha = 0.5f))
    fun grad(a: Long, b: Long) = TileStyle(Brush.linearGradient(listOf(Color(a), Color(b))), Color.White, Color(b).copy(alpha = 0.6f))
    val lightInk = Color(0xFF1E3A5F)
    return when (level) {
        0 -> if (dark) solid(0xFF3A4252, Color(0xFFB8C0CE)) else solid(0xFFD9DEE6, Color(0xFF6B7280))
        1 -> if (dark) solid(0xFF2C4262, Color(0xFFC9DBF3)) else solid(0xFFD6E4F7, lightInk)
        2 -> if (dark) solid(0xFF2F4E7A, Color(0xFFDDE9FA)) else solid(0xFFBCD6F6, lightInk)
        3 -> grad(0xFF8DBEF3, 0xFF5E9FE8)
        4 -> grad(0xFF5AA0F0, 0xFF3A7FDB)
        5 -> grad(0xFF52C7C0, 0xFF2BA8A3)
        6 -> grad(0xFF4CCB8A, 0xFF22A868)
        7 -> grad(0xFFFBC85A, 0xFFF2A413)
        8 -> grad(0xFFFFA25A, 0xFFF0772E)
        9 -> grad(0xFFFF7A7A, 0xFFE8475A)
        else -> grad(0xFF9A6BFF, 0xFFEC4899)
    }
}

@Composable
private fun ResultOverlay(
    title: String,
    subtitle: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
) {
    // 从不可见开始，挂上就淡入；直接写 visible = true 的话首帧已经可见，入场动画不会播
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.92f), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = primary.second,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.width(200.dp),
                ) { Text(primary.first, color = MiuixTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold) }
                if (secondary != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(text = secondary.first, onClick = secondary.second, modifier = Modifier.width(200.dp))
                }
            }
        }
    }
}

/** 等级阶梯：已经合出来过的档位亮起来，看得到离 A+ 还差几步。 */
@Composable
private fun LevelLadder(highest: Int) {
    val dark = LocalIsDarkTheme.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            Gpa2048Texts.LADDER,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GpaScale.LEVELS.forEachIndexed { i, level ->
                val reached = i <= highest
                val style = tileStyle(i, dark)
                Box(
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .graphicsLayer { alpha = if (reached) 1f else 0.28f }
                        .clip(RoundedCornerShape(8.dp))
                        .background(style.brush),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(level.letter, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = style.fg, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 按位移方向判定滑动方向，取位移绝对值更大的轴；小于阈值当作误触不响应。
 */
private fun resolveSwipeDirection(dx: Float, dy: Float, thresholdPx: Float): Direction? {
    if (abs(dx) < thresholdPx && abs(dy) < thresholdPx) return null
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) Direction.RIGHT else Direction.LEFT
    } else {
        if (dy > 0) Direction.DOWN else Direction.UP
    }
}
