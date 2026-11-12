package com.xjtu.toolbox.ui.glass.coui.menu

import android.animation.ArgbEvaluator
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import com.xjtu.toolbox.ui.glass.coui.CouiIcon as Icon
import com.xjtu.toolbox.ui.glass.coui.CouiText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.xjtu.toolbox.ui.glass.coui.Checkbox
import com.xjtu.toolbox.ui.glass.coui.CouiStateProgress
import com.xjtu.toolbox.ui.glass.coui.SpotlightDiagnostic
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled
import com.xjtu.toolbox.ui.glass.coui.CouiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.couiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.rememberCouiTouchMotion

internal val CouiMenuItemHeight = 40.dp
internal val CouiMenuMinWidth = 128.dp
internal val CouiMenuMaxWidth = 280.dp
private val CouiMenuItemStateHorizontalInset = 8.dp
private val CouiMenuItemStateVerticalInset = 1.5.dp
private val CouiMenuItemContentHorizontalPadding = 12.dp
private val CouiMenuItemContentVerticalPadding = 8.dp
private val CouiMenuItemIconSize = 20.dp
private val CouiMenuItemIconSpacing = 12.dp
internal val CouiMenuContentVerticalPadding = 8.dp
private val CouiMenuHeaderTopPadding = 4.dp
private val CouiMenuDividerThickness = 1.dp
private val CouiMenuDividerGap = 5.dp
private val CouiMenuDividerHeight = (CouiMenuDividerGap - CouiMenuItemStateVerticalInset) * 2f + CouiMenuDividerThickness
private const val CouiMenuSelectedContainerAlpha = 0.12f

/**
 * COUI 菜单项数据。
 *
 * @param value 菜单项提交给业务层的值。
 * @param text 菜单项显示文本。
 * @param icon 菜单项显示图标。
 * @param selected 是否展示为当前选中项。
 * @param enabled 是否允许点击或滑动提交。
 * @param checked 独立开关的勾选状态; null 表示普通菜单项, 与 selected 的选中样式独立。
 * @param iconTint 图标自定义颜色; null 时沿用菜单项文字颜色。
 * @param dividerBefore 手动在菜单项前添加分隔区域, 该区域不参与选中或提交。
 * @param children 二级菜单项; 非空时本项只负责展开, 不提交业务值。分隔线由子项的 dividerBefore 指定。
 */
data class CouiMenuItem<T>(
    val value: T,
    val text: String,
    val icon: ImageVector? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val dividerBefore: Boolean = false,
    val iconTint: Color? = null,
    val children: List<CouiMenuItem<T>> = emptyList(),
)

/**
 * COUI 菜单内容。
 *
 * @param expanded 菜单是否展开。
 * @param onDismissRequest 菜单请求关闭时的回调。
 * @param items 菜单项列表。
 * @param onItemClick 菜单项被点击或滑动松手提交时的回调。
 * @param modifier 菜单容器的修饰符。
 * @param offset 菜单相对锚点的偏移量。
 * @param properties 透传给底层 Popup 的弹窗属性。
 * @param anchor 设置行提供的触点锚点; 不提供时沿用父布局定位。
 * @param headerText 顶部提示文本, 不参与选择或提交; null 或空字符串时不显示。
 * @param headerTextAlign 顶部提示文本的对齐方式。
 * @param themeTintEnabled 是否为采样材质叠加 Material 页面背景色的色相, 默认开启。
 */
@Composable
private fun <T> CouiMenuContent(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<CouiMenuItem<T>>,
    onItemClick: (CouiMenuItem<T>) -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    properties: PopupProperties = PopupProperties(focusable = true),
    anchor: CouiMenuAnchor? = null,
    headerText: String? = null,
    headerTextAlign: TextAlign = TextAlign.Center,
    themeTintEnabled: Boolean = true,
) {
    require(items.all { item -> item.children.all { it.children.isEmpty() } }) {
        "Menu 只支持两级菜单"
    }
    if (items.any { it.children.isNotEmpty() }) {
        CouiMenuWithSubMenu(
            expanded, onDismissRequest, items, onItemClick, modifier, offset, properties,
            anchor, headerText, headerTextAlign, themeTintEnabled,
        )
        return
    }
    val selection = remember { CouiMenuSelection() }
    val motion = rememberCouiMenuMotion(expanded)
    val touchMotion = rememberCouiTouchMotion(expanded)
    val menuShape = LocalCouiMenuStyle.current.shape
    val menuBackdrop = LocalCouiMenuBackdrop.current
    val material = if (menuBackdrop != null && (expanded || motion.visible)) {
        couiMenuMaterial(menuBackdrop, menuShape, expanded, motion, touchMotion, themeTintEnabled)
    } else {
        CouiMenuMaterial()
    }

    fun dismissMenu() {
        if (!expanded) return
        selection.pressedIndex.value = null
        onDismissRequest()
    }

    CouiMenuPopup(
        expanded = expanded,
        motion = motion,
        touchMotion = touchMotion,
        anchor = anchor,
        onDismissRequest = ::dismissMenu,
        offset = offset,
        shape = menuShape,
        containerColor = if (menuBackdrop != null) Color.Transparent else LocalCouiMenuStyle.current.colors.container,
        modifier = modifier.then(material.drawing).then(material.input)
            .widthIn(min = CouiMenuMinWidth, max = CouiMenuMaxWidth),
        properties = properties,
    ) { scrollState ->
        CouiMenuList(items = items, expanded = expanded, onItemClick = onItemClick,
            selection = selection, scrollState = scrollState, headerText = headerText, headerTextAlign = headerTextAlign)
    }
}

internal class CouiMenuSelection {
    val pressedIndex = mutableStateOf<Int?>(null)
    val pressedIndicatorIndex = mutableStateOf<Int?>(null)
    val pressedIndicatorSession = mutableIntStateOf(0)
    var pressedIndicatorProgress by mutableStateOf(CouiStateProgress())
    var pressHandoff by mutableStateOf(false)

    fun restartIndicator() {
        pressedIndicatorProgress.dispose()
        pressedIndicatorProgress = CouiStateProgress()
        pressHandoff = false
        pressedIndicatorSession.intValue++
    }

    fun handoffPress(progress: Float) {
        pressedIndicatorProgress.dispose()
        pressedIndex.value = null
        pressedIndicatorIndex.value = 0
        pressedIndicatorProgress = CouiStateProgress(progress)
        pressHandoff = true
        pressedIndicatorSession.intValue++
    }

    fun clear() {
        pressedIndicatorProgress.dispose()
        pressedIndex.value = null
        pressedIndicatorIndex.value = null
    }
}

@Composable
internal fun <T> CouiMenuList(
    modifier: Modifier = Modifier,
    items: List<CouiMenuItem<T>>,
    expanded: Boolean,
    onItemClick: (CouiMenuItem<T>) -> Unit,
    selection: CouiMenuSelection,
    scrollState: ScrollState,
    headerText: String? = null,
    headerTextAlign: TextAlign = TextAlign.Center,
    panel: CouiMenuPanel<T>? = null,
    activeSubMenuIndex: Int? = null,
    onSubMenuHeaderClick: (() -> Unit)? = null,
) {
    var pressedIndex by selection.pressedIndex
    var pressedIndicatorIndex by selection.pressedIndicatorIndex
    val pressedIndicatorSession by selection.pressedIndicatorSession
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    val hapticFeedback = LocalHapticFeedback.current
    var measuredHeaderHeightPx by remember { mutableStateOf(0) }
    val headerHeightPx = if (headerText.isNullOrEmpty()) 0 else measuredHeaderHeightPx
    val density = LocalDensity.current
    val itemHeightPx = with(density) { CouiMenuItemHeight.roundToPx() }
    val dividerHeightPx = with(density) { CouiMenuDividerHeight.roundToPx() }
    val contentPaddingPx = with(density) { CouiMenuContentVerticalPadding.roundToPx() }
    var nextItemTopPx = 0
    val itemTopsPx = items.map { item ->
        if (item.dividerBefore) nextItemTopPx += dividerHeightPx
        val top = nextItemTopPx
        nextItemTopPx += itemHeightPx
        top
    }
    panel?.items = items
    panel?.itemTops = itemTopsPx
    panel?.headerHeight = headerHeightPx
    panel?.itemHeight = itemHeightPx
    Column(
        modifier = modifier.then(if (expanded && panel == null) {
            Modifier.pointerInput(expanded, items, itemHeightPx, dividerHeightPx, itemTopsPx, headerHeightPx, contentPaddingPx, hapticFeedback, accessibilityManager) {
                fun itemIndexAtY(positionY: Float): Int? {
                    return itemTopsPx.indexOfFirst { top ->
                        positionY >= top && positionY < top + itemHeightPx
                    }.takeIf { it >= 0 }
                }

                fun enabledItemIndexAt(position: Offset): Int? {
                    if (position.x < 0f || position.x >= size.width) return null
                    return itemIndexAtY(position.y - contentPaddingPx - headerHeightPx)?.takeIf { items[it].enabled }
                }

                fun isNonSelectableContentAt(position: Offset): Boolean {
                    if (position.x < 0f || position.x >= size.width) return false
                    val contentY = position.y - contentPaddingPx
                    if (contentY < 0f) return false
                    if (contentY < headerHeightPx) return true
                    val itemY = contentY - headerHeightPx
                    return items.indices.any { index ->
                        items[index].dividerBefore &&
                            itemY >= itemTopsPx[index] - dividerHeightPx && itemY < itemTopsPx[index]
                    }
                }

                try {
                    awaitPointerEventScope {
                        while (true) {
                            // 对应 COUI dispatchTouchEvent 在 super 前记录 DOWN, 外层滚动条仍优先消费。
                            val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                            if (downEvent.changes.count { it.pressed } != 1) continue
                            val down = downEvent.changes
                                .firstOrNull { it.pressed && !it.previousPressed && !it.isConsumed }
                                ?: continue
                            if (scrollState.isScrollInProgress ||
                                accessibilityManager?.isTouchExplorationEnabled == true
                            ) continue
                            val canSlideSelect = scrollState.maxValue == 0
                            var pendingIndex = enabledItemIndexAt(down.position)
                            val initialIndex = pendingIndex
                            var movedBetweenItems = false
                            if (pendingIndex != null) selection.restartIndicator()
                            pressedIndex = pendingIndex
                            pendingIndex?.let { pressedIndicatorIndex = it }
                            var pointerPressed = true
                            while (pointerPressed) {
                                val event = awaitPointerEvent()
                                if (event.isCouiTouchCancelled() || event.changes.count { it.pressed } > 1 ||
                                    accessibilityManager?.isTouchExplorationEnabled == true
                                ) {
                                    event.changes.forEach { it.consume() }
                                    pendingIndex = null
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    if (change == null) pendingIndex = null
                                    pointerPressed = false
                                } else if (!canSlideSelect) {
                                    // 厂商可滚动菜单超过 touch slop 后交给滚动, 不切换滑选项。
                                    if (scrollState.isScrollInProgress ||
                                        kotlin.math.abs(change.position.y - down.position.y) > viewConfiguration.touchSlop
                                    ) {
                                        pendingIndex = null
                                        break
                                    }
                                } else {
                                    val nextIndex = enabledItemIndexAt(change.position)
                                    if (isNonSelectableContentAt(change.position)) {
                                        // COUI 的提示与分隔区域不切换目标, 也不让共享遮罩退场。
                                        if (pendingIndex != null) movedBetweenItems = true
                                    } else if (nextIndex == null) {
                                        movedBetweenItems = true
                                        pendingIndex = null
                                        pressedIndex = null
                                    } else if (nextIndex != pendingIndex) {
                                        if (pendingIndex == null) selection.restartIndicator()
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        pendingIndex = nextIndex
                                        pressedIndex = nextIndex
                                        pressedIndicatorIndex = nextIndex
                                        if (nextIndex != initialIndex) movedBetweenItems = true
                                    }
                                    // 对应厂商 cancelLastItemSelect, 防止原项点击与滑选各提交一次。
                                    if (movedBetweenItems || pendingIndex == null) change.consume()
                                }
                            }
                            if (movedBetweenItems) {
                                pendingIndex?.let { index -> currentOnItemClick(items[index]) }
                            }
                            pressedIndex = null
                        }
                    }
                } finally {
                    // 列表更新或修饰符移除也会取消协程, 此时不能保留旧项遮罩。
                    pressedIndex = null
                    pressedIndicatorIndex = null
                }
            }
        } else {
            Modifier
        })
            // 留白须在 DOWN 命中范围内; 内容坐标仍以留白内侧为原点, 并随列表滚动。
            .padding(vertical = CouiMenuContentVerticalPadding)
            .onGloballyPositioned { panel?.contentCoordinates = it },
    ) {
        if (!headerText.isNullOrEmpty()) {
            CouiMenuHeader(
                modifier = Modifier.onSizeChanged { measuredHeaderHeightPx = it.height },
                text = headerText,
                textAlign = headerTextAlign,
            )
        }
        Box {
            key(pressedIndicatorSession) {
                CouiMenuPressedIndicator(
                    itemTop = pressedIndicatorIndex?.let { index ->
                        itemTopsPx.getOrNull(index)?.let { with(density) { it.toDp() } }
                    },
                    visible = pressedIndex != null,
                    progress = selection.pressedIndicatorProgress,
                    pressHandoff = selection.pressHandoff,
                    onFadeOutFinished = {
                        if (pressedIndex == null) pressedIndicatorIndex = null
                    }
                )
            }
            Column {
                items.forEachIndexed { index, item ->
                    if (item.dividerBefore) CouiMenuDivider()
                    val headerClick = if (index == 0) onSubMenuHeaderClick else null
                    CouiMenuRow(
                        item = item,
                        subMenuExpanded = index == activeSubMenuIndex,
                        sharedMaskVisible = pressedIndicatorIndex != null,
                        onClick = if (!item.enabled || (!expanded && headerClick == null)) null else {
                            {
                                pressedIndex = null
                                if (headerClick != null) headerClick() else onItemClick(item)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CouiMenuHeader(modifier: Modifier = Modifier, text: String, textAlign: TextAlign) {
    val dark = LocalCouiMenuStyle.current.isDark
    val horizontalPadding = CouiMenuItemStateHorizontalInset + CouiMenuItemContentHorizontalPadding
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                top = CouiMenuHeaderTopPadding,
                end = horizontalPadding,
                bottom = CouiMenuItemContentVerticalPadding,
            ),
        style = LocalCouiMenuStyle.current.headerTextStyle,
        color = Color(if (dark) 0x8AFFFFFF else 0x8A000000),
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 与 COUI 一致, 窗口容纳整个可用区域, 菜单在窗口内独立定位和变换。 */
@Composable
internal fun CouiMenuPopup(
    expanded: Boolean,
    motion: CouiMenuMotion,
    touchMotion: CouiTouchMotion,
    anchor: CouiMenuAnchor?,
    onDismissRequest: () -> Unit,
    offset: DpOffset,
    shape: Shape,
    containerColor: Color,
    modifier: Modifier,
    properties: PopupProperties,
    rootModifier: Modifier = Modifier,
    touchInputOnRoot: Boolean = false,
    mainTransition: Modifier = Modifier,
    transitionProgress: (() -> Float)? = null,
    onPositioned: (CouiMenuMotionPositionProvider) -> Unit = {},
    overlay: @Composable BoxScope.(CouiMenuMotionPositionProvider) -> Unit = {},
    onOutsideRequest: (Offset) -> Unit = { onDismissRequest() },
    scrollState: ScrollState = rememberScrollState(),
    scrollBar: CouiMenuScrollBar = remember(scrollState) { CouiMenuScrollBar(scrollState) },
    scrollEnabled: Boolean = true,
    content: @Composable ColumnScope.(ScrollState) -> Unit,
) {
    LaunchedEffect(expanded, scrollState) {
        if (expanded) {
            scrollState.stopScroll(MutatePriority.PreventUserInput)
            scrollState.scrollTo(0)
        }
    }
    if (!expanded && !motion.visible) return
    val shadow = rememberCouiMenuShadow(shape, motion, touchMotion, transitionProgress)

    val density = LocalDensity.current
    val materialPositionProvider = remember(offset, density) { CouiMenuPositionProvider(offset, density) }
    val touchOffsetY = with(LocalDensity.current) { offset.y.roundToPx() }
    val positionProvider = remember(materialPositionProvider, motion, anchor, touchOffsetY) {
        CouiMenuMotionPositionProvider(
            materialPositionProvider, motion,
            anchorPosition = { anchor?.positionInWindow },
            visibleWindowBounds = { anchor?.visibleWindowBounds },
            touchOffsetY = touchOffsetY,
        )
    }
    val currentOnOutsideRequest by rememberUpdatedState(onOutsideRequest)
    val hostView = LocalView.current
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties.withMenuInput(expanded),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val hostOnScreen = IntArray(2)
                val hostInWindow = IntArray(2)
                hostView.getLocationOnScreen(hostOnScreen)
                hostView.getLocationInWindow(hostInWindow)
                // 系统会移动全窗口 Popup 以避让 Insets, 不能把请求的 (0, 0) 当作实际原点。
                positionProvider.popupOriginInHostWindow = coordinates.positionOnScreen().round() - IntOffset(
                    hostOnScreen[0] - hostInWindow[0],
                    hostOnScreen[1] - hostInWindow[1],
                )
                onPositioned(positionProvider)
            }.then(rootModifier),
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            // 背景是菜单的兄弟节点, 仅菜单外的按下命中它, 不截获菜单拖动。
            Spacer(
                Modifier.matchParentSize().pointerInput(expanded, properties.dismissOnClickOutside) {
                    if (!expanded || !properties.dismissOnClickOutside) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.firstOrNull { it.pressed && !it.previousPressed }
                                ?.let { currentOnOutsideRequest(it.position) }
                        }
                    }
                },
            )
            Box(
                Modifier
                    .absoluteOffset { positionProvider.menuPosition }
                    .onSizeChanged { positionProvider.menuSize = it }
                    .then(shadow.drawing),
            ) {
                Column(
                    modifier = Modifier
                        .graphicsLayer {
                            val frame = motion.frame
                            scaleX = frame.scale * touchMotion.scaleX.value
                            scaleY = frame.scale * touchMotion.scaleY.value
                            translationX = frame.translation.x + touchMotion.translationX.value
                            translationY = frame.translation.y + touchMotion.translationY.value
                            alpha = frame.alpha
                            transformOrigin = TransformOrigin.Center
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .then(mainTransition)
                        .then(shadow.surface)
                        .then(if (!touchInputOnRoot) Modifier.couiTouchMotion(touchMotion, expanded) else Modifier)
                        .then(if (properties.excludeFromSystemGesture) Modifier.systemGestureExclusion() else Modifier)
                        .background(containerColor, shape)
                        .clip(shape)
                        .then(modifier)
                        .width(IntrinsicSize.Max)
                        .then(couiMenuScrollBar(scrollBar, expanded, scrollEnabled,
                            dark = LocalCouiMenuStyle.current.isDark,
                            contentPadding = CouiMenuContentVerticalPadding))
                        .verticalScroll(scrollState, enabled = scrollEnabled)
                        .semantics { isTraversalGroup = true },
                    content = { content(scrollState) },
                )
            }
            overlay(positionProvider)
        }
    }
}

/**
 * 绘制滑动预选框，使预选切换时只移动同一个状态层。
 *
 * @param itemTop 当前预选项的顶部位置, 包含分隔区域高度; null 时不绘制。
 * @param visible 是否显示预选框，用于按下和松手时的淡入淡出。
 * @param onFadeOutFinished 淡出完成后清理保留的绘制位置。
 */
@Composable
private fun CouiMenuPressedIndicator(
    itemTop: Dp?,
    visible: Boolean,
    progress: CouiStateProgress,
    pressHandoff: Boolean,
    onFadeOutFinished: () -> Unit
) {
    if (itemTop == null) {
        return
    }
    val dark = LocalCouiMenuStyle.current.isDark
    // COUITouchRecyclerView 的共享遮罩以 hover 色作为按压终色。
    val maskColor = if (dark) 0x26FFFFFF else 0x14000000
    val evaluator = remember { ArgbEvaluator() }
    val currentOnFadeOutFinished by rememberUpdatedState(onFadeOutFinished)
    DisposableEffect(progress) { onDispose { progress.dispose() } }
    LaunchedEffect(visible, progress, pressHandoff) {
        // animateToProgressFrom 交接的预选框直接淡出，不补短按最低进度。
        progress.setActive(visible, minimumPress = !pressHandoff, onHidden = { currentOnFadeOutFinished() })
    }
    val offsetY by animateDpAsState(
        targetValue = itemTop,
        // COUI TouchRecyclerView 的垂直菜单使用 response=0.15, bounce=0。
        animationSpec = spring(dampingRatio = 1f, stiffness = 1754.5962f),
        label = "couiMenuPressedIndicatorOffsetY",
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(0, offsetY.roundToPx()) }
            .padding(horizontal = CouiMenuItemStateHorizontalInset)
            .fillMaxWidth()
            .height(CouiMenuItemHeight)
            .clip(LocalCouiMenuStyle.current.itemShape)
            .drawBehind {
                drawRect(Color(evaluator.evaluate(progress.value.coerceIn(0f, 1f), 0, maskColor) as Int))
            }
    )
}

/** 展开时保留调用方的输入配置; 收起动画保留窗口, 但立即释放触摸和焦点。 */
private fun PopupProperties.withMenuInput(expanded: Boolean): PopupProperties {
    var flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    if (!expanded || !focusable) {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
    if (!expanded) {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
    if (securePolicy == SecureFlagPolicy.SecureOn) {
        flags = flags or WindowManager.LayoutParams.FLAG_SECURE
    }
    if (!clippingEnabled) {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    return PopupProperties(
        flags = flags,
        inheritSecurePolicy = securePolicy == SecureFlagPolicy.Inherit,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        // 手势排除由菜单节点提供, 不能排除全窗口的系统返回手势。
        excludeFromSystemGesture = false,
        usePlatformDefaultWidth = false,
        windowType = windowType,
        windowToken = windowToken,
        // 本仓库的 Compose 1.12.1 的 PopupProperties 尚无 blurBehindRadius / scrimAlpha 参数;
        // 菜单不使用窗口级背景模糊与弹窗遮罩, 因此不转发这两项, 其余字段保持一致。
    )
}

@Composable
private fun <T> CouiMenuRow(
    item: CouiMenuItem<T>,
    sharedMaskVisible: Boolean,
    subMenuExpanded: Boolean = false,
    onClick: (() -> Unit)?
) {
    val clickInteractionSource = remember { MutableInteractionSource() }
    val selectedColor = LocalCouiMenuStyle.current.colors.primary
    val onSurfaceColor = LocalCouiMenuStyle.current.colors.onSurface
    val textColor = when {
        !item.enabled -> onSurfaceColor.copy(alpha = 0.38f)
        item.selected -> selectedColor
        else -> onSurfaceColor
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .height(CouiMenuItemHeight)
        .padding(horizontal = CouiMenuItemStateHorizontalInset, vertical = CouiMenuItemStateVerticalInset)
        .clip(LocalCouiMenuStyle.current.itemShape)
        .then(
            if (item.selected) {
                Modifier.background(selectedColor.copy(alpha = CouiMenuSelectedContainerAlpha))
            } else {
                Modifier
            }
        )
        .then(couiMenuItemMask(clickInteractionSource, item.enabled && onClick != null, sharedMaskVisible,
            dark = LocalCouiMenuStyle.current.isDark))
        .then(
            if (item.checked != null) {
                Modifier.toggleable(
                    value = item.checked,
                    enabled = item.enabled && onClick != null,
                    role = Role.Checkbox,
                    interactionSource = clickInteractionSource,
                    indication = null,
                    onValueChange = { onClick?.invoke() }
                )
            } else if (onClick != null) {
                Modifier.clickable(
                    enabled = item.enabled,
                    interactionSource = clickInteractionSource,
                    indication = null,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
        .padding(
            horizontal = CouiMenuItemContentHorizontalPadding,
            // 状态层内缩计入行内边距, 保持内容可用高度。
            vertical = CouiMenuItemContentVerticalPadding - CouiMenuItemStateVerticalInset
        )

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        item.checked?.let { checked ->
            Checkbox(
                checked = checked,
                enabled = item.enabled,
                shape = LocalCouiMenuStyle.current.checkboxShape,
                borderShape = LocalCouiMenuStyle.current.checkboxBorderShape,
                colors = LocalCouiMenuStyle.current.checkboxColors,
            )
            Spacer(modifier = Modifier.width(CouiMenuItemIconSpacing))
        }
        item.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(CouiMenuItemIconSize),
                tint = if (item.enabled) item.iconTint ?: textColor else textColor
            )
            Spacer(modifier = Modifier.width(CouiMenuItemIconSpacing))
        }
        Text(
            text = item.text,
            modifier = Modifier.weight(1f),
            style = LocalCouiMenuStyle.current.itemTextStyle,
            color = textColor
        )
        if (item.children.isNotEmpty()) {
            Spacer(Modifier.width(CouiMenuItemIconSpacing))
            Icon(
                imageVector = CouiMenuSubMenuIndicator,
                contentDescription = null,
                modifier = Modifier.size(width = 14.dp, height = 24.dp),
                tint = onSurfaceColor.copy(alpha = when {
                    !item.enabled -> 0.38f
                    subMenuExpanded -> 1f
                    else -> 0.30f
                }),
            )
        }
    }
}

@Composable
private fun CouiMenuDivider() {
    val outline = LocalCouiMenuStyle.current.colors.outlineVariant
    val color = outline.copy(alpha = outline.alpha * 0.6f)
    Spacer(
        Modifier.fillMaxWidth().height(CouiMenuDividerHeight).drawBehind {
            val inset = (CouiMenuItemStateHorizontalInset + CouiMenuItemContentHorizontalPadding).toPx()
            drawLine(
                color = color,
                start = Offset(inset, size.height / 2f),
                end = Offset(size.width - inset, size.height / 2f),
                strokeWidth = CouiMenuDividerThickness.toPx(),
            )
        },
    )
}

/** 默认使用当前 COUI 主题；显式 style 优先。菜单独立管理窗口、输入和退场资源。 */
@Composable
fun <T> Menu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<CouiMenuItem<T>>,
    onItemClick: (CouiMenuItem<T>) -> Unit,
    style: CouiMenuStyle = CouiMenuDefaults.style(),
    offset: DpOffset = DpOffset.Zero,
    properties: PopupProperties = PopupProperties(focusable = true),
    anchor: CouiMenuAnchor? = null,
    headerText: String? = null,
    headerTextAlign: TextAlign = TextAlign.Center,
    onSpotlightDiagnostic: ((SpotlightDiagnostic) -> Unit)? = null,
) {
    CompositionLocalProvider(
        LocalCouiMenuStyle provides style,
        LocalCouiMenuDiagnostic provides onSpotlightDiagnostic,
    ) {
        CouiMenuContent(
            expanded, onDismissRequest, items, onItemClick, modifier,
            offset, properties, anchor, headerText, headerTextAlign, style.themeTint.alpha > 0f,
        )
    }
}
