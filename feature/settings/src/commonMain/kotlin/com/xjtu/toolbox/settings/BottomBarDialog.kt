package com.xjtu.toolbox.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.BottomBarTabs
import com.xjtu.toolbox.nav.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val ROW_HEIGHT = 48.dp

/**
 * 编辑底栏中间那几格：从首页一级功能（[candidates]）里勾选，已选的排在最上面、按底栏从左到右，
 * 按住右侧把手或长按整行拖动排序。首页和我的固定在两端。[icon] 与首页宫格同一套，null 时不画。
 */
@Composable
internal fun BottomBarDialog(
    candidates: List<AppService>,
    initial: List<AppRoute>,
    icon: ((AppRoute) -> ImageVector)?,
    onDismiss: () -> Unit,
    onConfirm: (List<AppRoute>) -> Unit,
) {
    val chosen = remember { mutableStateListOf(*initial.toTypedArray()) }
    val rows = chosen + candidates.map { it.route }.filterNot { it in chosen }
    var dragging by remember { mutableStateOf<AppRoute?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    val haptic = LocalHapticFeedback.current

    fun startDrag(route: AppRoute) {
        dragging = route
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // 拖过半行就和邻格换位，偏移扣掉一行，被拖的格子始终跟在手指下
    fun drag(route: AppRoute, dy: Float) {
        dragY += dy
        val i = chosen.indexOf(route)
        val step = when {
            dragY > rowPx / 2 && i < chosen.lastIndex -> 1
            dragY < -rowPx / 2 && i > 0 -> -1
            else -> return
        }
        chosen.add(i + step, chosen.removeAt(i))
        dragY -= step * rowPx
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun endDrag() {
        dragging = null
        dragY = 0f
    }

    WindowDialog(
        show = true,
        title = "底栏",
        summary = "首页、我的固定在两端，中间选 ${BottomBarTabs.MIN_MIDDLE}–${BottomBarTabs.MAX_MIDDLE} 个，拖动排序",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                (listOf("首页") + chosen.map(BottomBarTabs::label) + "我的").joinToString(" · "),
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // 列表单独滚；weight 让平板横屏弹窗被限高时先压列表，按钮不被裁掉
            Column(Modifier.weight(1f, fill = false).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { route ->
                    // 按路由定身份：换位时这一行连同正在进行的拖动手势一起挪过去
                    key(route) {
                        val checked = route in chosen
                        val lifted = route == dragging
                        val toggleable =
                            if (checked) chosen.size > BottomBarTabs.MIN_MIDDLE else chosen.size < BottomBarTabs.MAX_MIDDLE
                        val toggle = {
                            if (toggleable) {
                                if (checked) chosen.remove(route) else chosen.add(route)
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT)
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer { if (lifted) translationY = dragY }
                                .background(
                                    if (lifted) MiuixTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .then(
                                    if (checked) {
                                        Modifier.pointerInput(route) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { startDrag(route) },
                                                onDrag = { change, delta -> change.consume(); drag(route, delta.y) },
                                                onDragEnd = { endDrag() },
                                                onDragCancel = { endDrag() },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(onClick = toggle)
                                .alpha(if (checked || toggleable) 1f else 0.4f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = toggle)
                            Spacer(Modifier.width(12.dp))
                            icon?.let {
                                Icon(it(route), contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                BottomBarTabs.label(route),
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (checked) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "拖动排序",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .size(ROW_HEIGHT)
                                        .pointerInput(route) {
                                            detectDragGestures(
                                                onDragStart = { startDrag(route) },
                                                onDrag = { change, delta -> change.consume(); drag(route, delta.y) },
                                                onDragEnd = { endDrag() },
                                                onDragCancel = { endDrag() },
                                            )
                                        }
                                        .padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "保存",
                    onClick = { onConfirm(chosen.toList()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
