package com.xjtu.toolbox.game.blocks

import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameSound
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.game.Sfx
import com.xjtu.toolbox.game.ui.GameMenu
import com.xjtu.toolbox.ui.rememberHaptics
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.SinkFeedback

/** 最高分按玩法分开记：`best_blocks_marathon` 这样。 */
fun blocksRecordId(mode: BlocksMode) = "${GameIds.BLOCKS}_${mode.id}"

private fun BlocksGame.rotateWithSound(clockwise: Boolean) {
    rotate(clockwise)
    GameSound.play(Sfx.TICK, 0.5f)
}


/**
 * 方块：全屏画面，极简扁平风，跟随系统深浅色。配色和画法在 BlocksRender.kt。
 *
 * 纯手势操作：左右拖挪、轻点转、往下快划直落（见 [detectBlocksGestures]）。
 * 接键盘时方向键 / 空格 / Z X 也能玩。
 */
@Composable
fun BlocksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = blocksPalette()
    var mode by remember { mutableStateOf<BlocksMode?>(null) }
    var bests by remember { mutableStateOf(BlocksMode.entries.associateWith { GameStore.bestScore(context, blocksRecordId(it)) }) }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        val current = mode
        if (current == null) {
            ModePicker(bests, palette, onPick = { mode = it }, onBack = onBack)
        } else {
            BlocksPlay(
                mode = current,
                best = bests.getValue(current),
                palette = palette,
                onRecord = { score ->
                    GameStore.submitScore(context, blocksRecordId(current), score)
                    bests = bests + (current to maxOf(bests.getValue(current), score))
                },
                onExit = onBack,
            )
        }
    }
}

/** 顶栏上的图标按钮：没有底色，只有图标，点按时轻微下沉。 */
@Composable
private fun BarIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, SinkFeedback(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ModePicker(bests: Map<BlocksMode, Int>, palette: BlocksPalette, onPick: (BlocksMode) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Box(Modifier.padding(top = 4.dp).offset(x = (-10).dp)) {
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回", palette.ink, onBack)
        }
        Spacer(Modifier.height(20.dp))
        Text("方块", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = palette.ink)
        Text("选一种玩法开始", fontSize = 15.sp, color = palette.dim)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BlocksMode.entries.forEach { m ->
                val accent = accentOf(m, 1)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(palette.panel)
                        .clickable(remember { MutableInteractionSource() }, SinkFeedback()) { onPick(m) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(
                        Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(accent.copy(alpha = if (palette.dark) 0.16f else 0.10f)),
                    ) { drawModeArt(m, palette) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                        Spacer(Modifier.height(2.dp))
                        Text(m.summary, fontSize = 13.sp, color = palette.dim)
                    }
                    val best = bests.getValue(m)
                    if (best > 0) {
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 6.dp)) {
                            Text("最高", fontSize = 11.sp, color = palette.dim)
                            Text(best.toString(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = accent)
                        }
                    }
                }
            }
        }
    }
}

/** 选玩法卡片上的小插画：几块方块摆出这种玩法的意思。 */
private fun DrawScope.drawModeArt(mode: BlocksMode, palette: BlocksPalette) {
    val cs = size.width / 5.5f
    val ox = (size.width - cs * 4) / 2
    val base = size.height - cs * 0.9f
    fun block(x: Int, y: Int, value: Int, alpha: Float = 1f) =
        drawFlatBlock(ox + x * cs, base - (y + 1) * cs, cs, blockColor(value, palette), alpha)
    when (mode) {
        // 越堆越高
        BlocksMode.MARATHON -> {
            intArrayOf(1, 2, 3, 4).forEachIndexed { x, h -> for (y in 0 until h) block(x, y, (x + y) % 7 + 1) }
        }
        // 一块正在落下的 T，下面一行快满了
        BlocksMode.ULTRA -> {
            for (x in 0 until 4) if (x != 1) block(x, 0, 7)
            block(0, 2, 3); block(1, 2, 3); block(2, 2, 3); block(1, 1, 3, 0.9f)
        }
        // 底下两行灰色垃圾往上顶
        BlocksMode.RISE -> {
            for (y in 0 until 2) for (x in 0 until 4) if (x != (y + 2) % 4) block(x, y, GARBAGE)
            block(1, 3, 5); block(2, 3, 5); block(1, 2, 5); block(2, 2, 5)
        }
    }
}

@Composable
private fun BlocksPlay(mode: BlocksMode, best: Int, palette: BlocksPalette, onRecord: (Int) -> Unit, onExit: () -> Unit) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    var game by remember { mutableStateOf(BlocksGame(mode)) }
    val fx = remember(game) { BlocksFx() }
    var frame by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var lines by remember { mutableIntStateOf(0) }
    var level by remember { mutableIntStateOf(1) }
    var secondsLeft by remember { mutableIntStateOf((ULTRA_MS / 1000).toInt()) }
    var paused by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var cellPx by remember { mutableFloatStateOf(1f) }
    var label by remember { mutableStateOf<ClearEvent?>(null) }
    val labelAnim = remember { Animatable(1f) }
    val levelAnim = remember { Animatable(1f) }
    val bump = remember { Animatable(0f) }
    val shownScore by animateIntAsState(score, tween(260), label = "score")
    val accent = accentOf(mode, level)

    fun sync() {
        frame++
        score = game.score
        lines = game.lines
        level = game.level
        // 冲分是整局倒计时，抬升是下一次抬升的倒计时
        val leftMs = if (mode == BlocksMode.RISE) RISE_MS - game.riseMs else game.timeLeftMs
        secondsLeft = ((leftMs + 999) / 1000).toInt()
    }

    fun restart() {
        game = BlocksGame(mode)
        paused = false
        over = false
        label = null
        sync()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) paused = true }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(paused, over) {
        view.keepScreenOn = !paused && !over
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(game, paused) {
        if (paused) {
            game.softDropping = false
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        var lastLock = game.lockCount
        var lastClear = game.clearEvent?.serial ?: 0
        var lastDrop = game.hardDrops
        var lastLevel = game.level
        while (!game.over) {
            withFrameNanos { now ->
                val ms = ((now - last) / 1_000_000L).coerceAtMost(100)
                // 只吃掉整毫秒部分，零头留给下一帧，免得时间越跑越慢
                last += ms * 1_000_000L
                // 消行动画放完之前停住重力和锁定计时，按键照常响应
                if (!fx.clearing) game.tick(ms)
                fx.step(ms / 1000f)
            }
            if (game.lockCount != lastLock) {
                lastLock = game.lockCount
                fx.lockCells = game.lastLocked
                fx.lockT = 0f
                haptics.lowTick()
                GameSound.play(Sfx.KNOCK, 0.6f)
            }
            if (game.hardDrops != lastDrop) {
                lastDrop = game.hardDrops
                GameSound.play(Sfx.BONK, 0.7f)
                // 直落：棋盘被砸得往下一沉再弹回
                launch {
                    bump.snapTo(with(density) { 4.dp.toPx() })
                    bump.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
                }
            }
            val ev = game.clearEvent
            if (ev != null && ev.serial != lastClear) {
                lastClear = ev.serial
                fx.clear = ev
                fx.clearT = 0f
                if (ev.big) haptics.success() else haptics.tick()
                // 一次消得越多「啵」得越高，大消除再来一声「嗒哒」
                GameSound.play(Sfx.POP, 0.9f, GameSound.scale(ev.rows.size * 2 - 2))
                if (ev.big) GameSound.play(Sfx.TADA, 0.8f)
                label = ev
                launch {
                    labelAnim.snapTo(0f)
                    labelAnim.animateTo(1f, tween(if (ev.big) 1300 else 950))
                }
            }
            if (game.level != lastLevel) {
                lastLevel = game.level
                GameSound.play(Sfx.COIN)
                launch {
                    levelAnim.snapTo(0f)
                    levelAnim.animateTo(1f, tween(1300))
                }
            }
            sync()
        }
        sync()
        over = true
        haptics.error()
        GameSound.play(Sfx.SAD_TROMBONE, 0.8f)
        onRecord(game.score)
    }
    LaunchedEffect(score) { if (score > best) onRecord(score) }

    fun act(block: BlocksGame.() -> Unit) {
        if (paused || game.over) return
        game.block()
        frame++
    }
    // 系统返回：对局中先暂停，暂停 / 结算时再按就退出
    BackHandler {
        if (paused || over) onExit() else paused = true
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> act { moveLeft() }
                    Key.DirectionRight -> act { moveRight() }
                    Key.DirectionDown -> act { softDrop() }
                    Key.DirectionUp, Key.X -> act { rotateWithSound(true) }
                    Key.Z -> act { rotateWithSound(false) }
                    Key.Spacebar -> act { hardDrop() }
                    Key.P, Key.Escape -> if (!over) paused = !paused
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
            // 顶栏：返回 · 分数 · 暂停
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回", palette.ink) { if (!over) paused = true }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(shownScore.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = palette.ink, lineHeight = 30.sp)
                }
                BarIcon(Icons.Filled.Pause, "暂停", palette.ink) { if (!over) paused = true }
            }
            // 信息条：数据 · 下一个
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    when (mode) {
                        BlocksMode.MARATHON -> Stat("等级", level.toString(), accent, palette)
                        BlocksMode.ULTRA -> Stat(
                            "剩余", "%d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                            if (secondsLeft <= 10) Color(0xFFEF476F) else palette.ink, palette,
                        )
                        BlocksMode.RISE -> Stat("抬升", "${secondsLeft}s", if (secondsLeft <= 2) Color(0xFFEF476F) else palette.ink, palette)
                    }
                    Stat("行数", lines.toString(), palette.ink, palette)
                    Stat("最高", maxOf(best, score).toString(), palette.ink, palette)
                }
                PieceSlot("下一个", palette) {
                    if (frame < 0) return@PieceSlot
                    val next = game.preview(3)
                    // 下一块大一点在上，后两块小一点并排在下
                    next.getOrNull(0)?.let { drawMini(it, size.width / 6f, palette, center = Offset(size.width / 2, size.height * 0.36f)) }
                    next.getOrNull(1)?.let { drawMini(it, size.width / 12f, palette, 0.55f, Offset(size.width * 0.3f, size.height * 0.8f)) }
                    next.getOrNull(2)?.let { drawMini(it, size.width / 12f, palette, 0.55f, Offset(size.width * 0.7f, size.height * 0.8f)) }
                }
            }

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(game) { detectBlocksGestures({ cellPx }, { game.lockCount }, ::act) },
                contentAlignment = Alignment.Center,
            ) {
                val inset = 6.dp
                val cell = minOf((maxWidth - inset * 2) / BOARD_WIDTH, (maxHeight - inset * 2) / (BOARD_HEIGHT - HIDDEN_ROWS))
                val boardW = cell * BOARD_WIDTH
                val boardH = cell * (BOARD_HEIGHT - HIDDEN_ROWS)
                Box(
                    Modifier
                        .offset { IntOffset(0, bump.value.roundToInt()) }
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.board)
                        .padding(inset),
                ) {
                    Canvas(Modifier.size(boardW, boardH)) {
                        if (frame < 0) return@Canvas // 游戏状态不是 Compose state，读一下帧号让画布每帧重画
                        cellPx = size.width / BOARD_WIDTH
                        drawBoard(game, fx, cellPx, palette)
                    }
                    val ev = label
                    if (ev != null && ev.label.isNotEmpty() && labelAnim.value < 1f) {
                        val t = labelAnim.value
                        Text(
                            ev.label,
                            fontSize = if (ev.big) 28.sp else 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (ev.big) accent else palette.ink,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    // 弹出时略过头再回落，停一会儿后往上淡出
                                    val pop = if (t < 0.18f) 0.7f + 0.4f * (t / 0.18f) else 1.1f - minOf(0.1f, (t - 0.18f))
                                    scaleX = pop
                                    scaleY = pop
                                    translationY = if (t > 0.6f) -24.dp.toPx() * (t - 0.6f) / 0.4f else 0f
                                    alpha = if (t < 0.6f) 1f else (1f - t) / 0.4f
                                },
                        )
                    }
                    if (levelAnim.value < 1f) {
                        val t = levelAnim.value
                        Text(
                            "等级 $level",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = boardH * 0.28f)
                                .graphicsLayer {
                                    scaleX = 0.85f + 0.2f * minOf(1f, t * 5f)
                                    scaleY = scaleX
                                    alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
                                },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        when {
            over -> GameMenu(
                title = if (game.timeUp) "时间到" else "堆满了",
                big = score.toString(),
                subtitle = "消了 $lines 行" + if (score >= best && score > 0) " · 新纪录" else " · 最高 $best",
                actions = listOf("再来一局" to { restart() }, "退出" to onExit),
                accent = accent, panel = palette.panel, ink = palette.ink,
            )
            paused -> GameMenu(
                title = "暂停",
                subtitle = "本局 $score 分",
                actions = listOf("继续" to { paused = false }, "重新开始" to { restart() }, "退出" to onExit),
                accent = accent, panel = palette.panel, ink = palette.ink,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, palette: BlocksPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, fontSize = 11.sp, color = palette.dim)
    }
}

/** 放一块方块的小卡片，左上角标一行小字。 */
@Composable
private fun PieceSlot(label: String, palette: BlocksPalette, draw: DrawScope.() -> Unit) {
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(palette.panel)) {
        Text(label, fontSize = 10.sp, color = palette.dim, modifier = Modifier.padding(start = 8.dp, top = 5.dp))
        Canvas(Modifier.fillMaxSize().padding(top = 12.dp), onDraw = draw)
    }
}


/**
 * 棋盘上的手势，全部操作都在这里：
 * - 左右拖：跟着手指一格一格挪；
 * - 轻点：顺时针转；
 * - 往下快划：直接落到底。防误触靠四条——划得够远（≥ 2.5 格）、够快（按下后 [SWIPE_MS] 内）、
 *   够竖（竖向是横向两倍以上），并且这一划里还没横着挪过；另外一划只落一块：手指按下后
 *   方块已经换了（刚锁定出了新块），这一划作废，免得下一块也被砸下去。
 */
private suspend fun PointerInputScope.detectBlocksGestures(
    cellPx: () -> Float,
    lockCount: () -> Int,
    act: (BlocksGame.() -> Unit) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown()
        val startLock = lockCount()
        var accX = 0f
        var moved = false
        var movedX = false
        var dropped = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (!moved && change.uptimeMillis - down.uptimeMillis < 250) act { rotateWithSound(true) }
                break
            }
            val total = change.position - down.position
            if (total.getDistance() > slop) moved = true
            if (!moved || dropped) continue
            change.consume()
            val cell = cellPx()
            if (!movedX && lockCount() == startLock && change.uptimeMillis - down.uptimeMillis <= SWIPE_MS &&
                total.y > cell * 2.5f && total.y > abs(total.x) * 2f
            ) {
                act { hardDrop() }
                dropped = true
                continue
            }
            // 主要在往下划时不横挪，免得直落前手指一歪先挪了一格
            if (abs(total.x) < abs(total.y) * 0.6f) continue
            val step = cell * 0.8f
            accX += (change.position - change.previousPosition).x
            while (accX >= step) { act { moveRight() }; accX -= step; movedX = true }
            while (accX <= -step) { act { moveLeft() }; accX += step; movedX = true }
        }
    }
}

private const val SWIPE_MS = 350L
