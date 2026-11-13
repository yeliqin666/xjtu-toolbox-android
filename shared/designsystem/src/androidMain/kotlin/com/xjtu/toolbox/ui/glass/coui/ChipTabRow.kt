package com.xjtu.toolbox.ui.glass.coui


import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.Choreographer
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val ChipMaskFade = 12.dp
private val ChipShadowExpansion = 100.dp
private const val ChipActionVisibilityDuration = 280

// 原位节点只录制绘制，裁剪负责限制子项命中；阴影在整栏共享层中回放。
private object ChipViewportShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val expansion = with(density) { ChipShadowExpansion.toPx() }
        return Outline.Rectangle(Rect(0f, -expansion, size.width, size.height + expansion))
    }
}

/** key 在同一分类栏内唯一，且在重排及重组期间保持稳定。图标不由组件重新着色。 */
data class CouiChipTabItem(
    val key: Any,
    val label: String,
    val enabled: Boolean = true,
    val icon: (@Composable () -> Unit)? = null,
)

/**
 * 单行、受控单选分类栏。非空列表必须提供有效 selectedKey；重复点击不会取消选择。
 * 点击、焦点及 selectedKey 变化会滚动显示对应项目；首次布局保留初始或恢复的位置。
 * animationLevel 对应原厂 1=高、2=中、3=低、4=极低；不读取厂商私有系统属性。
 * itemSpacing 默认遵循原厂 group 的分类间距；宿主可按页面资源配置间隔。
 * leadingAction 位于单选组之外，为 null 时只显示分类列表；leadingActionVisible 控制其动画显隐。
 * 需要退出过渡时保留 leadingAction 内容，只切换 leadingActionVisible。
 * renderers 与项目按顺序对应，不得跨节点复用。
 */
@Composable
fun ChipTabRow(
    modifier: Modifier = Modifier,
    items: List<CouiChipTabItem>,
    selectedKey: Any?,
    onSelectedKeyChange: (Any) -> Unit,
    backdrop: CouiBackdrop,
    dark: Boolean = CouiTheme.isDark,
    enabled: Boolean = true,
    leadingAction: (@Composable () -> Unit)? = null,
    leadingActionVisible: Boolean = true,
    itemSpacing: Dp = 8.dp,
    animationLevel: Int = 1,
    contentColor: Color = CouiChipTabDefaults.contentColor(dark, enabled),
    disabledContentColor: Color = CouiChipTabDefaults.contentColor(dark, false),
    textStyle: TextStyle = CouiChipTabDefaults.themeTextStyle(),
    contourGlowEnabled: Boolean = LocalCouiContourGlowEnabled.current,
    /**
     * 集成侧扩展：重复点已选中项时是否也回调 [onSelectedKeyChange]。
     * 原版不回调；学辅的排序胶囊靠「再点当前项翻升 / 降序」，那里打开。
     */
    reselectable: Boolean = false,
    renderers: List<CouiFloatingRenderer> = remember(items.map { it.key }) { items.map { CouiFloatingRenderer.standalone() } },
) {
    require(items.map { it.key }.distinct().size == items.size) { "分类项的 key 必须唯一" }
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }
    require(if (items.isEmpty()) selectedKey == null else selectedIndex >= 0) { "选中分类必须属于当前列表" }
    require(itemSpacing.value.isFinite() && itemSpacing >= 0.dp) { "分类间距必须为有限非负值" }
    require(animationLevel in 1..4) { "动画等级必须在 1 到 4 之间" }
    require(renderers.size == items.size && renderers.distinct().size == renderers.size) { "每个分类项必须使用独立渲染器" }
    val scroll = rememberSaveable(animationLevel, saver = Saver<ChipRowScroll, Int>(
        save = { it.position }, restore = { ChipRowScroll(animationLevel, it, true) },
    )) { ChipRowScroll(animationLevel) }
    DisposableEffect(scroll) { onDispose { scroll.dispose() } }
    val context = LocalContext.current
    val configuration = remember(context) { ViewConfiguration.get(context) }
    val screenWidth = context.resources.displayMetrics.widthPixels
    scroll.setRefreshRate(checkNotNull(LocalView.current.display) { "无法读取分类栏所在显示器" }.refreshRate)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onSelect by rememberUpdatedState(onSelectedKeyChange)
    val showLeadingAction = leadingAction != null && leadingActionVisible
    val contentPadding by animateDpAsState(
        targetValue = if (showLeadingAction) 8.dp else 16.dp,
        animationSpec = tween(ChipActionVisibilityDuration, easing = FastOutSlowInEasing),
        label = "chipTabContentPadding",
    )
    val viewportLayer = rememberGraphicsLayer()
    val actionLayer = rememberGraphicsLayer()
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    Row(modifier.chipMask(scroll, contentPadding, viewportLayer, actionLayer) { viewportBounds },
        verticalAlignment = Alignment.CenterVertically) {
        TopAppBarActionVisibility(visible = showLeadingAction,
            durationMillis = ChipActionVisibilityDuration, easing = FastOutSlowInEasing) {
            Box(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) { leadingAction?.invoke() }
        }
        Layout(
            modifier = Modifier.weight(1f)
                .onPlaced { viewportBounds = Rect(it.positionInParent(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                .clip(ChipViewportShape)
                .drawWithContent { viewportLayer.record { this@drawWithContent.drawContent() } }
                .selectableGroup()
                .semantics {
                    horizontalScrollAxisRange = ScrollAxisRange({ scroll.position.toFloat() }, { scroll.maximum.toFloat() }, rtl)
                    if (enabled) scrollBy { x, _ -> scroll.scrollBy(if (rtl) -x else x); true }
                }
                .pointerInput(scroll, enabled, screenWidth, configuration) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scroll.stopScroll()
                        var fingerVelocity = 0
                        try {
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            val dragged = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                scroll.drag(-over.toInt(), screenWidth)
                                change.consume()
                            } ?: return@awaitEachGesture
                            tracker.addPosition(dragged.uptimeMillis, dragged.position)
                            var pointer = dragged.id
                            var lastX = dragged.position.x.toInt()
                            var released = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.isCouiTouchCancelled()) break
                                var change = event.changes.firstOrNull { it.id == pointer }
                                if (change == null || !change.pressed) {
                                    val replacement = event.changes.firstOrNull { it.pressed }
                                    if (replacement == null) {
                                        if (change != null) tracker.addPosition(change.uptimeMillis, change.position)
                                        released = true
                                        break
                                    }
                                    change = replacement
                                    pointer = change.id
                                    lastX = change.position.x.toInt()
                                    tracker.resetTracking()
                                }
                                if (change.isConsumed) break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val x = change.position.x.toInt()
                                scroll.drag(lastX - x, screenWidth)
                                lastX = x
                                change.consume()
                            }
                            if (released) {
                                fingerVelocity = tracker.calculateVelocity().x.roundToInt().coerceIn(
                                    -configuration.scaledMaximumFlingVelocity, configuration.scaledMaximumFlingVelocity)
                            }
                        } finally {
                            // 未超过拖动阈值的 UP/CANCEL 也必须恢复被 DOWN 中断的越界回弹。
                            scroll.release(fingerVelocity, configuration.scaledMinimumFlingVelocity, screenWidth)
                        }
                    }
                },
            content = {
                items.forEachIndexed { index, item ->
                    key(item.key) {
                        ChipTab(
                            modifier = Modifier.onFocusChanged { if (it.isFocused) scroll.reveal(index) },
                            selected = selectedKey == item.key,
                            onClick = {
                                if (selectedKey != item.key || reselectable) onSelect(item.key)
                                scroll.reveal(index)
                            },
                            label = item.label, backdrop = backdrop, dark = dark, enabled = enabled && item.enabled,
                            icon = item.icon, contentColor = if (enabled && item.enabled) contentColor else disabledContentColor,
                            textStyle = textStyle, contourGlowEnabled = contourGlowEnabled, renderer = renderers[index],
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            require(constraints.hasBoundedWidth) { "分类栏必须有有限宽度" }
            val padding = contentPadding.roundToPx()
            val fade = ChipMaskFade.roundToPx()
            val verticalPadding = 8.dp.roundToPx()
            val spacing = itemSpacing.roundToPx()
            val width = constraints.maxWidth
            val itemMaxWidth = (width - padding * 2 - fade * 2).coerceAtLeast(0)
            val children = measurables.map { it.measure(Constraints(maxWidth = itemMaxWidth, maxHeight = constraints.maxHeight)) }
            val contentWidth = children.sumOf { it.width } + (children.size - 1).coerceAtLeast(0) * spacing
            val height = constraints.constrainHeight((children.maxOfOrNull { it.height } ?: 32.dp.roundToPx()) + verticalPadding * 2)
            val lefts = IntArray(children.size)
            var used = 0
            children.forEachIndexed { index, child ->
                lefts[index] = if (rtl) contentWidth - used - child.width else used
                used += child.width + spacing
            }
            scroll.layout(width, padding, contentWidth, lefts, children.map { it.width }.toIntArray(), rtl,
                selectedKey, selectedIndex)
            layout(width, height) {
                children.forEachIndexed { index, child -> child.place(padding + lefts[index] - scroll.position, verticalPadding) }
            }
        }
    }
}

private fun Modifier.chipMask(scroll: ChipRowScroll, padding: Dp, viewportLayer: GraphicsLayer, actionLayer: GraphicsLayer,
    viewportBounds: () -> Rect): Modifier = drawWithCache {
    val bounds = viewportBounds()
    val inset = padding.roundToPx().toFloat()
    val fade = ChipMaskFade.toPx()
    // 渐隐在停靠边界处结束，避免覆盖按内边距对齐的完整胶囊。
    val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        shader = LinearGradient(inset - fade, 0f, inset, 0f, -1, 0, Shader.TileMode.CLAMP)
    }
    val right = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        shader = LinearGradient(bounds.width - inset, 0f, bounds.width - inset + fade, 0f, 0, -1, Shader.TileMode.CLAMP)
    }
    val expansion = ChipShadowExpansion.toPx()
    onDrawWithContent {
        // 列表原位只录制；完整动作槽位随后回放，保留其位置、淡化与外围投影。
        actionLayer.record { this@onDrawWithContent.drawContent() }
        val canvas = drawContext.canvas.nativeCanvas
        val layer = canvas.saveLayer(0f, -expansion, size.width, size.height + expansion, null)
        val viewport = canvas.save()
        canvas.translate(bounds.left, bounds.top)
        val clip = canvas.save()
        val fadeLeft = scroll.position > 0
        val fadeRight = scroll.position < scroll.maximum
        canvas.clipRect(if (fadeLeft) inset - fade else -100f, -100f,
            if (fadeRight) bounds.width - inset + fade else bounds.width + 100f, bounds.height + 100f)
        drawLayer(viewportLayer)
        canvas.restoreToCount(clip)
        if (fadeLeft) canvas.drawRect(inset - fade, -100f, inset, bounds.height + 100f, left)
        if (fadeRight) canvas.drawRect(bounds.width - inset, -100f, bounds.width - inset + fade, bounds.height + 100f, right)
        canvas.restoreToCount(viewport)
        // 原厂图标标签以正 elevation 排在列表之后，不受列表的 DST_OUT 遮罩擦除。
        drawLayer(actionLayer)
        canvas.restoreToCount(layer)
    }
}

private class ChipRowScroll(animationLevel: Int, initialPosition: Int = 0, private var initialized: Boolean = false) {
    var position by mutableIntStateOf(initialPosition)
        private set
    var maximum by mutableIntStateOf(0)
        private set
    private var viewport = 0
    private var padding = 0
    private var contentWidth = 0
    private var lefts = IntArray(0)
    private var widths = IntArray(0)
    private var layoutRtl: Boolean? = null
    private var lastSelectedKey: Any? = null
    private var lastSelectedIndex = -1
    private var frameTime = 0L
    private var frameSeconds = 0.016f
    private var nominalFrameSeconds = 0.016f
    private var animationStartMillis = 0L
    private var screenWidth = 1
    private var disposed = false
    private val physics = CouiScrollPhysics(animationLevel)
    private val holder = FloatValueHolder()
    private val selection = SpringAnimation(holder).apply {
        spring = SpringForce().apply {
            stiffness = ((2.0 * PI / 0.4f) * (2.0 * PI / 0.4f)).toFloat()
            dampingRatio = 0.8f
        }
        addUpdateListener { _, value, _ -> position = value.roundToInt().coerceIn(0, maximum) }
    }
    private val choreographer = Choreographer.getInstance()
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (disposed || !physics.running) return
            frameSeconds = if (frameTime == 0L) frameSeconds else (time - frameTime) / 1_000_000_000f
            frameTime = time
            val old = position
            physics.update(frameSeconds, (AnimationUtils.currentAnimationTimeMillis() - animationStartMillis).toFloat())
            var next = physics.current
            if (next < 0 || next > maximum) {
                val edge = if (next < 0) 0 else maximum
                val raw = next - edge
                val distance = screenWidth * 0.3731f
                next = edge + ((raw * distance).toDouble() / sqrt((distance * distance + raw * raw).toDouble())).toInt()
            }
            if ((old >= 0 && next < 0) || (old <= maximum && next > maximum)) {
                physics.returnToBounds(next, maximum, preserveVelocity = true)
            }
            position = if (maximum == 0) 0 else next
            if (physics.running) choreographer.postFrameCallback(this)
        }
    }

    fun layout(viewport: Int, padding: Int, contentWidth: Int, lefts: IntArray, widths: IntArray, rtl: Boolean,
        selectedKey: Any?, selectedIndex: Int) {
        val selectionChanged = layoutRtl != null && (lastSelectedKey != selectedKey || lastSelectedIndex != selectedIndex)
        val layoutChanged = layoutRtl != null && (this.viewport != viewport || this.padding != padding ||
            this.contentWidth != contentWidth || layoutRtl != rtl ||
            !this.lefts.contentEquals(lefts) || !this.widths.contentEquals(widths))
        this.viewport = viewport
        this.padding = padding
        this.contentWidth = contentWidth
        this.lefts = lefts
        this.widths = widths
        val oldMaximum = maximum
        maximum = max(0, contentWidth - (viewport - padding * 2).coerceAtLeast(0))
        if (maximum == 0) {
            stopScroll()
            position = 0
        } else if ((!initialized && rtl) || (layoutRtl != null && layoutRtl != rtl)) position = if (rtl) maximum else 0
        else if (maximum != oldMaximum) {
            stopScroll()
            position = position.coerceIn(0, maximum)
        }
        layoutRtl = rtl
        lastSelectedKey = selectedKey
        lastSelectedIndex = selectedIndex
        initialized = true
        if (selectionChanged || layoutChanged) {
            stopScroll()
            reveal(selectedIndex)
        }
    }

    fun stopScroll() {
        physics.stop()
        choreographer.removeFrameCallback(frame)
        frameTime = 0L
        selection.cancel()
    }

    fun setRefreshRate(rate: Float) {
        require(rate.isFinite() && rate > 0f) { "显示刷新率必须为有限正值" }
        nominalFrameSeconds = (1_000_000_000f / rate).toLong() / 1_000_000f / 1000f
    }

    fun drag(delta: Int, screenWidth: Int) {
        selection.cancel()
        val over = when { position < 0 -> position; position > maximum -> position - maximum; else -> 0 }
        val actual = if (over == 0) delta else (((1f - min(abs(over).toFloat() / screenWidth, 1f)) * delta / 5f) * 2f).toInt()
        if (maximum > 0) position += actual
    }

    fun release(fingerVelocity: Int, minimumVelocity: Int, screenWidth: Int) {
        // 同一帧的点击可能已启动选中露出，不能再启动一条竞争的回弹。
        if (disposed || maximum == 0 || selection.isRunning) return
        this.screenWidth = screenWidth.coerceAtLeast(1)
        val speed = abs(fingerVelocity.toLong())
        val fling = speed > minimumVelocity && (position in 1 until maximum ||
            position > maximum && fingerVelocity >= 1500 || position < 0 && fingerVelocity <= -1500)
        if (fling) physics.fling(position, -fingerVelocity, nominalFrameSeconds)
        else physics.returnToBounds(position, maximum, initialVelocity = if (speed > minimumVelocity) -fingerVelocity.toFloat() else null)
        frameTime = System.nanoTime()
        animationStartMillis = AnimationUtils.currentAnimationTimeMillis()
        choreographer.removeFrameCallback(frame)
        if (physics.running) choreographer.postFrameCallback(frame)
    }

    fun reveal(index: Int) {
        if (index !in lefts.indices) return
        val extent = (viewport - padding * 2).coerceAtLeast(0)
        val visibleLeft = position
        val visibleRight = position + extent
        val left = lefts[index]
        val right = left + widths[index]
        val target = when {
            left < visibleLeft -> left
            right > visibleRight -> right - extent
            else -> return
        }.coerceIn(0, maximum)
        stopScroll()
        holder.value = position.toFloat()
        selection.setStartValue(position.toFloat())
        selection.setStartVelocity(0f)
        selection.animateToFinalPosition(target.toFloat())
    }

    fun scrollBy(delta: Float) {
        stopScroll()
        position = (position + delta.roundToInt()).coerceIn(0, maximum)
    }

    fun dispose() {
        disposed = true
        stopScroll()
    }
}
