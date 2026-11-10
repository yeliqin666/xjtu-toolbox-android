package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 页面级分段选择：一枚白色胶囊（外圈一层很淡的阴影），里面一块灰色滑块标出选中项。
 *
 * 滑块的手感照底栏玻璃 dock：按下就压扁、加深并跟到那一格；横向滑过触摸阈值后贴着手指走，
 * 松手吸到最近一格再切换。没滑过阈值就是点击。
 *
 * 颜色只从卡片色 [AppCardColor] 和正文色推，不用 miuix 的 `surface` / `surfaceContainer`：
 * 那两个角色在默认主题、系统取色、自选主题色下深浅关系各不相同。
 *
 * [embedded]：已经在卡片或抽屉里，外边距收窄。能不能左右滑页由调用方的 pager 决定，这里不管。
 */
@Composable
fun AppSegmentedTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val count = tabs.size
    if (count == 0) return
    val dark = LocalIsDarkTheme.current
    val onGlass = com.xjtu.toolbox.ui.glass.LocalOnGlassBar.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val card = AppCardColor
    val capsule = RoundedCornerShape(percent = 50)
    // 玻璃顶栏上半透明，让玻璃从底下透上来
    val container = if (onGlass) card.copy(alpha = 0.72f) else card
    val restTint = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f)
    val pressTint = if (dark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f)

    val position = remember { Animatable(selectedTabIndex.coerceIn(0, count - 1).toFloat()) }
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestSelected by rememberUpdatedState(selectedTabIndex)
    val latestOnSelected by rememberUpdatedState(onTabSelected)
    val slide = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

    // 外部改了选中项（翻页、程序切换）：滑块跟过去
    LaunchedEffect(selectedTabIndex, count) {
        position.animateTo(selectedTabIndex.coerceIn(0, count - 1).toFloat(), slide)
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (embedded) 4.dp else 16.dp, vertical = if (embedded) 6.dp else 8.dp)
            // 浅色一圈微阴影托出质感；深色阴影看不见，换一圈细描边
            .then(
                if (dark || onGlass) Modifier
                else Modifier.shadow(
                    elevation = 8.dp,
                    shape = capsule,
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.10f),
                ),
            )
            .clip(capsule)
            .background(container)
            .then(if (dark) Modifier.border(0.6.dp, ink.copy(alpha = 0.08f), capsule) else Modifier)
            .padding(4.dp)
            .height(34.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val widthPx = constraints.maxWidth.toFloat()
            val cellPx = widthPx / count
            val cellDp = maxWidth / count
            val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

            // 灰色滑块
            Box(
                Modifier
                    .width(cellDp)
                    .fillMaxHeight()
                    .offset {
                        val x = (position.value * cellPx).roundToInt()
                        IntOffset(if (isLtr) x else (widthPx - cellPx).roundToInt() - x, 0)
                    }
                    .graphicsLayer {
                        val p = press.value
                        scaleY = 1f - 0.08f * p
                        scaleX = 1f + 0.03f * p
                    }
                    .clip(capsule)
                    .background(lerp(restTint, pressTint, press.value)),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .pointerInput(count, cellPx, isLtr) {
                        fun indexAt(x: Float): Float {
                            val local = if (isLtr) x else size.width - x
                            return (local / cellPx - 0.5f).coerceIn(0f, (count - 1).toFloat())
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downIndex = indexAt(down.position.x).roundToInt()
                            val slop = viewConfiguration.touchSlop
                            var dragging = false
                            scope.launch { press.animateTo(1f, tween(120)) }
                            scope.launch { position.animateTo(downIndex.toFloat(), slide) }
                            var released = false
                            var lastX = down.position.x
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    lastX = change.position.x
                                    if (dragging) change.consume()
                                    break
                                }
                                if (!dragging) {
                                    val dx = abs(change.position.x - down.position.x)
                                    val dy = abs(change.position.y - down.position.y)
                                    // 先竖着滑出阈值 = 在滚页面，这一下不算点也不算拖
                                    if (dy > slop && dy > dx) break
                                    if (dx > slop) dragging = true
                                }
                                if (dragging) {
                                    lastX = change.position.x
                                    scope.launch { position.snapTo(indexAt(lastX)) }
                                    change.consume()
                                }
                            }
                            scope.launch { press.animateTo(0f, tween(220)) }
                            val target = when {
                                !released -> latestSelected.coerceIn(0, count - 1)
                                dragging -> indexAt(lastX).roundToInt()
                                else -> downIndex
                            }
                            scope.launch { position.animateTo(target.toFloat(), slide) }
                            if (released && target != latestSelected) latestOnSelected(target)
                        }
                    },
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = index == selectedTabIndex
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                                onClick { latestOnSelected(index); true }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontSize = if (count >= 5) 13.sp else 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) ink else muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
