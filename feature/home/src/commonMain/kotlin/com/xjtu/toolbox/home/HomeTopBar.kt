package com.xjtu.toolbox.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.xjtu.toolbox.ui.components.JiaoxiaoxiAvatar
import com.xjtu.toolbox.ui.glass.CouiHomeTopBarSurface
import com.xjtu.toolbox.ui.glass.CouiPageBackdrop
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页的折叠顶栏：左侧「交小西」头像（随上滑淡入放大），功能圆钮全在右上角；
 * 编辑态左边是「编辑主页」，右边齿轮 + 对勾。
 *
 * **经典分类卡与卡片组共用这一份**，切换「功能区」时顶栏不该跟着变；背景从改造前的
 * `progressiveBlurBar` 换成了 COUI 的渐变模糊（[CouiHomeTopBarSurface]），三颗宿主按钮
 * 走 COUI `TopAppBarButton`（采样同一层表面）。首页这条自定义版式（头像 + 上滑出现的小图标）
 * 暂时不换成 COUI AppBar 组件，只换模糊。
 */
@Composable
internal fun HomeTopBar(
    /** 折叠进度 0（停在顶部）→ 1（刊头完全滚走）。 */
    progress: () -> Float,
    /** 状态栏高度：顶栏内容从它下面开始。 */
    topInset: Dp,
    /** 本页正文采样源（背景 + 滚动内容），交给 COUI 渐变模糊；null = 没有可糊的东西，实色底。 */
    pageBackdrop: CouiPageBackdrop?,
    onTapCollapsed: () -> Unit,
    /** 右上角那一排宿主按钮（`:app` 是扫码、搜索、通知）。null = 这一端没有这排按钮。 */
    actions: (@Composable () -> Unit)?,
    /** 编辑态：左侧换成「编辑主页」，右侧换成齿轮 + 对勾。 */
    editing: Boolean = false,
    onHomeSettings: () -> Unit = {},
    onSaveEdit: () -> Unit = {},
    alignWithGreeting: Boolean = false,
) {
    // 经 rememberUpdatedState 读最新的 progress：刊头量完高度后折叠区间会变，只记第一份会判早。
    // 折叠判据放 derivedStateOf：滚动时每帧都在变，直接读会让整条顶栏跟着重组。
    val latestProgress by rememberUpdatedState(progress)
    val collapsed by remember { derivedStateOf { latestProgress() > 0.5f } }
    CouiHomeTopBarSurface(
        pageBackdrop = pageBackdrop,
        gradientColor = MiuixTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(topInset + BAR_HEIGHT)) {
            // 折叠后点顶栏空白回到顶部；展开时不拦截（内容本来就在上面）
            Box(
                Modifier
                    .matchParentSize()
                    .then(
                        if (collapsed && !editing) {
                            Modifier.clickable(interactionSource = null, indication = null, onClick = onTapCollapsed)
                        } else {
                            Modifier
                        },
                    ),
            )

            // 编辑态的标题靠左，让人一眼知道现在在摆桌面
            Box(
                Modifier
                    .matchParentSize()
                    .padding(top = topInset, start = 20.dp)
                    .height(BAR_HEIGHT),
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedContent(editing, transitionSpec = { BarSwap }, label = "barTitle") { edit ->
                    if (edit) {
                        Text(
                            "编辑主页",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = topInset)
                    .height(BAR_HEIGHT)
                    .padding(start = 20.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val lift = Modifier.graphicsLayer {
                    translationY = if (alignWithGreeting) 0f else -BUTTON_LIFT.toPx() * (1f - progress())
                }
                Box(lift) {
                    AnimatedContent(editing, transitionSpec = { BarSwap }, label = "barLeading") { edit ->
                        if (!edit) HomeBarAvatar(progress)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(lift) {
                    AnimatedContent(
                        editing,
                        transitionSpec = { BarSwap },
                        contentAlignment = Alignment.CenterEnd,
                        label = "barActions",
                    ) { edit ->
                        if (edit) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CouiToolbarActionButton(onClick = onHomeSettings) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "主页设置",
                                        tint = MiuixTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                CouiToolbarActionButton(onClick = onSaveEdit) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "保存布局",
                                        tint = MiuixTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            actions?.invoke()
                        }
                    }
                }
            }
        }
    }
}

/** 顶栏左侧的「交小西」头像：随上滑淡入、由 0.78 放大到 1，停在顶部时看不见。 */
@Composable
private fun HomeBarAvatar(progress: () -> Float) {
    JiaoxiaoxiAvatar(
        Modifier.size(28.dp).graphicsLayer {
            val f = progress()
            alpha = f
            scaleX = 0.78f + 0.22f * f
            scaleY = scaleX
        },
    )
}

/** 顶栏在常规 / 编辑两套按钮之间切换：旧的缩小淡出，新的放大淡入。 */
private val BarSwap = ContentTransform(
    targetContentEnter = fadeIn(tween(200, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.8f),
    initialContentExit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
    sizeTransform = SizeTransform(clip = false),
)

/** 首页折叠顶栏（状态栏以下）的高度，几颗圆钮在里面居中。 */
internal val BAR_HEIGHT = 52.dp

/** 展开时圆钮比顶栏正中上移的距离：顶栏里圆钮上方正好留这么多，不会顶进状态栏。 */
private val BUTTON_LIFT get() = (BAR_HEIGHT - 36.dp) / 2 - 2.dp
