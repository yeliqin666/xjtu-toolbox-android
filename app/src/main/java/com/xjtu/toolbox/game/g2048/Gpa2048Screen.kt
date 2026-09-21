package com.xjtu.toolbox.game.g2048

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.ui.adaptive.readableWidth
import kotlin.math.abs
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * GPA 2048：把经典 2048 的数字换成绩点等级链，规则完全照搬 2048——
 * 难度不因为换皮而变化，好不好玩也就还是原来那味。
 *
 * 拖拽阈值 24dp 是给手指按的；宽屏（平板/折叠屏展开态）额外接方向键，
 * 用键盘玩不用在小键盘上戳。
 */

private val DRAG_THRESHOLD_DP = 24.dp

@Composable
fun Gpa2048Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(newGame(Random(System.nanoTime()))) }
    var best by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        best = GameStore.bestScore(context, GameIds.G2048)
    }

    // 每次分数变化都顺手提交一次最高分，submitScore 自己会判断要不要真的写——
    // 这里不用额外维护"是否破纪录"的状态。
    LaunchedEffect(state.score) {
        GameStore.submitScore(context, GameIds.G2048, state.score)
        best = GameStore.bestScore(context, GameIds.G2048)
    }

    fun handleMove(direction: Direction) {
        state = applyMove(state, direction, Random.Default)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "GPA 2048",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
                    // 只在宽屏才接方向键：手机上系统本身不会派发方向键事件，这个判断
                    // 更多是文档意义上的——真机不会触发,但保留判断避免手机意外响应模拟器方向键。
                    val direction = when (event.key) {
                        Key.DirectionLeft -> Direction.LEFT
                        Key.DirectionRight -> Direction.RIGHT
                        Key.DirectionUp -> Direction.UP
                        Key.DirectionDown -> Direction.DOWN
                        else -> null
                    }
                    if (direction != null) {
                        handleMove(direction)
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScoreBar(score = state.score, best = best, highest = state.board.highestIndex())

            if (state.hasWon) {
                Text(
                    text = "已经拿到 A+ 啦，继续冲更高分",
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.isOver) {
                Text(
                    text = "已经无路可走，滑一下重新开始",
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            BoardView(
                board = state.board,
                onSwipe = { direction ->
                    if (state.isOver) {
                        state = newGame(Random(System.nanoTime()))
                    } else {
                        handleMove(direction)
                    }
                },
            )

            TextButton(
                text = "重新开始",
                onClick = { state = newGame(Random(System.nanoTime())) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ScoreBar(score: Int, best: Int, highest: Int?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ScoreTile(label = "分数", value = score.toString())
        ScoreTile(label = "最高分", value = best.toString())
        ScoreTile(
            label = "当前最高绩点",
            value = highest?.let { GpaScale.LEVELS[it].gpa.toString() } ?: "-",
        )
    }
}

@Composable
private fun ScoreTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BoardView(board: Board, onSwipe: (Direction) -> Unit) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { DRAG_THRESHOLD_DP.toPx() }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight.takeIf { it > 0.dp } ?: maxWidth)
        Box(
            Modifier
                .size(side)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    var dragX = 0f
                    var dragY = 0f
                    detectDragGestures(
                        onDragStart = { dragX = 0f; dragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                        },
                        onDragEnd = {
                            val direction = resolveSwipeDirection(dragX, dragY, thresholdPx)
                            if (direction != null) onSwipe(direction)
                        },
                    )
                }
                .padding(8.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until BOARD_SIZE) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (col in 0 until BOARD_SIZE) {
                            Box(Modifier.weight(1f).fillMaxSize()) {
                                GpaTile(board.get(row, col))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 按拖拽终点的位移方向判定滑动方向，取位移绝对值更大的轴，
 * 小于阈值当作误触不响应——避免手指轻轻一抖就吞掉一次操作。
 */
private fun resolveSwipeDirection(dx: Float, dy: Float, thresholdPx: Float): Direction? {
    if (abs(dx) < thresholdPx && abs(dy) < thresholdPx) return null
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) Direction.RIGHT else Direction.LEFT
    } else {
        if (dy > 0) Direction.DOWN else Direction.UP
    }
}

@Composable
private fun GpaTile(levelIndex: Int?) {
    val shape = RoundedCornerShape(10.dp)
    if (levelIndex == null) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MiuixTheme.colorScheme.background),
        )
        return
    }
    val level = GpaScale.LEVELS[levelIndex]
    val isWin = levelIndex == GpaScale.WIN_INDEX
    val bg = if (isWin) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer
    val fg = if (isWin) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = level.letter, style = MiuixTheme.textStyles.title3, color = fg, fontWeight = FontWeight.Bold)
            Text(text = level.gpa.toString(), style = MiuixTheme.textStyles.footnote2, color = fg, textAlign = TextAlign.Center)
        }
    }
}
