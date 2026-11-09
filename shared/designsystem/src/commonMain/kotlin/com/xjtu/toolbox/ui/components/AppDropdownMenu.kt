package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹出菜单：挂在触发按钮所在的那个 Box 里，从按钮的位置「长出来」——缩放支点取按钮中心，
 * 支点在菜单外面，所以展开时菜单一边放大一边从按钮处滑到自己的位置；收起时原路缩回去。
 *
 * 下方放不下就翻到按钮上方；左右贴着按钮的那一侧对齐（[alignment] 的 Start / End）。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    // 收起动画播完之前弹层还得留着
    var shown by remember { mutableStateOf(false) }
    if (expanded) shown = true
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
        } else if (shown) {
            progress.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
            shown = false
        }
    }
    if (!shown) return

    val density = LocalDensity.current
    val alignEnd = alignment == Alignment.TopEnd || alignment == Alignment.BottomEnd
    val origin = remember { mutableStateOf(TransformOrigin(if (alignEnd) 1f else 0f, 0f)) }
    val provider = remember(alignEnd, offset, density) { AnchorPopupPosition(alignEnd, offset, density, origin) }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val shape = RoundedCornerShape(18.dp)

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier
                .widthIn(min = 180.dp, max = 300.dp)
                .heightIn(max = 400.dp)
                .graphicsLayer {
                    val p = progress.value
                    transformOrigin = origin.value
                    scaleX = 0.2f + 0.8f * p
                    scaleY = scaleX
                    alpha = (p * 1.8f).coerceIn(0f, 1f)
                }
                .shadow(14.dp, shape, ambientColor = MenuShadow, spotColor = MenuShadow)
                .clip(shape)
                .background(surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            content = content,
        )
    }
}

/** 菜单的位置与缩放支点：贴着锚点一侧对齐，支点是锚点中心在菜单坐标里的位置。 */
private class AnchorPopupPosition(
    private val alignEnd: Boolean,
    private val offset: DpOffset,
    private val density: Density,
    private val origin: MutableState<TransformOrigin>,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = with(density) { 8.dp.roundToPx() }
        val gap = with(density) { offset.y.roundToPx() }
        val end = alignEnd == (layoutDirection == LayoutDirection.Ltr)
        val x = (if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) {
            below
        } else {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(margin)
        }
        if (popupContentSize.width > 0 && popupContentSize.height > 0) {
            val center = anchorBounds.center
            origin.value = TransformOrigin(
                (center.x - x).toFloat() / popupContentSize.width,
                (center.y - y).toFloat() / popupContentSize.height,
            )
        }
        return IntOffset(x, y)
    }
}

/**
 * 菜单项：按下时只有这一行（四周留着白边）慢慢压灰，松手再缓缓退掉。
 */
@Composable
fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (pressed) tween(140, easing = LinearOutSlowInEasing) else tween(360, easing = FastOutSlowInEasing),
        label = "menuItemPress",
    )
    val tint = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind { if (press > 0f) drawRect(tint.copy(alpha = 0.08f * press)) }
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            text()
        }
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            trailingIcon()
        }
    }
}

/** 单选菜单项（学期、时间范围这类）：选中的那项主题色加粗、右侧打勾。 */
@Composable
fun AppDropdownOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    AppDropdownMenuItem(
        text = {
            Text(
                text,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            )
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "已选", tint = color, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        onClick = onClick,
    )
}

/** 菜单投影：比卡片阴影更散更淡，浮起来而不发脏。 */
private val MenuShadow = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f)
