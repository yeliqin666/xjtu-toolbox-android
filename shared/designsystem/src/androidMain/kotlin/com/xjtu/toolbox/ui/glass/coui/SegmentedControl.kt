package com.xjtu.toolbox.ui.glass.coui

import android.graphics.Paint
import androidx.annotation.RequiresApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * COUI-Kit `segmentbutton/SegmentedControl.kt` 的移植：受控胶囊分段选择器。
 *
 * 默认在拖过新项目时提交，[commitOnRelease] 仅在有效 UP 后提交草稿。
 * [backdrop] 为空时使用普通填色，适合卡片 / 弹层；传入源时使用透明模糊，适合直接放在页面或多色背景上。
 * 源须是不含本控件的不透明 SDR 兄弟背景节点。[containerColor] 只用于普通填色，[indicatorColor] 用于两种外观。
 * [renderer] 仅用于透明模糊，须逐节点独立创建；宿主可在 remember 工厂中捕获构造失败后决定是否进入组件。
 * [small] 使用 32dp/2dp 紧凑预设；普通预设为 40dp/4dp。
 * Modifier 可覆盖尺寸，指示器、采样和输入均使用实际约束。取消不新增提交，已即时提交的选择由宿主保留。
 * 从选中项或移动中的指示器起拖，越过横向阈值后持续持有手势；此前的纵向拖动可由页面接管。
 */
@RequiresApi(34)
@Composable
fun SegmentedControl(
    modifier: Modifier = Modifier,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: CouiBackdrop? = null,
    dark: Boolean = CouiTheme.isDark,
    enabled: Boolean = true,
    small: Boolean = false,
    commitOnRelease: Boolean = false,
    /**
     * 集成侧扩展：重复点已选中项时是否也回调 [onSelected]。
     * 原版不回调（点已选中的格子等于无事发生）；日程的「第 N 周」要靠二次点击弹选周菜单，那里打开。
     */
    reselectable: Boolean = false,
    textStyle: TextStyle = CouiSegmentedControlDefaults.textStyle(),
    containerColor: Color = CouiSegmentedControlDefaults.containerColor(dark),
    indicatorColor: Color = CouiSegmentedControlDefaults.indicatorColor(dark),
    renderer: CouiFloatingRenderer? = if (backdrop != null) remember { CouiFloatingRenderer.standalone() } else null,
) {
    require(labels.isNotEmpty() && selectedIndex in labels.indices) { "分段标签不能为空，选中下标必须在范围内" }
    require(backdrop == null || renderer != null) { "透明模糊需要独立的单主体 renderer" }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current = rememberUpdatedState(selectedIndex)
    val submit = rememberUpdatedState(onSelected)
    val haptic = LocalHapticFeedback.current
    val session = remember(labels) { SegmentSelectionSession(selectedIndex) }
    var lightCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var inputCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val motion = remember { CouiSegmentMotion() }
    val touch = rememberCouiTouchMotion(enabled)
    val press = rememberCouiPressMotion()
    val indicatorPress = rememberCouiPressMotion()
    val labelPresses = labels.indices.map { key(it) { rememberCouiPressMotion() } }
    val contourEnabled = backdrop != null && LocalCouiContourGlowEnabled.current
    val contour = remember(contourEnabled) { if (contourEnabled) CouiContour(style = CouiContourStyle.Button) else null }
    val edgePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val indicatorShape = remember { CouiShape(999.dp, 2f) }
    val indicatorDrawing = remember(density, rtl) { SegmentIndicatorDrawing() }
    val contentLayer = if (backdrop != null) rememberGraphicsLayer() else null
    val material = remember(dark) { CouiSegmentedControlDefaults.material(dark) }
    val labelColor = textStyle.color.takeOrElse { CouiTheme.colorSchemeFor(dark).onSurface }
    val shadow = remember { CouiFloatingShadow(0f, Color.Transparent, 0f, 0f, 0f, Color.Transparent, Rect.Zero, 0f) }
    val (lightInput, light) = if (backdrop != null) {
        rememberCouiSpotlight(enabled, SpotlightPreset.OpaqueMedium2, dark,
            noise = CouiSegmentedControlDefaults.spotlightNoise(dark),
            dither = CouiSegmentedControlDefaults.spotlightDither(dark),
            inputTarget = { _, _ -> if (session.allowLight) lightCoordinates else null })
    } else Modifier to null
    val measurer = rememberTextMeasurer()
    // 字体完成异步解析时，自然宽度必须与 BasicText 一起更新。
    val resolvedTypeface = LocalFontFamilyResolver.current.resolve(textStyle.fontFamily,
        textStyle.fontWeight ?: FontWeight.Normal, textStyle.fontStyle ?: FontStyle.Normal,
        textStyle.fontSynthesis ?: FontSynthesis.All).value
    val naturalWidths = remember(labels, textStyle, density, measurer, resolvedTypeface) {
        labels.map { max(with(density) { CouiSegmentedControlDefaults.minWidth.toPx() },
            measurer.measure(it, textStyle, maxLines = 1).size.width.toFloat() +
                with(density) { CouiSegmentedControlDefaults.labelPadding.toPx() * 2f }) }
    }
    LaunchedEffect(selectedIndex, session.touching) { session.sync(selectedIndex) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            session.finish(true, current.value, commitOnRelease) { }
            indicatorPress.release()
            touch.release(Offset.Zero)
            press.release()
            labelPresses.forEach { it.release() }
            motion.release()
        }
    }
    DisposableEffect(motion) { onDispose { motion.dispose() } }

    BoxWithConstraints(modifier.height(if (small) CouiSegmentedControlDefaults.smallHeight else CouiSegmentedControlDefaults.height)) {
        val defaultPadding = with(density) {
            (if (small) CouiSegmentedControlDefaults.smallPadding else CouiSegmentedControlDefaults.padding).roundToPx()
        }
        val desired = (naturalWidths.sum() + defaultPadding * 2f).roundToInt()
        val actualWidth = constraints.constrainWidth(desired)
        val actualHeight = constraints.maxHeight
        val padding = defaultPadding.coerceAtMost(actualWidth / 2)
        val geometry = remember(actualWidth, actualHeight, padding, naturalWidths, rtl, small) {
            SegmentGeometry(actualWidth.toFloat(), actualHeight.toFloat(), padding.toFloat(),
                segmentWidths(naturalWidths, actualWidth - padding * 2), rtl, small)
        }
        LaunchedEffect(session.preview, geometry) { motion.select(session.preview, geometry, density.density) }
        var bounds by remember { mutableStateOf(IntSize.Zero) }
        val surface = if (backdrop != null) {
            Modifier.couiFloatingSurface(backdrop, checkNotNull(renderer), material, shadow, CouiCapsuleShape,
                checkNotNull(contentLayer), contourEnabled, foregroundComposition = true, beforeContent = { clip ->
                    withTransform({
                        translate(touch.translationX.value, touch.translationY.value)
                        scale(touch.scaleX.value + press.scale.value - 1f,
                            touch.scaleY.value + press.scale.value - 1f, center)
                    }) {
                        clipPath(clip) {
                            drawSegmentIndicator(motion.rect(geometry, indicatorPress.scale.value), geometry.height,
                                indicatorShape, indicatorDrawing, contour, edgePaint, dark, indicatorColor)
                            // 直接混合已绘制材质，不能先写进透明内容层。
                            light?.invoke(this)
                        }
                    }
                }, useMenuLightEdge = !dark) {
                CouiFloatingSurface(Rect(Offset(touch.translationX.value, touch.translationY.value),
                    Size(bounds.width.toFloat(), bounds.height.toFloat())),
                    scaleX = touch.scaleX.value + press.scale.value - 1f,
                    scaleY = touch.scaleY.value + press.scale.value - 1f, cornerWeight = 1f)
            }
        } else Modifier
        Box(Modifier.width(with(density) { actualWidth.toDp() }).fillMaxHeight()
            .onSizeChanged { bounds = it }.selectableGroup()
            .pointerInput(session) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) session.allowLight = true
                    }
                }
            }.then(lightInput).then(surface)) {
            val content = if (contentLayer != null) {
                Modifier.drawWithContent { contentLayer.record { this@drawWithContent.drawContent() } }
            } else {
                Modifier.drawWithCache {
                    val background = Path().apply { addOutline(indicatorShape.createOutline(size, layoutDirection, this@drawWithCache)) }
                    onDrawWithContent {
                        // 原厂 Path 分支的容器裁剪持续覆盖指示器及其径向投影。
                        clipPath(background) {
                            drawRect(containerColor)
                            drawSegmentIndicator(motion.rect(geometry, indicatorPress.scale.value), geometry.height,
                                indicatorShape, indicatorDrawing, null, edgePaint, dark, indicatorColor)
                        }
                        drawContent()
                    }
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = touch.translationX.value; translationY = touch.translationY.value
                scaleX = touch.scaleX.value + press.scale.value - 1f
                scaleY = touch.scaleY.value + press.scale.value - 1f
            }.onGloballyPositioned { lightCoordinates = it }
                .then(content).clip(if (backdrop != null) CouiCapsuleShape else indicatorShape)) {
                Row(Modifier.fillMaxSize().padding(horizontal = with(density) { padding.toDp() })
                    .onGloballyPositioned { inputCoordinates = it }
                    .pointerInput(geometry, labels, enabled, commitOnRelease) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val coordinates = inputCoordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                            val start = coordinates.localToWindow(down.position)
                            // 输入只有一个 owner，自身消费横拖不会误触发公共观察器的「父级接管」。
                            touch.begin(start, bounds)
                            press.press(bounds)
                            session.begin(current.value)
                            val downPosition = down.position + Offset(padding.toFloat(), 0f)
                            val indicator = motion.rect(geometry, indicatorPress.scale.value).inflate(10f)
                            val dragging = geometry.hit(downPosition.x) == current.value || indicator.contains(downPosition)
                            if (dragging) {
                                motion.beginDrag()
                                indicatorPress.press(IntSize(motion.indicatorWidth.roundToInt(), geometry.indicatorHeight.roundToInt()))
                            }
                            var pressedLabel = geometry.hit(downPosition.x)
                            pressedLabel?.let { labelPresses[it].press(IntSize(geometry.widths[it], geometry.indicatorHeight.roundToInt())) }
                            // 原厂只在拖动时从每项两侧扣掉切换距离，普通点按使用完整项目。
                            val switchDistance = CouiSegmentedControlDefaults.switchDistance.toPx().toInt().toFloat()
                            val hitInset = if (dragging) switchDistance else 0f
                            var rubberOrigin = down.position.x
                            var ownsDrag = false
                            var completed = false
                            val tracker = CouiVelocityTracker(down.uptimeMillis)
                            try {
                                tracker.add(down, coordinates)
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.isCouiTouchCancelled() || event.changes.size != 1) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!coordinates.isAttached) break
                                    val windowPosition = coordinates.localToWindow(change.position)
                                    val delta = windowPosition - start
                                    if (change.isConsumed) break
                                    if (!ownsDrag) {
                                        if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                                        ownsDrag = dragging && change.pressed && abs(delta.x) > viewConfiguration.touchSlop
                                    }
                                    if (ownsDrag) {
                                        // 沿底栏在 Initial 阶段接管；已开始滑选后，纵向偏移不再交回页面。
                                        change.consume()
                                    } else {
                                        val pass = if (change.pressed) PointerEventPass.Final else PointerEventPass.Main
                                        if (awaitPointerEvent(pass).changes.any { it.id == down.id && it.isConsumed }) break
                                    }
                                    tracker.add(change, coordinates)
                                    if (change.pressed) touch.move(windowPosition)
                                    val target = geometry.hit(change.position.x + padding, hitInset)
                                    if (dragging && change.pressed) {
                                        if (target != null && target != session.preview) {
                                            session.select(target, commitOnRelease) { submit.value(it) }
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        } else if (!motion.moving) {
                                            if (motion.resetRubberOrigin) { rubberOrigin = change.position.x; motion.resetRubberOrigin = false }
                                            motion.dragRubber(change.position.x - rubberOrigin, indicatorPress.scale.value,
                                                session.preview == 0 || session.preview == labels.lastIndex,
                                                switchDistance)
                                        }
                                    }
                                    if (target != pressedLabel && (!dragging || target != null)) {
                                        pressedLabel?.let { labelPresses[it].release() }
                                        if (dragging) target?.let { labelPresses[it].press(IntSize(geometry.widths[it], geometry.indicatorHeight.roundToInt())) }
                                        pressedLabel = target
                                    }
                                    if (!change.pressed) {
                                        if (target != null) {
                                            if (reselectable && target == current.value) submit.value(target)
                                            else session.select(target, commitOnRelease) { submit.value(it) }
                                        }
                                        completed = target != null
                                        touch.release(tracker.releaseVelocity())
                                        change.consume()
                                        break
                                    }
                                }
                            } finally {
                                tracker.recycle()
                                if (!completed) {
                                    session.allowLight = false
                                    touch.release(Offset.Zero)
                                }
                                press.release()
                                session.finish(!completed, current.value, commitOnRelease) { submit.value(it) }
                                motion.release()
                                indicatorPress.release()
                                labelPresses.forEach { it.release() }
                            }
                        }
                    }) {
                    labels.forEachIndexed { index, label ->
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(Modifier.width(with(density) { geometry.widths[index].toDp() }).fillMaxHeight()
                            .graphicsLayer { scaleX = labelPresses[index].scale.value; scaleY = labelPresses[index].scale.value }
                            .clip(indicatorShape)
                            .couiButtonFeedback(dark, interactionSource, enabled, pressFeedback = false)
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab; selected = selectedIndex == index
                                if (!enabled) disabled()
                                else onClick { if (index != current.value || reselectable) submit.value(index); true }
                            }.onKeyEvent {
                                if (enabled && it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar || it.key == Key.DirectionCenter)) {
                                    if (index != current.value || reselectable) submit.value(index)
                                    true
                                } else false
                            }.focusable(enabled, interactionSource).padding(horizontal = CouiSegmentedControlDefaults.labelPadding), contentAlignment = Alignment.Center) {
                            BasicText(label, style = textStyle.copy(textAlign = TextAlign.Center,
                                color = if (enabled) labelColor else labelColor.copy(alpha = labelColor.alpha * .38f)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** 每个节点只保留当前尺寸；中心移动不重建 Path 或径向渐变。 */
private class SegmentIndicatorDrawing {
    private var size = Size.Unspecified
    var path = Path()
        private set
    var shadow: Brush? = null
        private set

    fun update(next: Size, shape: CouiShape, scope: DrawScope) {
        if (size == next) return
        size = next
        path = Path().apply { addOutline(shape.createOutline(next, scope.layoutDirection, scope)) }
        shadow = Brush.radialGradient(listOf(Color(0x0D000000), Color(0x03000000)),
            Offset(next.width / 2f, next.height / 2f), (next.width / 2f).coerceAtLeast(1f))
    }
}

/** 原厂指示器的专属径向投影；轮廓仍复用公共 Drawable 后端。 */
@RequiresApi(33)
private fun DrawScope.drawSegmentIndicator(rect: Rect, containerHeight: Float, shape: CouiShape,
    drawing: SegmentIndicatorDrawing, contour: CouiContour?, paint: Paint, dark: Boolean, color: Color) {
    if (rect.width <= 0f || rect.height <= 0f) return
    drawing.update(rect.size, shape, this)
    translate(rect.left, rect.top) {
        clipPath(drawing.path, ClipOp.Difference) {
            val shadowSize = Size(rect.width, (containerHeight - rect.top - 2.dp.toPx()).coerceAtLeast(0f))
            if (dark) drawRoundRect(Color(0x14000000), Offset(0f, 2.dp.toPx()), shadowSize, CornerRadius(rect.height / 2f))
            else drawRoundRect(checkNotNull(drawing.shadow),
                Offset(0f, 2.dp.toPx()), shadowSize, CornerRadius(rect.height / 2f))
        }
        clipPath(drawing.path) {
            drawRect(color, size = rect.size)
            contour?.let {
                paint.shader = it.shader(rect.size, density, rect.height / 2f, 2f, Color.Transparent,
                    0f, if (dark) .15f else .8f, 1f, 1.8f * density)
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRect(0f, 0f, rect.width, rect.height, paint) }
            }
        }
    }
}
