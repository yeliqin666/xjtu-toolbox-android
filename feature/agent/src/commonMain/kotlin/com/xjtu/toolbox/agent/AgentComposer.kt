@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.xjtu.toolbox.ui.glass.CouiGlassPanel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mic
import com.xjtu.toolbox.platform.decodeImageFull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对话输入栏：输入区和发送键同处一个圆角胶囊，发送键是容器内的实心圆。
 * 用 BasicTextField 自己画占位符，不用 miuix TextField——那是带浮动 label 的表单控件。
 */
@Composable
internal fun AgentComposer(
    /** 端上资源：附件缩略图要读本地文件（见 [AgentHost.readImageBytes]）。 */
    host: AgentHost,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    contextExhausted: Boolean,
    bottomReserve: Dp,
    /** 输入框空着时的占位文字。 */
    placeholder: String = "问一句…",
    /** 待发送的图片路径。空列表表示这个模型不支持图片，连按钮都不显示。 */
    attachments: List<String> = emptyList(),
    /** 给不给图片入口：模型认图片**且这一端有相册**（调用点把两件合成这一个布尔）。 */
    visionEnabled: Boolean = false,
    onPickImage: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    /** 玻璃模式下的采样源：聊天内容那层 COUI 采样；null（经典风格 / 非 Android）时纯白/黑实底。 */
    pageBackdrop: com.xjtu.toolbox.ui.glass.CouiPageBackdrop? = null,
    onOpenShared: (() -> Unit)? = null,
    /** 语音输入所处阶段；null = 这一端或这份配置没有语音输入，不画麦克风。 */
    voicePhase: VoicePhase? = null,
    /** 按住麦克风：开始录。 */
    onVoiceStart: () -> Unit = {},
    /** 松手：结束并识别；[cancel] 为真（上滑取消）丢掉这段。 */
    onVoiceEnd: (cancel: Boolean) -> Unit = {},
) {
    // 按住说话时手指滑进了取消区；点一下（没按住）时提示一下要按住
    var voiceArmed by remember { mutableStateOf(false) }
    var holdHint by remember { mutableStateOf(false) }
    LaunchedEffect(holdHint) {
        if (holdHint) {
            kotlinx.coroutines.delay(1600)
            holdHint = false
        }
    }
    // 只发图不打字是合理的（「这是什么」「帮我看看这张课表」）。
    val canSend = (input.isNotBlank() || attachments.isNotEmpty()) && !contextExhausted
    val actionEnabled = isLoading || canSend
    var focused by remember { mutableStateOf(false) }

    // 聚焦时描边亮起来。这是唯一的状态反馈，所以别做得太隐晦，也别做成整框变色。
    val borderColor by animateColorAsState(
        targetValue = when {
            contextExhausted -> MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
            focused -> MiuixTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
        },
        animationSpec = tween(180),
        label = "composerBorder",
    )
    val sendBg by animateColorAsState(
        targetValue = if (actionEnabled) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        animationSpec = tween(180),
        label = "composerSendBg",
    )

    // ── 底部让位 ──
    //
    // 走 windowInsetsPadding 而不是手取 `WindowInsets.ime.getBottom()`：后者是相对整块屏幕的原始值，
    // 不知道祖先（经典底栏、宿主 Scaffold）已经让过多少；前者会扣掉祖先声明消费过的部分。
    //
    // 悬浮底栏的 bottomBarReserve 已经把系统导航条算进去了，不能再叠 navigationBars，
    // 否则输入框会多让一条导航条（实测多出 24dp）。只有 reserve = 0 的经典 / 侧栏形态
    // 才由这里自己让导航条。
    //
    // union 取各边最大值：键盘弹起时 ime 远大于 reserve，取 ime；键盘收起时取 reserve，
    // 全程同一个表达式，没有收键盘时"先掉到底再跳回来"的跳变。
    val restingInsets = if (bottomReserve > 0.dp) {
        WindowInsets(bottom = bottomReserve)
    } else {
        WindowInsets.navigationBars
    }
    val bottomInsets = WindowInsets.ime.union(restingInsets)

    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(bottomInsets)
            // 悬浮底栏的 reserve 本来就按上限留了空隙，下边再加 8dp 输入框就悬得偏高
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (bottomReserve > 0.dp) 0.dp else 8.dp),
    ) {
        // 待发送的图片：贴在输入框上方，每张右上角一个叉。
        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { path ->
                    Box {
                        AttachmentThumb(path, host)
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                .clickable { onRemoveAttachment(path) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "移除",
                                tint = MiuixTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
        }
        val pillTint = MiuixTheme.colorScheme.surfaceVariant
        // 玻璃底交给 COUI 面板：采样源是聊天列表那层（不含输入框自己），拿不到就纯白/黑。
        CouiGlassPanel(
            pageBackdrop = pageBackdrop,
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            tint = pillTint.copy(alpha = 0.55f),
            // 默认 10 太浅，压在聊天气泡上字都透出来；输入框要能看清自己的字
            blurRadius = 32f,
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            onOpenShared?.let { open ->
                IconButton(onClick = open, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Inbox, "分享材料", modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (visionEnabled) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(
                            enabled = !contextExhausted &&
                                attachments.size < AgentVision.MAX_IMAGES_PER_MESSAGE
                        ) { onPickImage() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "添加图片",
                        tint = if (attachments.size < AgentVision.MAX_IMAGES_PER_MESSAGE) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    // 单行时和右侧 36dp 发送圆对齐；多行时自然往上长。
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !contextExhausted,
                    textStyle = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .onFocusChanged { focused = it.isFocused },
                )
                if (input.isEmpty()) {
                    Text(
                        when {
                            contextExhausted -> "这轮对话满了，新建一个吧"
                            voicePhase == VoicePhase.RECORDING && voiceArmed -> "松手取消"
                            voicePhase == VoicePhase.RECORDING -> "正在听，松手发送，上滑取消"
                            voicePhase == VoicePhase.TRANSCRIBING -> "正在识别…"
                            holdHint -> "按住麦克风说话"
                            else -> placeholder
                        },
                        style = MiuixTheme.textStyles.body1,
                        color = if (voicePhase == VoicePhase.RECORDING && voiceArmed) MiuixTheme.colorScheme.error
                        else if (voicePhase == VoicePhase.RECORDING) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (voicePhase != null) {
                HoldToTalkMic(
                    phase = voicePhase,
                    armed = voiceArmed,
                    enabled = !contextExhausted && voicePhase != VoicePhase.TRANSCRIBING,
                    onTap = { holdHint = true },
                    onStart = {
                        voiceArmed = false
                        onVoiceStart()
                    },
                    onMove = { voiceArmed = it },
                    onEnd = {
                        onVoiceEnd(voiceArmed)
                        voiceArmed = false
                    },
                )
            }
            if (isLoading && canSend) IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Stop, contentDescription = "停止生成", modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .clickable(enabled = actionEnabled) { if (canSend) onSend() else if (isLoading) onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isLoading && !canSend) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isLoading && !canSend) "停止" else if (isLoading) "插话并发送" else "发送",
                    tint = if (actionEnabled) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        }
    }
}
/**
 * 本地图片缩略图。
 *
 * 自己 decode 而不是引第三方图片库：这些文件是 [AgentVision] 压过的，最长边 1300、
 * 一二百 KB，数量上限 4 张，为它们拖进一整个加载框架不划算。
 * `remember(path)` 保证同一张只解一次，重组不会反复读盘。
 */
@Composable
internal fun AttachmentThumb(path: String, host: AgentHost, size: Dp = 62.dp) {
    val bitmap = remember(path) {
        runCatching {
            // 读盘与解码都是平台的事（`:app` = `BitmapFactory.decodeFile`，全尺寸 ARGB_8888）；
            // 读不到（文件被清掉、或这一端根本没有挑图那一档）就画下面那个占位。
            host.readImageBytes(path)?.let { decodeImageFull(it) }
        }.getOrNull()
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = "图片",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 文件被清掉了（清缓存 / 换账号）。给个占位比留个空白格子清楚。
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 推送记录的底栏：只读说明 + 开新对话，底部让位与 [AgentComposer] 一致。 */
@Composable
internal fun PushLogFooter(bottomReserve: Dp, onNewChat: () -> Unit) {
    val resting = if (bottomReserve > 0.dp) WindowInsets(bottom = bottomReserve) else WindowInsets.navigationBars
    // 和输入框同款的胶囊实底（#379）：聊天记录滚到底栏后面时，提示文字不再和内容叠在一起
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(resting)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (bottomReserve > 0.dp) 0.dp else 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "这里只收小岱的推送，不能回复",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "开新对话",
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onNewChat)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * 按住说话的麦克风：长按开始录，松手结束，按着往上滑过 60dp 进入「松手取消」。点一下只提示要按住。
 * 录音时圆钮放大、主色底，滑进取消区变红。
 */
@Composable
private fun HoldToTalkMic(
    phase: VoicePhase,
    armed: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onStart: () -> Unit,
    onMove: (armed: Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val recording = phase == VoicePhase.RECORDING
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val accent = if (armed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    val bg by animateColorAsState(if (recording) accent else androidx.compose.ui.graphics.Color.Transparent, tween(160), label = "micBg")
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (recording) 1.22f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "micScale",
    )
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    Box(
        Modifier
            .padding(end = 6.dp)
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bg)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val cancelPx = 60.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var finished = false
                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        up = waitForUpOrCancellation()
                        finished = true
                    }
                    if (finished) {
                        up?.let { it.consume(); tap() }
                        return@awaitEachGesture
                    }
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    start()
                    var nowArmed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val armedHere = down.position.y - change.position.y > cancelPx
                        if (armedHere != nowArmed) {
                            nowArmed = armedHere
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            move(armedHere)
                        }
                        change.consume()
                    }
                    end()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (phase == VoicePhase.TRANSCRIBING) {
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.Mic,
                contentDescription = "按住说话",
                tint = if (recording) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
