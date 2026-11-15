package com.xjtu.toolbox.ui.glass.coui


import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import com.xjtu.toolbox.ui.glass.coui.CouiText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI

/**
 * 受控单选分类项。重复点击仍回调，由宿主决定选中值；组件不自行取消选中。
 * backdrop 必须来自不透明的兄弟源。每个节点独立持有 renderer，创建失败向上传播。
 */
@Composable
fun ChipTab(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    backdrop: CouiBackdrop,
    dark: Boolean = CouiTheme.isDark,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    contentColor: Color = CouiChipTabDefaults.contentColor(dark, enabled),
    textStyle: TextStyle = CouiChipTabDefaults.themeTextStyle(),
    contourGlowEnabled: Boolean = LocalCouiContourGlowEnabled.current,
    renderer: CouiFloatingRenderer = remember { CouiFloatingRenderer.standalone() },
) {
    ChipSurface(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        backdrop = backdrop,
        dark = dark,
        enabled = enabled,
        iconOnly = false,
        contentColor = contentColor,
        textStyle = textStyle,
        contour = contourGlowEnabled,
        renderer = renderer,
    ) {
        if (icon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Text(modifier = Modifier.widthIn(max = 200.dp), text = label, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 分类栏的独立图标标签；不参与单选组，图标和动作由宿主提供。 */
@Composable
fun ChipTabButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    contentDescription: String,
    backdrop: CouiBackdrop,
    dark: Boolean = CouiTheme.isDark,
    enabled: Boolean = true,
    contentColor: Color = CouiChipTabDefaults.contentColor(dark, enabled),
    contourGlowEnabled: Boolean = LocalCouiContourGlowEnabled.current,
    renderer: CouiFloatingRenderer = remember { CouiFloatingRenderer.standalone() },
    icon: @Composable () -> Unit,
) {
    ChipSurface(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        selected = true,
        onClick = onClick,
        backdrop = backdrop,
        dark = dark,
        enabled = enabled,
        iconOnly = true,
        contentColor = contentColor,
        contour = contourGlowEnabled,
        renderer = renderer,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun ChipSurface(
    modifier: Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    backdrop: CouiBackdrop,
    dark: Boolean,
    enabled: Boolean,
    iconOnly: Boolean,
    contentColor: Color,
    textStyle: TextStyle? = null,
    contour: Boolean,
    renderer: CouiFloatingRenderer,
    content: @Composable RowScope.() -> Unit,
) {
    val alpha = remember { ChipSelectionAlpha(selected) }
    LaunchedEffect(selected) { alpha.select(selected) }
    DisposableEffect(alpha) { onDispose { alpha.dispose() } }
    val density = LocalDensity.current.density
    val press = rememberCouiPressMotion()
    val interaction = remember { MutableInteractionSource() }
    val contentLayer = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val fraction = alpha.value.coerceIn(0f, 1f)
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = enabled,
        preset = SpotlightPreset.ChipTab,
        dark = dark,
        cancelOnConsumedMove = true,
    )
    val interactionModifier = if (iconOnly) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Tab,
            onClick = onClick,
        )
    }
    val sizeModifier = if (iconOnly) Modifier.size(32.dp) else Modifier.widthIn(min = 52.dp, max = 300.dp).height(32.dp)
    Box(modifier
        .then(sizeModifier)
        .onSizeChanged { bounds = it }
        // 输入区域保持原尺寸，避免按压缩放使靠边触点在移动时越界。
        .couiPressMotion(press, enabled, cancelOnConsumedMove = true)
        .then(lightInput)
        .then(interactionModifier)
        .couiFloatingSurface(
            backdrop = backdrop,
            renderer = renderer,
            material = CouiChipTabDefaults.material(dark = dark, checkedFraction = fraction),
            shadow = CouiChipTabDefaults.shadow(dark = dark, density = density, checkedFraction = fraction, caustic = contour),
            shape = CouiCapsuleShape,
            contentLayer = contentLayer,
            contourGlowEnabled = contour,
            foregroundComposition = true,
            beforeContent = { lightClip ->
                if (drawLight != null) {
                    withTransform({ scale(press.scale.value, press.scale.value, center) }) {
                        clipPath(lightClip) { drawLight.invoke(this) }
                    }
                }
            },
        ) {
            CouiFloatingSurface(bounds = Rect(Offset.Zero, Size(bounds.width.toFloat(), bounds.height.toFloat())),
                scaleX = press.scale.value, scaleY = press.scale.value, cornerWeight = 1f)
        },
    ) {
        Row(Modifier
            .then(sizeModifier)
            .graphicsLayer { scaleX = press.scale.value; scaleY = press.scale.value }
            .drawWithContent { contentLayer.record { this@drawWithContent.drawContent() } }
            .clip(CouiCapsuleShape)
            .padding(horizontal = if (iconOnly) 6.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideCouiContent(color = contentColor, textStyle = textStyle) { content() }
        }
    }
}

private class ChipSelectionAlpha(selected: Boolean) {
    private val holder = FloatValueHolder(if (selected) 1f else 0f)
    var value by mutableFloatStateOf(holder.value)
        private set
    private val animation = SpringAnimation(holder).apply {
        spring = SpringForce().apply {
            stiffness = ((2.0 * PI / 0.2f) * (2.0 * PI / 0.2f)).toFloat()
            dampingRatio = 1f
        }
        setMinimumVisibleChange(1f / 256f)
        addUpdateListener { _, position, _ -> value = position }
    }

    fun select(selected: Boolean) { animation.animateToFinalPosition(if (selected) 1f else 0f) }
    fun dispose() { animation.cancel() }
}
