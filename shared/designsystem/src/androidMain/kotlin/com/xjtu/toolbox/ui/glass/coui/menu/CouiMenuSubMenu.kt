package com.xjtu.toolbox.ui.glass.coui.menu

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled
import com.xjtu.toolbox.ui.glass.coui.couiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.rememberCouiTouchMotion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class CouiMenuPanel<T>(val scroll: ScrollState) {
    val scrollBar = CouiMenuScrollBar(scroll)
    val selection = CouiMenuSelection()
    var items = emptyList<CouiMenuItem<T>>()
    var itemTops = emptyList<Int>()
    var itemHeight = 0
    var headerHeight = 0
    var contentCoordinates: LayoutCoordinates? = null
    var surfaceCoordinates: LayoutCoordinates? = null
    var clipTop = 0f
    var clipHeight = Float.POSITIVE_INFINITY

    fun contains(root: LayoutCoordinates, position: Offset): Boolean {
        val surface = surfaceCoordinates?.takeIf { it.isAttached } ?: return false
        val local = surface.localPositionOf(root, position)
        return local.x >= 0f && local.x < surface.size.width && local.y >= clipTop &&
            local.y < min(surface.size.height.toFloat(), clipTop + clipHeight)
    }

    fun indexAt(root: LayoutCoordinates, position: Offset): Int? {
        if (!contains(root, position)) return null
        val content = contentCoordinates?.takeIf { it.isAttached } ?: return null
        val y = content.localPositionOf(root, position).y - headerHeight
        return itemTops.indexOfFirst { y >= it && y < it + itemHeight }
            .takeIf { it >= 0 && items[it].enabled }
    }

    fun isNonSelectableContentAt(root: LayoutCoordinates, position: Offset): Boolean {
        if (!contains(root, position)) return false
        val content = contentCoordinates?.takeIf { it.isAttached } ?: return false
        val y = content.localPositionOf(root, position).y
        if (y >= 0f && y < headerHeight) return true
        val itemY = y - headerHeight
        return items.indices.any { index ->
            val dividerTop = if (index == 0) 0 else itemTops[index - 1] + itemHeight
            items[index].dividerBefore && itemY >= dividerTop && itemY < itemTops[index]
        }
    }
}

private class SubMenuState<T> {
    var parentIndex by mutableStateOf<Int?>(null)
    var open by mutableStateOf(false)
    var entered = false
    var mainNeedsDown = false
    var childTouchStartedDirectly = false
    var resetProgressOnEnter = false
    val progress = Animatable(0f)
    var rootCoordinates: LayoutCoordinates? = null
    var childSize by mutableStateOf(IntSize.Zero)
    var layout by mutableStateOf<SubMenuLayout?>(null)
    var transferredPressProgress = 0f
    var collapseStartY: Float? = null

    fun show(index: Int) {
        if (parentIndex != index) {
            childSize = IntSize.Zero
            resetProgressOnEnter = true
            collapseStartY = null
        }
        entered = false
        mainNeedsDown = false
        childTouchStartedDirectly = false
        parentIndex = index
        open = true
    }

    fun hide() {
        entered = false
        open = false
    }
}

/** COUI 小屏复制父行作返回入口, 大屏保留独立的侧向子菜单。 */
@Composable
internal fun <T> CouiMenuWithSubMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<CouiMenuItem<T>>,
    onItemClick: (CouiMenuItem<T>) -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    properties: PopupProperties,
    anchor: CouiMenuAnchor?,
    headerText: String?,
    headerTextAlign: TextAlign,
    themeTintEnabled: Boolean,
) {
    val state = remember { SubMenuState<T>() }
    val mainScroll = rememberScrollState()
    val childScroll = rememberScrollState()
    val main = remember { CouiMenuPanel<T>(mainScroll) }
    val child = remember { CouiMenuPanel<T>(childScroll) }
    val motion = rememberCouiMenuMotion(expanded)
    val mainTouch = rememberCouiTouchMotion(expanded)
    val childTouch = rememberCouiTouchMotion(expanded)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val backdrop = LocalCouiMenuBackdrop.current
    val shape = LocalCouiMenuStyle.current.shape
    val color = if (backdrop != null) Color.Transparent else LocalCouiMenuStyle.current.colors.container
    var provider by remember { mutableStateOf<CouiMenuMotionPositionProvider?>(null) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    val compact = windowSize.width / density.density < 600f
    val parent = state.parentIndex?.let { items.getOrNull(it) }
    val childItems = if (parent == null) emptyList() else if (compact) listOf(parent.copy(dividerBefore = false)) + parent.children else parent.children
    val visible = parent != null && (state.open || state.progress.value > 0f)

    LaunchedEffect(expanded, items) {
        // 列表变化不能将旧的按压目标提交给新的业务列表。
        main.selection.clear()
        child.selection.clear()
        if (expanded) {
            state.hide()
            state.progress.snapTo(0f)
            state.parentIndex = null
            state.collapseStartY = null
            childScroll.stopScroll(MutatePriority.PreventUserInput)
            childScroll.scrollTo(0)
        }
    }
    LaunchedEffect(expanded, state.open, state.parentIndex) {
        // 整组退场取消子转场并保留当前形状; 只收子菜单才回到父行。
        if (!expanded) return@LaunchedEffect
        // 先结束用户滚动, 避免归零操作因滚动优先级冲突而取消整段转场。
        childScroll.stopScroll(MutatePriority.PreventUserInput)
        if (state.open) {
            // 换父项按原厂跳过旧退出再展开; 同一父项反向时保留当前进度。
            if (state.resetProgressOnEnter) {
                state.progress.snapTo(0f)
                state.resetProgressOnEnter = false
            }
            childScroll.scrollTo(0)
            snapshotFlow { state.childSize }.first { it.height > 0 }
            // COUI 等子列表 bounds 就绪, 承接父行当前按压进度并向零收尾。
            if (state.transferredPressProgress > 0f) child.selection.handoffPress(state.transferredPressProgress)
            state.transferredPressProgress = 0f
        }
        state.progress.animateTo(
            if (state.open) 1f else 0f,
            spring(dampingRatio = 1f, stiffness = 322.2728f, visibilityThreshold = 0.0001f),
        )
        state.entered = state.open
        if (!state.open) {
            // 退出结束再清理滚动位置, 避免可见内容在收起首帧跳顶。
            childScroll.scrollTo(0)
            childTouch.reset()
            // 原厂 removeSubMenuView 后才替换下一条事件, 不在退出期间给父项补按下。
            if (state.parentIndex != null) state.mainNeedsDown = true
            state.parentIndex = null
        }
    }

    fun dismiss() {
        if (!expanded) return
        main.selection.clear()
        child.selection.clear()
        onDismissRequest()
    }

    fun activate(panel: CouiMenuPanel<T>, index: Int) {
        val item = panel.items.getOrNull(index)?.takeIf { it.enabled } ?: return
        when {
            panel === child && compact && index == 0 -> {
                val parentIndex = state.parentIndex ?: return
                if (state.open) state.hide() else state.show(parentIndex)
            }
            panel === main && item.children.isNotEmpty() -> {
                child.selection.clear()
                state.transferredPressProgress = if (main.selection.pressedIndicatorIndex.value != null) {
                    main.selection.pressedIndicatorProgress.value
                } else 0f
                main.selection.clear()
                state.show(index)
            }
            else -> onItemClick(item)
        }
    }

    val mainMaterial = if (backdrop != null && (expanded || motion.visible)) {
        couiMenuMaterial(backdrop, shape, expanded && !visible, motion, mainTouch, themeTintEnabled,
            inputTarget = { point, active ->
                val root = state.rootCoordinates
                if (!visible && root != null && (active || main.contains(root, point))) main.surfaceCoordinates else null
            },
            transitionProgress = { state.progress.value })
    } else CouiMenuMaterial()
    val layout = provider?.let { position ->
        val mainSize = position.menuSize
        val index = state.parentIndex
        if (mainSize == null || index == null || windowSize.height == 0 || state.childSize.height == 0) null else {
            val rowTop = (main.itemTops.getOrNull(index) ?: 0) + main.headerHeight - mainScroll.value + with(density) { CouiMenuContentVerticalPadding.roundToPx() }
            subMenuLayout(position.menuPosition, mainSize, state.childSize, windowSize, rowTop,
                position.anchorCenterX, compact, direction, density)
        }
    }
    state.layout = layout
    child.clipTop = if (compact && layout != null) layout.padding * (1f - state.progress.value) else 0f
    child.clipHeight = if (compact && layout != null) {
        layout.clipHeight + (state.childSize.height - layout.clipHeight) * state.progress.value
    } else Float.POSITIVE_INFINITY
    // COUI 前景光效保持整面板尺寸; overrideOutline 单独更新背景边界与投影透明度。
    val childShape = if (compact && layout != null) SubMenuClipShape(shape, layout, state.progress.value) else shape
    val childAlpha = {
        val progress = state.progress.value
        val alpha = motion.frame.alpha * if (compact) 1f else progress
        if (state.layout == null || (backdrop != null && !expanded && alpha <= 0.1f)) 0f else alpha
    }
    val childShadow = if (visible) rememberCouiMenuShadow(childShape, motion, childTouch,
        transitionProgress = { state.progress.value }, parentTouchMotion = mainTouch,
        nodeAlpha = childAlpha, outlineAlpha = { if (compact) state.progress.value else 1f },
        clipTop = child.clipTop, clipHeight = child.clipHeight)
    else CouiMenuShadow(Modifier, Modifier)
    // 两个输入节点在整个会话中保留, 展开子面板不能替换正在处理按住手势的根节点。
    val childMaterial = if (backdrop != null && (expanded || motion.visible)) {
        couiMenuMaterial(backdrop, childShape, expanded && state.open, motion, childTouch, themeTintEnabled,
            inputTarget = { point, active ->
                val root = state.rootCoordinates
                // 原厂只给根节点转发的子会话补发出界 CANCEL, 原生子 DOWN 继续跟手。
                if (state.open && root != null &&
                    ((active && state.childTouchStartedDirectly) || child.contains(root, point))
                ) child.surfaceCoordinates else null
            },
            transitionProgress = { state.progress.value }, parentTouchMotion = mainTouch,
            collapseBlend = if (compact) ({
                val fraction = (state.progress.value / CouiMenuDefaults.subMenuMergeThreshold).coerceIn(0f, 1f)
                1f - fraction * fraction * (3f - 2f * fraction)
            }) else null,
            mergeSource = mainMaterial.source.takeIf { compact })
    } else CouiMenuMaterial()
    val currentActivate by rememberUpdatedState(::activate)
    val currentCompact by rememberUpdatedState(compact)
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val haptic = LocalHapticFeedback.current
    val currentFlingBehavior by rememberUpdatedState(ScrollableDefaults.flingBehavior())

    val input = Modifier.onGloballyPositioned {
        state.rootCoordinates = it
        windowSize = it.size
    }.couiTouchMotion(mainTouch, expanded && !visible) { point ->
        val root = state.rootCoordinates
        if (root != null && main.contains(root, point)) main.surfaceCoordinates?.size else null
    }.couiTouchMotion(childTouch, expanded && state.open) { point ->
        val root = state.rootCoordinates
        if (root != null && child.contains(root, point)) child.surfaceCoordinates?.size else null
    }.then(mainMaterial.input).then(childMaterial.input)
        .pointerInput(expanded, items, accessibility) {
            if (!expanded) return@pointerInput
            try {
                coroutineScope {
                    val scope = this
                    var scrollJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (currentEvent.changes.count { it.pressed } != 1 || accessibility?.isTouchExplorationEnabled == true) return@awaitEachGesture
                        val root = state.rootCoordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        state.childTouchStartedDirectly = state.open && child.contains(root, down.position)
                        if (!main.contains(root, down.position) && !(state.parentIndex != null && child.contains(root, down.position))) return@awaitEachGesture
                        val scrollPanel = if (state.open && child.contains(root, down.position)) child else main
                        if ((scrollPanel === child || state.parentIndex == null) && scrollPanel.scrollBar.contains(root, down.position)) return@awaitEachGesture
                        // 根节点独占触摸提交; 行 clickable 保留键盘、无障碍和鼠标悬停。
                        down.consume()
                        scrollJob?.cancel()
                        var panel: CouiMenuPanel<T>? = null
                        var index: Int? = null
                        var panelDown = Offset.Zero
                        var panelLast = Offset.Zero
                        var scrollSlop = viewConfiguration.touchSlop
                        var scrolling = false
                        var scrollDeltas: Channel<Float>? = null
                        var flingVelocity = 0f
                        val velocity = VelocityTracker()
                        var released = false
                        var lastPosition = down.position
                        var lastTriggeredPanel: CouiMenuPanel<T>? = null
                        var lastTriggeredIndex: Int? = null
                        var dwellPanel: CouiMenuPanel<T>? = null
                        var dwellIndex: Int? = null
                        var dwellScroll = 0
                        var anchorSlopOrigin = Offset.Zero
                        var anchorSlopExceeded = false
                        var deadline = Long.MAX_VALUE
                        var mainAreaOrigin: Offset? = if (state.entered && !child.contains(root, down.position)) down.position else null
                        var mainAreaDeadline = Long.MAX_VALUE
                        var dismissChildOnUp = state.open && !child.contains(root, down.position)
                        // 原厂退出时禁用子列表派发, 但标题的独立点击仍可反向展开。
                        val closingHeaderParent = state.parentIndex?.takeIf {
                            !state.open && currentCompact && child.indexAt(root, down.position) == 0
                        }
                        var closingHeaderClick = closingHeaderParent != null

                        fun cancelPanel() {
                            panel?.selection?.pressedIndex?.value = null
                            scrollDeltas?.close()
                            scrollDeltas = null
                            if (scrolling) scrollJob?.cancel()
                            scrolling = false
                            panel = null
                            index = null
                        }

                        fun sendDown(target: CouiMenuPanel<T>, position: Offset, time: Long) {
                            cancelPanel()
                            panel = target
                            panelDown = target.surfaceCoordinates?.localPositionOf(root, position) ?: position
                            panelLast = panelDown
                            scrollSlop = if (target.scroll.isScrollInProgress) 0f else viewConfiguration.touchSlop
                            velocity.resetTracking()
                            velocity.addPosition(time, panelDown)
                            index = if (target.scroll.isScrollInProgress) null else target.indexAt(root, position)
                            target.selection.restartIndicator()
                            target.selection.pressedIndex.value = index
                            target.selection.pressedIndicatorIndex.value = index
                        }

                        fun clearDwell() {
                            dwellPanel = null
                            dwellIndex = null
                            deadline = Long.MAX_VALUE
                        }

                        fun dwellTargetAt(position: Offset): CouiMenuPanel<T> {
                            // 子面板仍附着时遮住下面的父项, 包括进入和退出过程。
                            return if (state.parentIndex != null && child.contains(root, position)) child else main
                        }

                        fun updateDwell(position: Offset) {
                            if (scrolling) return clearDwell()
                            val target = dwellTargetAt(position)
                            val candidate = target.indexAt(root, position)?.takeIf {
                                (target === main && main.items[it].children.isNotEmpty()) ||
                                    (target === child && currentCompact && it == 0)
                            }
                            if (candidate == null || (target === lastTriggeredPanel && candidate == lastTriggeredIndex)) {
                                clearDwell()
                                return
                            }
                            if (target !== dwellPanel || candidate != dwellIndex) {
                                dwellPanel = target
                                dwellIndex = candidate
                                dwellScroll = target.scroll.value
                                anchorSlopOrigin = position
                                anchorSlopExceeded = target !== child || lastTriggeredPanel !== main
                                deadline = Long.MAX_VALUE
                            }
                            // 原厂只给刚展开后的返回标题加 slop, 不限制普通子项接续。
                            if (!anchorSlopExceeded && (position - anchorSlopOrigin).getDistance() > viewConfiguration.touchSlop) anchorSlopExceeded = true
                            if (anchorSlopExceeded && deadline == Long.MAX_VALUE) deadline = SystemClock.uptimeMillis() + 650L
                        }

                        if (state.open && child.contains(root, down.position)) {
                            sendDown(child, down.position, down.uptimeMillis)
                        } else if (!state.open && closingHeaderParent == null && main.contains(root, down.position)) {
                            state.mainNeedsDown = false
                            sendDown(main, down.position, down.uptimeMillis)
                        }
                        updateDwell(down.position)
                        try {
                            while (true) {
                                val timeout = min(deadline, mainAreaDeadline)
                                val event = if (timeout == Long.MAX_VALUE) awaitPointerEvent(PointerEventPass.Initial) else {
                                    withTimeoutOrNull((timeout - SystemClock.uptimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Initial) }
                                }
                                if (event == null) {
                                    if (mainAreaDeadline <= deadline) {
                                        state.hide()
                                        cancelPanel()
                                        mainAreaDeadline = Long.MAX_VALUE
                                    } else {
                                        val target = dwellPanel
                                        val candidate = dwellIndex
                                        if (target != null && candidate != null && target === dwellTargetAt(lastPosition) &&
                                            target.scroll.value == dwellScroll &&
                                            target.indexAt(root, lastPosition) == candidate && !target.scroll.isScrollInProgress
                                        ) {
                                            lastTriggeredPanel = target
                                            lastTriggeredIndex = candidate
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (target === child) state.hide() else {
                                                currentActivate(main, candidate)
                                            }
                                            cancelPanel()
                                        }
                                    }
                                    clearDwell()
                                    continue
                                }
                                event.changes.forEach { it.consume() }
                                if (event.isCouiTouchCancelled() || event.changes.count { it.pressed } > 1 ||
                                    accessibility?.isTouchExplorationEnabled == true
                                ) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastPosition = change.position
                                if (closingHeaderParent != null) {
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                        child.indexAt(root, change.position) != 0
                                    ) closingHeaderClick = false
                                    if (!change.pressed) {
                                        released = true
                                        if (closingHeaderClick && !state.open && state.parentIndex == closingHeaderParent) {
                                            currentActivate(child, 0)
                                        }
                                        break
                                    }
                                    continue
                                }
                                if (!state.open && state.mainNeedsDown) {
                                    cancelPanel()
                                    state.mainNeedsDown = false
                                    // 收起后的下一条事件立即替换为 DOWN, 原 UP 不能提交一级项。
                                    if (main.contains(root, change.position)) sendDown(main, change.position, change.uptimeMillis)
                                    clearDwell()
                                    if (!change.pressed) break
                                    updateDwell(change.position)
                                    continue
                                }
                                if (state.open) {
                                    if (panel === main || (panel === child && !child.contains(root, change.position))) cancelPanel()
                                    // 没有在当前子面板收到 DOWN 的 UP 不能生成一次点击。
                                    if (panel == null && change.pressed && state.entered && child.contains(root, change.position)) {
                                        sendDown(child, change.position, change.uptimeMillis)
                                    }
                                } else if (panel === child) cancelPanel()

                                // 点击从展开开始就可反向收起; 主区域停留仍等子菜单进入完成。
                                val inMainOnly = state.open && !child.contains(root, change.position) && main.contains(root, change.position)
                                if (!change.pressed) {
                                    released = true
                                    if (scrolling) {
                                        val local = panel?.surfaceCoordinates?.localPositionOf(root, change.position) ?: change.position
                                        velocity.addPosition(change.uptimeMillis, local)
                                        flingVelocity = -velocity.calculateVelocity().y
                                        scrollDeltas?.close()
                                    } else if (inMainOnly && dismissChildOnUp) state.hide() else {
                                        val target = panel
                                        val candidate = index
                                        val waitingOnAnchor = target === child && currentCompact && candidate == 0 &&
                                            lastTriggeredPanel === main && !anchorSlopExceeded
                                        if (target != null && candidate != null && target.contains(root, change.position) &&
                                            !waitingOnAnchor &&
                                            (target.indexAt(root, change.position) == candidate || target.isNonSelectableContentAt(root, change.position))
                                        ) currentActivate(target, candidate)
                                    }
                                    break
                                }
                                if (inMainOnly && state.entered) {
                                    dismissChildOnUp = true
                                    val origin = mainAreaOrigin
                                    if (origin == null) {
                                        mainAreaOrigin = change.position
                                        mainAreaDeadline = SystemClock.uptimeMillis() + 500L
                                    } else if (mainAreaDeadline == Long.MAX_VALUE && (change.position - origin).getDistance() > viewConfiguration.touchSlop) {
                                        mainAreaDeadline = SystemClock.uptimeMillis() + 500L
                                    }
                                } else {
                                    mainAreaOrigin = null
                                    mainAreaDeadline = Long.MAX_VALUE
                                }
                                val target = panel
                                // COUI allowScroll(false) 拦截列表 MOVE, 父列表等子退出完成后再接续滑选。
                                if (target != null && (target === child || state.parentIndex == null)) {
                                    val local = target.surfaceCoordinates?.localPositionOf(root, change.position) ?: change.position
                                    velocity.addPosition(change.uptimeMillis, local)
                                    val drag = local.y - panelDown.y
                                    if (target.scroll.maxValue > 0 && !scrolling && abs(drag) > scrollSlop) {
                                        scrolling = true
                                        index = null
                                        target.selection.pressedIndex.value = null
                                        val deltas = Channel<Float>(Channel.UNLIMITED)
                                        scrollDeltas = deltas
                                        scrollJob = scope.launch {
                                            target.scroll.scroll(MutatePriority.UserInput) {
                                                for (delta in deltas) scrollBy(delta)
                                                currentFlingBehavior.apply { performFling(flingVelocity) }
                                            }
                                        }
                                        deltas.trySend(-(drag - sign(drag) * scrollSlop))
                                    } else if (scrolling) {
                                        scrollDeltas?.trySend(panelLast.y - local.y)
                                    } else if (target.scroll.maxValue == 0) {
                                        val next = target.indexAt(root, change.position)
                                        if (next != index && !target.isNonSelectableContentAt(root, change.position)) {
                                            if (next != null) {
                                                if (index == null) target.selection.restartIndicator()
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            index = next
                                            target.selection.pressedIndex.value = next
                                            if (next != null) target.selection.pressedIndicatorIndex.value = next
                                        }
                                    }
                                    panelLast = local
                                }
                                updateDwell(change.position)
                            }
                        } finally {
                            scrollDeltas?.close()
                            if (!released) scrollJob?.cancel()
                            main.selection.pressedIndex.value = null
                            child.selection.pressedIndex.value = null
                        }
                    }
                }
            } finally {
                main.selection.clear()
                child.selection.clear()
            }
        }
    CouiMenuPopup(
        expanded, motion, mainTouch, anchor,
        onDismissRequest = { if (state.open) state.hide() else dismiss() },
        offset = offset, shape = shape, containerColor = color,
        modifier = modifier.then(mainMaterial.drawing).widthIn(min = CouiMenuMinWidth, max = CouiMenuMaxWidth),
        properties = properties, rootModifier = input, touchInputOnRoot = true,
        mainTransition = Modifier.graphicsLayer {
            val geometry = state.layout
            val p = state.progress.value
            scaleX = 1f + ((geometry?.mainScale ?: 1f) - 1f) * p
            scaleY = scaleX
            transformOrigin = geometry?.mainOrigin ?: TransformOrigin.Center
            translationY = (geometry?.mainTranslationY ?: 0f) * p
        }.onGloballyPositioned { main.surfaceCoordinates = it },
        transitionProgress = { state.progress.value },
        onPositioned = { provider = it },
        onOutsideRequest = { point ->
            val root = state.rootCoordinates?.takeIf { it.isAttached }
            // 提示、分隔和暂时禁用的主面板也属于菜单, 不能由背景节点关闭整个窗口。
            if (root == null || (!main.contains(root, point) && !(visible && child.contains(root, point)))) dismiss()
        },
        overlay = {
            if (visible) {
                key(state.parentIndex) {
                    Box(
                        Modifier.absoluteOffset { layout?.childPosition ?: it.menuPosition }
                            .then(childShadow.drawing),
                    ) {
                        Column(
                            Modifier
                                .graphicsLayer {
                                    val geometry = state.layout
                                    val p = state.progress.value
                                    val frame = motion.frame
                                    // ParentInfluenceUnit 持续承接父矩阵; 子 unit 的 scale 偏移相加, 不重复缩小父行。
                                    val parentSx = frame.scale * mainTouch.scaleX.value
                                    val parentSy = frame.scale * mainTouch.scaleY.value
                                    val sx = parentSx + childTouch.scaleX.value - 1f
                                    val sy = parentSy + childTouch.scaleY.value - 1f
                                    val initialScale = if (compact) 1f else p
                                    transformOrigin = geometry?.childOrigin ?: TransformOrigin.Center
                                    scaleX = sx + initialScale - 1f
                                    scaleY = sy + initialScale - 1f
                                    val delta = geometry?.pivotDelta ?: Offset.Zero
                                    translationX = frame.translation.x + mainTouch.translationX.value + childTouch.translationX.value + delta.x * (parentSx - 1f)
                                    val globalY = frame.translation.y + mainTouch.translationY.value + childTouch.translationY.value + delta.y * (parentSy - 1f)
                                    if (compact && expanded && !state.open && geometry != null) {
                                        val root = state.rootCoordinates
                                        val content = main.contentCoordinates
                                        val surface = main.surfaceCoordinates
                                        val index = state.parentIndex
                                        if (root?.isAttached == true && content?.isAttached == true &&
                                            surface?.isAttached == true && index != null
                                        ) {
                                            val rowY = main.headerHeight + (main.itemTops.getOrNull(index) ?: 0)
                                            val rowTop = max(root.localPositionOf(content, Offset(0f, rowY.toFloat())).y,
                                                root.localPositionOf(surface, Offset.Zero).y)
                                            // 回采父行当前可见位置, 再换到子面板未平移的坐标; 全局退场保留最后一次结果。
                                            state.collapseStartY = rowTop - geometry.childPosition.y - globalY -
                                                state.childSize.height * transformOrigin.pivotFractionY * (1f - sy) - sy * geometry.padding
                                        }
                                    }
                                    translationY = globalY + (state.collapseStartY ?: geometry?.childStartY ?: 0f) * (1f - p)
                                    alpha = childAlpha()
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                }
                                .onGloballyPositioned {
                                    child.surfaceCoordinates = it
                                    state.childSize = it.size
                                }
                                .then(childShadow.surface)
                                .then(if (properties.excludeFromSystemGesture) Modifier.systemGestureExclusion() else Modifier)
                                .background(color, childShape).clip(childShape).then(childMaterial.drawing)
                                .then(provider?.menuSize?.let { mainSize ->
                                    Modifier.width(with(density) { mainSize.width.toDp() })
                                } ?: Modifier.widthIn(min = CouiMenuMinWidth, max = CouiMenuMaxWidth))
                                .heightIn(max = with(density) { (windowSize.height - 48.dp.roundToPx()).coerceAtLeast(0).toDp() })
                                .width(IntrinsicSize.Max)
                                .then(couiMenuScrollBar(child.scrollBar, expanded, state.open,
                                    dark = LocalCouiMenuStyle.current.isDark,
                                    contentPadding = CouiMenuContentVerticalPadding))
                                .verticalScroll(childScroll, enabled = state.open)
                                .semantics { isTraversalGroup = true },
                        ) {
                            CouiMenuList(
                                items = childItems, expanded = expanded && state.open,
                                onItemClick = { item -> currentActivate(child, child.items.indexOf(item)) },
                                selection = child.selection, scrollState = childScroll, panel = child,
                                // 对应 onSubMenuStartToExit, 图标立即复位, 不等待转场节点移除。
                                activeSubMenuIndex = if (compact && state.open) 0 else null,
                                onSubMenuHeaderClick = if (expanded && compact) ({ currentActivate(child, 0) }) else null,
                            )
                        }
                    }
                }
            }
        },
        scrollState = mainScroll,
        scrollBar = main.scrollBar,
        scrollEnabled = expanded && !visible,
    ) { scroll ->
        CouiMenuList(
            // COUI MainUpdate 只淡化列表内容, 材质与模糊仍使用菜单整体进出透明度。
            modifier = Modifier.graphicsLayer {
                alpha = 1f + (0.3f - 1f) * state.progress.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
            items = items, expanded = expanded && !state.open,
            onItemClick = { item -> currentActivate(main, main.items.indexOf(item)) },
            selection = main.selection, scrollState = scroll, headerText = headerText,
            headerTextAlign = headerTextAlign, panel = main,
            activeSubMenuIndex = state.parentIndex.takeIf { state.open },
        )
    }
}

private data class SubMenuLayout(
    val childPosition: IntOffset,
    val mainScale: Float,
    val mainOrigin: TransformOrigin,
    val childOrigin: TransformOrigin,
    val mainTranslationY: Float,
    val childStartY: Float,
    val clipHeight: Float,
    val padding: Float,
    val pivotDelta: Offset,
)

private fun subMenuLayout(
    main: IntOffset, mainSize: IntSize, childSize: IntSize, window: IntSize, rowTop: Int,
    anchorX: Float, compact: Boolean, direction: LayoutDirection, density: Density,
): SubMenuLayout {
    val overlap = with(density) { 14.dp.roundToPx() }
    val gap = with(density) { 12.dp.roundToPx() }
    val padding = with(density) { CouiMenuContentVerticalPadding.toPx() }
    val bottom = window.height - with(density) { 32.dp.roundToPx() }
    val top = with(density) { 16.dp.roundToPx() }
    val width = (mainSize.width * 0.9f).toInt()
    val height = (mainSize.height * 0.9f).toInt()
    val centerX = main.x + mainSize.width / 2f
    val pivotX = when {
        abs(anchorX - centerX) <= 1f -> 0.5f
        anchorX > centerX -> 1f
        else -> 0f
    }
    val relocatedX = main.x + ((mainSize.width - width) * pivotX).toInt()
    val relocatedY = if (compact) min(main.y, bottom - childSize.height - gap) else main.y + (mainSize.height - height) / 2
    var x = if (compact) main.x else if (direction == LayoutDirection.Rtl) relocatedX + width - overlap else relocatedX + overlap - childSize.width
    if (!compact) {
        if (direction == LayoutDirection.Rtl && x + childSize.width >= window.width) x = relocatedX + overlap - childSize.width
        if (direction == LayoutDirection.Ltr && x <= 0) x = relocatedX + width - overlap
    }
    x = x.coerceIn(0, (window.width - childSize.width).coerceAtLeast(0))
    val candidateY = if (compact) (relocatedY + height.toFloat() / mainSize.height.coerceAtLeast(1) * rowTop).toInt() - overlap else main.y + rowTop
    val y = candidateY.coerceIn(top, (bottom - childSize.height).coerceAtLeast(top))
    val childPosition = IntOffset(x, y)
    val childOrigin = if (compact) TransformOrigin.Center else TransformOrigin(
        if (x <= main.x) 1f else 0f,
        (main.y + rowTop + with(density) { CouiMenuItemHeight.toPx() } / 2f - y) / childSize.height.coerceAtLeast(1),
    )
    return SubMenuLayout(
        childPosition, width.toFloat() / mainSize.width.coerceAtLeast(1),
        TransformOrigin(pivotX, 0f), childOrigin,
        if (compact) max((top - main.y).toFloat(), min(0f, (y - gap - main.y).toFloat())) else 0f,
        if (compact) main.y + rowTop - y - padding else 0f,
        min(with(density) { CouiMenuItemHeight.toPx() }, (mainSize.height - rowTop).toFloat()), padding,
        Offset(
            x + childSize.width * childOrigin.pivotFractionX - centerX,
            y + childSize.height * childOrigin.pivotFractionY - (main.y + mainSize.height / 2f),
        ),
    )
}

private data class SubMenuClipShape(private val base: Shape, private val layout: SubMenuLayout, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val top = layout.padding * (1f - progress)
        val height = layout.clipHeight + (size.height - layout.clipHeight) * progress
        val outline = base.createOutline(Size(size.width, height.coerceAtLeast(0f)), layoutDirection, density)
        val path = Path()
        when (outline) {
            is Outline.Generic -> path.addPath(outline.path, Offset(0f, top))
            is Outline.Rounded -> { val local = Path().apply { addRoundRect(outline.roundRect) }; path.addPath(local, Offset(0f, top)) }
            is Outline.Rectangle -> path.addRect(outline.rect.translate(Offset(0f, top)))
        }
        return Outline.Generic(path)
    }
}
