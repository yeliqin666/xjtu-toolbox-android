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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /** 模型认不认图片。不认就不给入口——贴了也只会被服务端拒掉。 */
    visionEnabled: Boolean = false,
    onPickImage: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
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
    // 手算 inset 是错的路，之前两版都栽在这儿：先是 navigationBarsPadding + imePadding +
    // reserve 三段相加把输入框顶到屏幕中间；改成手动取最大值后，又因为没扣掉宿主 Scaffold
    // 已经为底栏让出的那段而多空 90dp；手动减一下，数又对不上。
    //
    // 根因是 `WindowInsets.ime.getBottom()` 拿的是**相对整块屏幕**的原始值，
    // 完全不知道祖先已经让过多少。正确做法是走 `windowInsetsPadding`，它会自动扣除
    // 已被消费的部分——前提是有人把"我已经让过了"声明出来。宿主只做了 padding(padding)
    // 没做 consumeWindowInsets，所以这里在屁岱自己的子树里补一次声明（见 body 的调用处），
    // 消费链建立后下面这一行就成立了。作用域只在屁岱内，不影响其它 tab。
    //
    // union 取各边最大值、add 取和，于是：
    // - 键盘弹起：ime 远大于 nav+reserve，取 ime（reserve 此时被键盘盖住，本就不该再让）
    // - 键盘收起：取 nav + 悬浮胶囊底栏的预留高度
    // 全程由同一个表达式给出，没有分支，也就没有收键盘时"先掉到底再跳回来"的跳变。
    val bottomInsets = WindowInsets.ime.union(
        WindowInsets.navigationBars.add(WindowInsets(bottom = bottomReserve))
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(bottomInsets)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        AttachmentThumb(path)
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
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
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
                        if (contextExhausted) "这轮对话满了，新建一个吧" else placeholder,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .clickable(enabled = actionEnabled) { if (isLoading) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isLoading) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isLoading) "停止" else "发送",
                    tint = if (actionEnabled) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
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
internal fun AttachmentThumb(path: String, size: Dp = 62.dp) {
    val bitmap = remember(path) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
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
