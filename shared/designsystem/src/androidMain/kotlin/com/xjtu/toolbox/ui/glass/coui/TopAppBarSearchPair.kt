package com.xjtu.toolbox.ui.glass.coui

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

private enum class SearchPairSlot { Input, Action }

/** 搜索与动作共享双主体材质；slot 只提供内容，点击、几何和反馈由本节点负责。 */
@RequiresApi(34)
@Composable
internal fun TopAppBarSearchPair(
    modifier: Modifier = Modifier,
    fullWidth: Float,
    progress: Float,
    enabled: Boolean,
    active: Boolean,
    deform: Boolean,
    animating: Boolean,
    capsule: Boolean,
    contentRenderEffect: androidx.compose.ui.graphics.RenderEffect?,
    action: @Composable () -> Unit,
    onActionClick: (() -> Unit)?,
    input: @Composable (Float) -> Unit,
) {
    val backdrop = checkNotNull(LocalCouiToolbarBackdrop.current)
    val style = LocalCouiToolbarStyle.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val renderer = remember { CouiFloatingRenderer.pair() }
    val inputLayer = rememberGraphicsLayer()
    val actionLayer = rememberGraphicsLayer()
    SideEffect { inputLayer.renderEffect = contentRenderEffect }
    val shape = style.capsuleShape
    val inputTouch = rememberCouiTouchMotion(deform)
    val inputPress = rememberCouiPressMotion()
    val actionEnabled = enabled && active && onActionClick != null
    val actionTouch = rememberCouiTouchMotion(deform && onActionClick != null)
    val actionPress = rememberCouiPressMotion()
    val actionSource = remember { MutableInteractionSource() }
    val (inputLightInput, inputLight) = rememberCouiSpotlight(enabled && active, SpotlightPreset.OpaqueMedium2, style.dark)
    val (actionLightInput, actionLight) = rememberCouiSpotlight(actionEnabled, SpotlightPreset.OpaqueMedium2, style.dark)
    var geometry by remember { mutableStateOf<CouiTopAppBarSearchPairGeometry?>(null) }
    val fraction = progress.coerceIn(0f, 1f)
    val actionBlur = remember(fraction) {
        val radius = (1f - fraction) * 30f
        if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL).asComposeRenderEffect() else null
    }
    fun inputSurface(): CouiFloatingSurface {
        val bounds = checkNotNull(geometry).inputBounds.translate(Offset(inputTouch.translationX.value, inputTouch.translationY.value))
        return CouiFloatingSurface(bounds,
            scaleX = inputTouch.scaleX.value + inputPress.scale.value - 1f,
            scaleY = inputTouch.scaleY.value + inputPress.scale.value - 1f, cornerWeight = 1f)
    }
    fun actionSurface(): CouiFloatingSurface {
        val frame = checkNotNull(geometry)
        return CouiFloatingSurface(frame.actionBounds.translate(Offset(actionTouch.translationX.value, actionTouch.translationY.value)),
            scaleX = frame.actionScale * (actionTouch.scaleX.value + actionPress.scale.value - 1f),
            scaleY = frame.actionScale * (actionTouch.scaleY.value + actionPress.scale.value - 1f),
            cornerWeight = 1f, alpha = frame.actionAlpha, visible = frame.actionAlpha > 0f)
    }
    val extension = with(density) { 4.dp.roundToPx() }
    val materials = listOf(style.material(capsule).copy(index = 1, extension = extension),
        style.material(true).copy(index = 2, extension = extension))
    val shadow = style.shadow(true)
    val drawing = Modifier.couiFloatingPair(backdrop, renderer, materials,
        listOf(shadow, shadow), listOf(shape, shape), listOf(inputLayer, actionLayer), style.contourGlowEnabled,
        drawOrder = listOf(1, 0), foregroundComposition = true,
        beforeContent = { index, path ->
            val surface = if (index == 0) inputSurface() else actionSurface()
            val light = if (index == 0) inputLight else actionLight
            if (light != null && surface.visible) inset(surface.bounds.left, surface.bounds.top,
                size.width - surface.bounds.right, size.height - surface.bounds.bottom) {
                withTransform({ scale(surface.scaleX, surface.scaleY, center) }) { clipPath(path) { light() } }
            }
        }, surfaces = { listOf(inputSurface(), actionSurface()) })
    SubcomposeLayout(modifier.then(drawing)) { constraints ->
        val height = constraints.maxHeight
        // 先测量自定义内容，再确定输入最终宽度；不经 onSizeChanged 回写制造一帧错位。
        val actionWidthLimit = min(CouiTopAppBarDefaults.searchActionMaxWidth.roundToPx(),
            (fullWidth * .4f).roundToInt()).coerceAtLeast(1)
        val button = subcompose(SearchPairSlot.Action) {
            ProvideCouiContent(color = style.contentColor.copy(alpha = style.contentColor.alpha * if (enabled && onActionClick != null) 1f else .38f),
                textStyle = style.textStyle) {
                Box(Modifier.height(40.dp)
                    .widthIn(min = CouiTopAppBarDefaults.searchActionMinWidth, max = CouiTopAppBarDefaults.searchActionMaxWidth)
                    .graphicsLayer {
                        // 挂载时的语义计算早于首次测量，未测量节点不参与显示或命中。
                        val frame = geometry
                        if (frame == null) {
                            alpha = 0f
                        } else {
                            val surface = actionSurface()
                            translationX = surface.bounds.left - frame.actionBounds.left.roundToInt()
                            translationY = surface.bounds.top
                            scaleX = surface.scaleX
                            scaleY = surface.scaleY
                            alpha = surface.alpha
                        }
                    }.couiTouchMotion(actionTouch, deform && onActionClick != null)
                    .couiPressMotion(actionPress, actionEnabled).then(actionLightInput)
                    .drawWithContent { actionLayer.record { this@drawWithContent.drawContent() } }
                    .clip(shape)
                    .then(if (!active) Modifier.clearAndSetSemantics {} else Modifier)
                    .couiMovingActionClick(actionSource, actionEnabled, animating) { onActionClick?.invoke() }
                    .padding(horizontal = CouiTopAppBarDefaults.searchActionPadding),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.graphicsLayer { alpha = fraction; renderEffect = actionBlur }) { action() }
                }
            }
        }.single().measure(Constraints(maxWidth = actionWidthLimit, minHeight = height, maxHeight = height))
        val gap = min(CouiTopAppBarDefaults.searchActionGap.toPx(), (fullWidth - button.width - 1f).coerceAtLeast(0f))
        val finalInputWidth = (fullWidth - button.width - gap).roundToInt().coerceAtLeast(1).toFloat()
        val frame = topAppBarSearchPairGeometry(constraints.maxWidth.toFloat(), height.toFloat(),
            button.width.toFloat(), gap, progress, rtl)
        geometry = frame
        val body = subcompose(SearchPairSlot.Input) {
            ProvideCouiContent(color = style.contentColor.copy(alpha = style.contentColor.alpha * if (enabled) 1f else .38f),
                textStyle = style.textStyle) {
                Box(Modifier.graphicsLayer {
                    if (geometry == null) {
                        alpha = 0f
                    } else {
                        val surface = inputSurface()
                        translationX = inputTouch.translationX.value
                        translationY = inputTouch.translationY.value
                        scaleX = surface.scaleX
                        scaleY = surface.scaleY
                        alpha = 1f
                    }
                }.couiTouchMotion(inputTouch, deform).couiPressMotion(inputPress, enabled && active)
                    .then(inputLightInput)
                    .drawWithContent { inputLayer.record { this@drawWithContent.drawContent() } }
                    .clip(shape)) { input(finalInputWidth) }
            }
        }.single().measure(Constraints.fixed(frame.inputBounds.width.roundToInt(), height))
        layout(constraints.maxWidth, height) {
            // 材质顺序由双主体 renderer 管理；已显露的动作优先接收触摸。
            if (frame.actionAlpha > 0f) button.place(frame.actionBounds.left.roundToInt(), 0, zIndex = 1f)
            body.place(frame.inputBounds.left.roundToInt(), 0)
        }
    }
}
