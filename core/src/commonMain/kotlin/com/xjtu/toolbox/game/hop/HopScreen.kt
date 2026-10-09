package com.xjtu.toolbox.game.hop

import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.xjtu.toolbox.platform.KeepScreenOn
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.SinkFeedback

/** 画面宽度对应多少个世界单位。 */
private const val VIEW_UNITS = 5.0f
/** 蓄满时台面最多被压下去多深。 */
private const val MAX_PRESS = 0.12f

private fun hint(game: HopGame): String = when {
    game.phase == HopPhase.OVER || game.phase == HopPhase.FALLING || game.phase == HopPhase.WARPING -> ""
    game.targets.size > 1 -> "分叉路！按住屏幕，手指往左或往右挪，选要跳的那块\n金色台更小更远，跳上去 +$FORK_BONUS"
    game.springActive -> "弹簧台：这一跳力度 ×1.5，少蓄一点"
    game.current.kind == PadKind.CRUMBLE && game.crumbleLeft > 0 -> "易碎台：快跳，要塌了"
    game.current.kind == PadKind.SPIN -> "旋转台：站着会被带着转，看准再跳"
    stayBonusOf(game.current) > 0 && game.stayTime < STAY_TIME -> "在这上面停两秒有额外分"
    game.targets.any { it.kind == PadKind.ICE } -> "下一块是冰面，落地会往前滑，宁近勿远"
    game.targets.any { it.kind == PadKind.MOVING } -> "目标在来回晃，看准时机"
    game.targets.any { it.kind == PadKind.GHOST } -> "幽灵台忽隐忽现，实体时才踩得住"
    game.targets.any { it.kind == PadKind.PORTAL } -> "传送门：跳进去会被送到前面，+$PORTAL_BONUS"
    game.targets.any { it.kind == PadKind.TRAMPOLINE } -> "蹦床：落上去会自动弹到下一块"
    game.score == 0 -> "按住屏幕蓄力，松手起跳\n落在正中连续加分"
    else -> ""
}

/**
 * 远景地标的名字，按出场顺序；分数每涨 [LANDMARK_EVERY] 换一座。
 *
 * **图不在这一层**：[HopScreen] 收 `landmarkImages`（与这张表同序）。
 * Android 用 `R.drawable.hop_landmark_*` 解出来的那份（逐字未变），Web 用 :core 的
 * `composeResources` 里那 9 张同样的 webp —— 两边读的是同一份图，只是取图的途径不同。
 */
val LANDMARK_NAMES = listOf(
    "钱学森图书馆",
    "交大北门",
    "主楼",
    "雁塔校区 · 火炬手",
    "曲江校区 · 贝壳楼",
    "饮水思源碑",
    "创新港 · 风帆",
    "创新港 · 4 号楼",
    "创新港 · 5 号楼",
)
private const val LANDMARK_EVERY = 12
private const val PREF_SKIN = "hop_skin"

/**
 * 跳一跳：全屏画面，没有系统顶栏。规则在 [HopGame]，画法在 HopRender.kt，
 * 这里管输入、逐帧推进、镜头和各种一闪而过的效果。
 */
@Composable
fun HopScreen(
    onBack: () -> Unit,
    /**
     * 远景地标的图，顺序与 [LANDMARK_NAMES] 一致。
     *
     * 取图是平台的事（Android = `R.drawable.hop_landmark_*`，Web = :core 的 composeResources），
     * 屏本身只认 [ImageBitmap] —— 空列表 = 本端没图，就不画远景（游戏照常可玩）。
     */
    landmarkImages: List<ImageBitmap> = emptyList(),
) {
    val haptics = rememberHaptics()
    var game by remember { mutableStateOf(HopGame()) }
    var frame by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    // 天色跟着分数走，入夜后字换成白的
    val night = nightOf(score)
    val ink = if (night > 0.5f) Color.White else Color(0xFF2B2750)
    val panel = if (night > 0.5f) Color(0xFF232845) else Color.White
    var skin by remember {
        mutableStateOf(GameStore.getString(PREF_SKIN)?.let { id -> HopSkin.entries.firstOrNull { it.id == id } } ?: HopSkin.PAWN)
    }
    var picking by remember { mutableStateOf(false) }
    val landmarkCount = minOf(landmarkImages.size, LANDMARK_NAMES.size)
    // -1 = 这一端没有地标图：不画远景、也不浮名字
    val landmarkIndex = if (landmarkCount == 0) -1 else (score / LANDMARK_EVERY) % landmarkCount
    var landmarkShownAt by remember { mutableFloatStateOf(0f) }
    var streak by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var best by remember { mutableIntStateOf(GameStore.bestScore(GameIds.HOP)) }
    var tip by remember { mutableStateOf(hint(game)) }
    // 镜头对准的世界坐标，逐帧向「脚下和下一块之间」靠拢
    var camX by remember { mutableFloatStateOf(game.focusX) }
    var camZ by remember { mutableFloatStateOf(game.focusZ) }
    val fx = remember(game) { HopFx() }
    val shownScore by animateIntAsState(score, tween(300), label = "score")
    val measurer = rememberTextMeasurer()

    fun restart() {
        game = HopGame()
        over = false
        paused = false
        score = 0
        streak = 0
        camX = game.focusX
        camZ = game.focusZ
        tip = hint(game)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, game) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) game.cancelCharge() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 有分了再按返回，先停下问一句，别一滑就把这局丢了
    fun leave() {
        if (!over && game.score > 0) {
            game.cancelCharge()
            paused = true
        } else onBack()
    }
    BackHandler(enabled = !paused && !picking) { leave() }
    BackHandler(enabled = picking) { picking = false }
    // 换地标时记下时刻：新的一座淡入，顶上浮出它的名字
    LaunchedEffect(landmarkIndex) { landmarkShownAt = fx.time }

    // 屏幕常亮：平台能力（Android = View.keepScreenOn，其余端空实现）
    KeepScreenOn(enabled = !over)

    LaunchedEffect(game) {
        val random = Random.Default
        var last = withFrameNanos { it }
        var gain = game.gainSerial
        var land = game.landSerial
        var release = game.releaseSerial
        var stayBonus = game.stayBonusSerial
        var wasFalling = false
        var wasCharging = false
        var chargeStream = 0
        while (game.phase != HopPhase.OVER) {
            withFrameNanos { now ->
                val real = ((now - last) / 1e9f).coerceAtMost(0.05f)
                val dt = if (paused || picking) 0f else real
                last = now
                game.update(dt)
                fx.step(dt)
                // 选角色时游戏停着，但预览里的角色还要动
                if (picking) fx.time += real
                val k = (dt * 5f).coerceAtMost(1f)
                camX += (game.focusX - camX) * k
                camZ += (game.focusZ - camZ) * k
                if (game.phase == HopPhase.CHARGING) fx.gather(game, random)
            }
            // 蓄力音跟着状态走：松手、切后台、打开选角色都会离开蓄力，统一在这里停
            val charging = game.phase == HopPhase.CHARGING
            if (charging != wasCharging) {
                GameSound.stop(chargeStream)
                chargeStream = if (charging) GameSound.play(Sfx.SLIDE_UP, 0.5f) else 0
            }
            wasCharging = charging
            if (game.releaseSerial != release) {
                release = game.releaseSerial
                fx.bouncePad = game.launchPad
                fx.bounceAmp = game.launchCharge * MAX_PRESS
                fx.bounceT = 0f
                fx.gathered.clear()
            }
            if (game.stayBonusSerial != stayBonus) {
                stayBonus = game.stayBonusSerial
                score = game.score
                haptics.success()
                GameSound.play(Sfx.COIN)
                fx.popups += Popup("+${game.lastStayBonus}", game.px, 0.75f, game.pz, 0f, Color(0xFFF2A413))
            }
            if (game.landSerial != land) {
                land = game.landSerial
                fx.sinceLand = 0f
                val center = game.gainSerial != gain && game.lastWasCenter
                fx.dust(game.px, game.pz, random, if (center) 14 else 8)
                fx.ripples += Ripple(game.px, game.pz, 0f, 0f, center)
                haptics.tick()
                // 落正中「叮」，连击沿音阶往上爬；普通落地「啵嘤」一声
                if (center) GameSound.play(Sfx.CHIME, 0.9f, GameSound.scale(game.streak - 1))
                else GameSound.play(Sfx.BOING, 0.6f)
            }
            if (game.gainSerial != gain) {
                gain = game.gainSerial
                score = game.score
                streak = game.streak
                val gold = game.lastWasCenter || game.lastGain >= FORK_BONUS
                if (gold) haptics.success()
                fx.popups += Popup("+${game.lastGain}", game.px, 0.75f, game.pz, 0f, if (gold) Color(0xFFF2A413) else ink)
            }
            val falling = game.phase == HopPhase.FALLING
            if (falling && !wasFalling) {
                streak = 0
                GameSound.stop(chargeStream)
                GameSound.play(Sfx.SLIDE_DOWN, 0.8f)
                if (game.current.fading) fx.debris(game.current, random)
            }
            wasFalling = falling
            tip = hint(game)
            frame++
        }
        over = true
        haptics.error()
        GameSound.play(Sfx.SAD_TROMBONE, 0.8f)
        GameStore.submitScore(GameIds.HOP, game.score)
        best = maxOf(best, game.score)
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(game) {
                    val sideOf = { x: Float -> if (x < size.width / 2) -1 else 1 }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        game.press(sideOf(down.position.x))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            game.aim(sideOf(change.position.x))
                        }
                        game.release()
                    }
                },
        ) {
            if (frame < 0) return@Canvas // 游戏状态不是 Compose state，读一下帧号让画布每帧重画
            drawSky(game.score, fx.time)
            val scale = size.width / VIEW_UNITS
            if (landmarkIndex >= 0) {
                drawLandmark(landmarkImages[landmarkIndex], (fx.time - landmarkShownAt) / 1.2f, night, -(camX - camZ) * scale * 0.05f)
            }
            // 镜头对准的点落在屏幕中下部
            val iso = Iso(
                size.width / 2 - (camX - camZ) * COS30 * scale,
                size.height * 0.62f + (camX + camZ) * SIN30 * scale,
                scale,
            )
            val charging = game.phase == HopPhase.CHARGING
            val squash = if (charging) game.charge / MAX_CHARGE else 0f
            fun pressOf(pad: Pad): Float = when {
                pad === game.current && charging -> squash * MAX_PRESS
                pad === fx.bouncePad -> bounceOffset(fx.bounceT, fx.bounceAmp)
                else -> 0f
            }

            game.pads.forEach { drawPadShadow(it, iso) }

            // 远的先画；棋子插在它脚下那块之后，比它近的台子会挡住它
            val standing = game.phase == HopPhase.IDLE || charging || game.phase == HopPhase.SLIDING || game.phase == HopPhase.WARPING
            val stay = if (standing && stayBonusOf(game.current) > 0 && game.stayTime < STAY_TIME) game.stayTime / STAY_TIME else -1f
            val playerKey = when {
                standing -> game.current.x + game.current.z - 0.001f
                game.phase == HopPhase.FLYING -> Float.NEGATIVE_INFINITY
                else -> game.px + game.pz
            }
            var playerDrawn = false
            val crumbleShake = if (game.current.kind == PadKind.CRUMBLE && game.crumbleLeft > 0f && standing) {
                sin(fx.time * 70f) * 0.012f * (1f - game.crumbleLeft / CRUMBLE_TIME)
            } else 0f
            for (pad in game.pads.sortedByDescending { it.x + it.z }) {
                if (!playerDrawn && pad.x + pad.z < playerKey) {
                    drawPlayer(game, skin, iso, squash, pressOf(game.current), fx)
                    playerDrawn = true
                }
                drawPad(pad, iso, pressOf(pad), if (pad === game.current) crumbleShake else 0f, fx.time, night, if (pad === game.current) stay else -1f)
                if (pad.bonus > 0 && !pad.fading) {
                    val label = measurer.measure("+$FORK_BONUS", TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFFE09A00)))
                    val at = iso.p(pad.x, pad.z, 0.42f + 0.04f * sin(fx.time * 4f))
                    drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f))
                }
            }
            drawForkMarks(game, iso, fx.time)
            drawRipples(fx.ripples, iso)
            if (!playerDrawn) drawPlayer(game, skin, iso, squash, pressOf(game.current), fx)
            drawMotes(fx.motes, iso)
            drawMotes(fx.gathered, iso)
            for (p in fx.popups) {
                val layout = measurer.measure(p.text, TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, color = p.color))
                val at = iso.p(p.x, p.z, p.y + p.t * 0.5f)
                drawText(layout, topLeft = at - Offset(layout.size.width / 2f, layout.size.height / 2f), alpha = 1f - p.t * p.t)
            }
        }

        // 左上：返回 + 分数
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.08f))
                    .clickable(remember { MutableInteractionSource() }, SinkFeedback(), onClick = ::leave),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(shownScore.toString(), fontSize = 46.sp, fontWeight = FontWeight.Black, color = ink, lineHeight = 48.sp)
                Text("最高 ${maxOf(best, score)}", fontSize = 13.sp, color = ink.copy(alpha = 0.55f))
                if (streak >= 2) {
                    Text("完美 ×$streak", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFFF2A413))
                }
            }
        }

        // 右上：音效开关、换角色
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (GameSound.enabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (GameSound.enabled) "关闭音效" else "打开音效",
                tint = ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(ink.copy(alpha = 0.08f))
                    .clickable(remember { MutableInteractionSource() }, SinkFeedback()) {
                        GameSound.enabled = !GameSound.enabled
                    }
                    .padding(8.dp)
                    .size(20.dp),
            )
            Text(
                skin.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(ink.copy(alpha = 0.08f))
                    .clickable(remember { MutableInteractionSource() }, SinkFeedback()) {
                        game.cancelCharge()
                        picking = true
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // 换地标时，顶上浮出它的名字，两秒多后淡掉
        val sinceLandmark = fx.time - landmarkShownAt
        if (frame >= 0 && score > 0 && landmarkIndex >= 0 && sinceLandmark < 2.6f) {
            Text(
                LANDMARK_NAMES[landmarkIndex],
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 120.dp)
                    .graphicsLayer { alpha = (minOf(sinceLandmark, 2.6f - sinceLandmark) / 0.4f).coerceIn(0f, 1f) },
            )
        }

        if (tip.isNotEmpty() && !over) {
            Text(
                tip,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(panel.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        when {
            picking -> SkinPicker(skin, panel, ink, fx, frame) {
                skin = it
                GameStore.putString(PREF_SKIN, it.id)
                picking = false
            }
            over -> GameMenu(
                title = "掉下去了",
                big = score.toString(),
                subtitle = if (score >= best && score > 0) "新纪录！" else "最高 $best",
                actions = listOf("再来一局" to { restart() }, "退出" to onBack),
                accent = ACCENT, panel = panel, ink = ink,
            )
            paused -> GameMenu(
                title = "暂停",
                big = score.toString(),
                subtitle = "最高 ${maxOf(best, score)}",
                actions = listOf("继续" to { paused = false }, "重新开始" to { restart() }, "退出" to onBack),
                accent = ACCENT, panel = panel, ink = ink,
            )
        }
    }
}

private val ACCENT = Color(0xFF6C63FF)

/** 选角色：四格，每格里角色在原地做它的待机动作，点一下就换。 */
@Composable
private fun SkinPicker(current: HopSkin, panel: Color, ink: Color, fx: HopFx, frame: Int, onPick: (HopSkin) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(current) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(panel)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("选角色", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.7f))
            Spacer(Modifier.height(14.dp))
            HopSkin.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { s ->
                        Column(
                            Modifier
                                .size(120.dp, 140.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (s == current) ACCENT.copy(alpha = 0.15f) else ink.copy(alpha = 0.05f))
                                .clickable(remember { MutableInteractionSource() }, SinkFeedback()) { onPick(s) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(Modifier.size(110.dp)) {
                                if (frame < 0) return@Canvas // 读帧号让预览每帧重画
                                drawSkin(
                                    s,
                                    PieceState(
                                        feet = Offset(size.width / 2, size.height * 0.85f), scale = size.width * 0.9f,
                                        squash = 0f, flying = false, flight = 0f, side = 1, tilt = 0f,
                                        time = fx.time, sinceLand = 1f,
                                    ),
                                )
                            }
                            Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (s == current) ACCENT else ink)
                        }
                    }
                }
            }
        }
    }
}

/** 画角色：先在脚下落影子，再按当前状态交给皮肤去画。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayer(game: HopGame, skin: HopSkin, iso: Iso, squash: Float, press: Float, fx: HopFx) {
    val flying = game.phase == HopPhase.FLYING
    val feetY = if (flying || game.phase == HopPhase.FALLING || game.phase == HopPhase.OVER) game.py else -press
    // 影子落在脚下那块台面上，脚下没台子就落到地面
    val under = game.pads.firstOrNull { !it.fading && it.contains(game.px, game.pz) }
    val surface = if (under != null) (if (under === game.current) -press else 0f) else -PAD_HEIGHT
    if (feetY >= surface - 0.01f) drawPieceShadow(iso.p(game.px, game.pz, surface), iso.scale, feetY - surface)
    val tilt = if (game.tipping && (game.phase == HopPhase.FALLING || game.phase == HopPhase.OVER)) {
        (if (game.tiltX - game.tiltZ >= 0) 85f else -85f) * (game.fallProgress / 0.35f).coerceAtMost(1f)
    } else 0f
    // 站着时面朝要跳的那块，空中面朝起跳方向
    val side = when {
        flying -> game.jumpSide
        game.targets.isNotEmpty() -> if (game.aimTarget.axis == 0) 1 else -1
        else -> 1
    }
    // 起跳瞬间被弹一下：给负的压缩量表示拉长
    val stretch = if (flying && game.flightProgress < 0.2f) -0.25f * (1f - game.flightProgress / 0.2f) else 0f
    // 传送：前一半在入口缩没，后一半在出口冒出来
    val size = if (game.phase == HopPhase.WARPING) kotlin.math.abs(1f - game.warpProgress * 2f) else 1f
    drawSkin(
        skin,
        PieceState(
            feet = iso.p(game.px, game.pz, feetY), scale = iso.scale, squash = squash + stretch,
            flying = flying, flight = game.flightProgress, side = side, tilt = tilt,
            time = fx.time, sinceLand = fx.sinceLand, size = size,
        ),
    )
    if (game.springActive && squash > 0f) {
        val c = iso.p(game.px, game.pz, feetY)
        drawCircle(Color(0xFFFF8FB1), iso.scale * 0.3f * squash, c, alpha = 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(iso.scale * 0.015f))
    }
}

/** 一局里的临时效果：粒子、波纹、飘字、起跳后台子的回弹。 */
private class HopFx {
    val motes = mutableListOf<Mote>()
    /** 蓄力时往棋子身上聚的光点，松手就清掉。 */
    val gathered = mutableListOf<Mote>()
    val ripples = mutableListOf<Ripple>()
    val popups = mutableListOf<Popup>()
    var bouncePad: Pad? = null
    var bounceAmp = 0f
    var bounceT = 1f
    var time = 0f
    /** 落地后过了多久，角色的落地回弹动作用。 */
    var sinceLand = 1f

    fun step(dt: Float) {
        time += dt
        bounceT += dt
        sinceLand += dt
        stepMotes(motes, dt)
        stepMotes(gathered, dt)
        ripples.forEach { it.t += dt / 0.6f }
        ripples.removeAll { it.t >= 1f }
        popups.forEach { it.t += dt / 0.9f }
        popups.removeAll { it.t >= 1f }
    }

    fun dust(x: Float, z: Float, random: Random, count: Int) {
        repeat(count) {
            val a = random.nextFloat() * 6.283f
            val v = 0.5f + random.nextFloat() * 0.6f
            motes += Mote(x, 0.02f, z, cos(a) * v, 0.4f + random.nextFloat() * 0.5f, sin(a) * v, 0.45f, 0.45f, Color.White, 0.035f, 2.5f)
        }
    }

    fun gather(game: HopGame, random: Random) {
        if (random.nextFloat() > 0.5f) return
        val a = random.nextFloat() * 6.283f
        val r = 0.45f + random.nextFloat() * 0.3f
        val x = game.px + cos(a) * r
        val z = game.pz + sin(a) * r
        val y = 0.05f + random.nextFloat() * 0.5f
        val life = 0.35f
        gathered += Mote(x, y, z, (game.px - x) / life, (0.3f - y) / life, (game.pz - z) / life, life, life, Color.White, 0.025f, 0f)
    }

    fun debris(pad: Pad, random: Random) {
        repeat(16) {
            val x = pad.x + (random.nextFloat() - 0.5f) * pad.half * 2
            val z = pad.z + (random.nextFloat() - 0.5f) * pad.half * 2
            motes += Mote(x, -random.nextFloat() * PAD_HEIGHT, z, (random.nextFloat() - 0.5f) * 0.6f, random.nextFloat() * 0.8f, (random.nextFloat() - 0.5f) * 0.6f, 0.8f, 0.8f, Color(0xFFB09D86), 0.05f, 5f)
        }
    }
}
