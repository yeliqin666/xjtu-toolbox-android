package com.xjtu.toolbox.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.BackHandler
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 非首页点小岱球时弹出的「大半屏」小岱（#557）：从底部滑上来盖住大半屏，上面一截还看得见原来的页面。
 * 顶上的把手往下拖、点上方遮罩、按返回都能收起；拖得不够远就弹回去。
 */
@Composable
internal fun PidaiHoverPanel(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // 打开时才注册：返回键按「后注册先处理」，一直挂着的话排在导航栈后面，返回会被底下的页面抢走
    if (visible) BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { Animatable(0f) }
    LaunchedEffect(visible) { if (visible) drag.snapTo(0f) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it },
            exit = slideOutVertically(tween(240)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val dismissPx = with(density) { 140.dp.toPx() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    // 面板顶端离状态栏很远：里面的页面不必再给状态栏留白
                    .consumeWindowInsets(WindowInsets.statusBars),
            ) {
                // 把手：往下拖收起，速度够快或拖过 140dp 就收，否则弹回
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                            onDragStopped = { velocity ->
                                if (drag.value > dismissPx || velocity > 1800f) {
                                    onDismiss()
                                } else {
                                    drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
    }
}
