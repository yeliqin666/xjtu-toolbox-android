package com.xjtu.toolbox.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 首页常用功能：一行 4 个、最多 [MAX_QUICK] 个。可编辑时长按或点「编辑」进入编辑：
 * 拖动排序、点角上的 × 移除、末尾的 + 添加。每次改动都交给 [onChange] 落盘。
 */
@Composable
internal fun QuickActionsGrid(
    pinned: List<HomeServiceRow>,
    all: List<HomeServiceRow>,
    editable: Boolean,
    onChange: (List<String>) -> Unit,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    /** 卡片是深色液体底时传白色，标题 / 「编辑」 / 功能名都跟着提亮；null 用主题色。 */
    contentColor: Color? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val order = remember { mutableStateListOf<String>() }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val commit by rememberUpdatedState { onChange(order.toList()) }
    val byKey = all.associateBy { it.key }
    val keys = if (editing) order.toList() else pinned.map { it.key }
    val shown = keys.mapNotNull { byKey[it] }
    fun startEditing() {
        order.clear()
        order.addAll(pinned.map { it.key })
        editing = true
    }

    Column(modifier.fillMaxWidth()) {
        if (editable) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (editing) "拖动排序，点 × 移除" else "常用功能",
                    style = MiuixTheme.textStyles.footnote1,
                    color = contentColor?.copy(alpha = 0.78f) ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (editing) "完成" else "编辑",
                    style = MiuixTheme.textStyles.footnote1,
                    color = contentColor ?: MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { if (editing) editing = false else startEditing() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        QuickGridLayout {
            shown.forEach { row ->
                key(row.key) {
                    val dragging = dragKey == row.key
                    Box(
                        Modifier
                            .onGloballyPositioned { bounds[row.key] = it.boundsInRoot() }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                val home = bounds[row.key]
                                if (dragging && home != null) {
                                    translationX = finger.x - home.center.x
                                    translationY = finger.y - home.center.y
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                }
                            }
                            .then(if (!editing) Modifier else Modifier.pointerInput(row.key) {
                                detectDragGestures(
                                    onDragStart = { bounds[row.key]?.let { finger = it.center }; dragKey = row.key },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        finger += delta
                                        val target = order.indexOfFirst { k -> k != row.key && bounds[k]?.contains(finger) == true }
                                        if (target >= 0) order.add(target, order.removeAt(order.indexOf(row.key)))
                                    },
                                    onDragEnd = { dragKey = null; commit() },
                                    onDragCancel = { dragKey = null; commit() },
                                )
                            }),
                    ) {
                        HomeQuickAction(
                            row.icon, row.title, row.color,
                            onClick = { if (!editing) row.onClick() },
                            onLongClick = if (editable && !editing) ::startEditing else null,
                            originKey = row.key.takeUnless { editing },
                            iconSize = iconSize,
                            labelColor = contentColor,
                        )
                        if (editing) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 6.dp).size(20.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    .clickable { order.remove(row.key); commit() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "移除${row.title}",
                                    tint = MiuixTheme.colorScheme.surface, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
            if (editable && (editing || shown.isEmpty()) && shown.size < MAX_QUICK) {
                HomeQuickAction(
                    Icons.Default.Add, "添加", contentColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    onClick = { picking = true },
                    iconSize = iconSize,
                    labelColor = contentColor,
                )
            }
        }
    }

    if (picking) {
        val candidates = all.filter { it.key !in keys }
        WindowDialog(show = true, title = "添加常用功能", onDismissRequest = { picking = false }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (editing) order.add(row.key)
                                onChange(keys + row.key)
                                picking = false
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExpressiveIcon(icon = row.icon, color = row.color, size = 34.dp, iconSize = 18.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(row.title, style = MiuixTheme.textStyles.body1)
                    }
                }
            }
        }
    }
}

/** 等宽 4 列：各格是同一层的兄弟节点，拖动中的那格才能用 zIndex 盖住别的行。 */
@Composable
private fun QuickGridLayout(content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val cell = constraints.maxWidth / QUICK_COLUMNS
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(cell)) }
        val lines = placeables.chunked(QUICK_COLUMNS)
        val heights = lines.map { line -> line.maxOf { it.height } }
        layout(constraints.maxWidth, heights.sum()) {
            var y = 0
            lines.forEachIndexed { i, line ->
                line.forEachIndexed { j, p -> p.place(j * cell, y) }
                y += heights[i]
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeQuickAction(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    iconSize: Dp,
    onLongClick: (() -> Unit)? = null,
    originKey: String? = null,
    labelColor: Color? = null,
) {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    val iconRadius = iconSize * 0.31f
    val snapshot: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.Center) {
            ExpressiveIcon(icon = icon, color = color, size = iconSize, iconSize = iconSize * 0.5f)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onLongClick = onLongClick,
                onClick = {
                    originKey?.let { origin.arm(it, iconRadius, density, snapshot) }
                    onClick()
                },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        // 转场从图标本身长出来：坐标取图标方块，不含下面的名称。
        Box(if (originKey != null) Modifier.expandOriginSource(origin) else Modifier) {
            ExpressiveIcon(icon = icon, color = color, size = iconSize, iconSize = iconSize * 0.5f)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, maxLines = 1,
            color = labelColor ?: MiuixTheme.colorScheme.onSurface)
    }
}

private const val QUICK_COLUMNS = 4
private const val MAX_QUICK = 8
