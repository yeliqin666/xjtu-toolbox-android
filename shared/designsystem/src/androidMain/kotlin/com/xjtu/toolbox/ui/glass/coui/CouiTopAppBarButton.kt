package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * COUI-Kit `TopAppBarButton` 的移植：圆形 40dp 顶栏按钮；[text] 为真时是胶囊文字按钮。
 *
 * 与原版的差别只有两处：
 * - 采样源由参数 [backdrop]（作用域内的 `LocalCouiToolbarBackdrop`）传进来；
 * - 内容槽是 `RowScope`（原版也是），图标按钮里只放一颗图标。
 *
 * 尺寸、材质、光照、跟手、按压、hover/focus、禁用态全部照原版：
 * - 原版 `minimumInteractiveComponentSize` 保留，交互区 48dp、视觉圆 40dp；
 * - [text] 为真时按原版走胶囊分支：`widthIn(min = 60.dp).height(40.dp)`、内容水平留 12dp，
 *   形状由 `boundsSize` 宽高不等自动切到 `style.capsuleShape`，材质/光照与圆钮同一套；
 * - `CouiTopAppBarButtonDefaults.style()` 决定 material/shadow/内容色，
 *   `couiFloatingSurface` 画 AGSL 浮层，跟手光与按压弹簧各自独立；
 * - [enabled] 为 false 时关掉跟手光、形变、按压与点击，内容色乘 0.38。
 */
@Composable
internal fun CouiTopAppBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 胶囊文字按钮（原版 `text` 参数）：宽 ≥ 60dp、高 40dp，内容水平留 12dp。 */
    text: Boolean = false,
    size: Dp = 40.dp,
    renderer: CouiFloatingRenderer = remember { CouiFloatingRenderer.standalone() },
    style: CouiToolbarStyle = CouiTopAppBarButtonDefaults.style(),
    content: @Composable RowScope.() -> Unit,
) {
    // COUI 原版：按钮必须在顶栏表面作用域内，采样源由 CouiTopAppBarSurface 提供。
    val backdrop = checkNotNull(LocalCouiToolbarBackdrop.current) { "顶栏按钮必须位于顶栏效果作用域内" }
    val dark = style.dark
    val contour = style.contourGlowEnabled
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }
    val capsule = boundsSize.width != boundsSize.height
    val shape = if (capsule) style.capsuleShape else style.circleShape
    val material = remember(style, capsule) { style.material(capsule) }
    val shadow = remember(style, capsule) { style.shadow(capsule) }
    val contentLayer = rememberGraphicsLayer()
    val motion = rememberCouiTouchMotion(expanded = enabled)
    val press = rememberCouiPressMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = enabled,
        preset = SpotlightPreset.OpaqueSmall2,
        dark = dark,
    )
    val contentColor = style.contentColor
    Box(
        modifier
            .then(Coui.defaultStyle.minimumInteractiveComponentSize())
            // 原版：文字按钮按内容撑宽，最小 60dp、高固定 40dp；图标按钮是 40dp 正方形。
            .then(if (text) Modifier.widthIn(min = 60.dp).height(40.dp) else Modifier.size(size))
            .onSizeChanged { boundsSize = it }
            .drawWithCache {
                val lightClip = Path().apply {
                    addOutline(shape.createOutline(this@drawWithCache.size, layoutDirection, this@drawWithCache))
                }
                onDrawWithContent {
                    drawContent()
                    // 原厂直接混合已绘制的按钮表面, 不能先混入透明内容层再 SrcOver。
                    if (drawLight != null) {
                        withTransform({
                            translate(motion.translationX.value, motion.translationY.value)
                            scale(
                                motion.scaleX.value + press.scale.value - 1f,
                                motion.scaleY.value + press.scale.value - 1f,
                                center,
                            )
                        }) {
                            clipPath(lightClip) { drawLight.invoke(this) }
                        }
                    }
                }
            }
            .couiFloatingSurface(
                backdrop = backdrop,
                renderer = renderer,
                material = material,
                shadow = shadow,
                shape = shape,
                contentLayer = contentLayer,
                contourGlowEnabled = contour,
                foregroundComposition = true,
            ) {
                CouiFloatingSurface(
                    bounds = Rect(
                        Offset(motion.translationX.value, motion.translationY.value),
                        Size(boundsSize.width.toFloat(), boundsSize.height.toFloat()),
                    ),
                    scaleX = motion.scaleX.value + press.scale.value - 1f,
                    scaleY = motion.scaleY.value + press.scale.value - 1f,
                    cornerWeight = if (capsule) 1f else 2f,
                )
            },
    ) {
        Row(
            Modifier
                .then(if (text) Modifier.widthIn(min = 60.dp).height(40.dp) else Modifier.fillMaxSize())
                .graphicsLayer {
                    translationX = motion.translationX.value
                    translationY = motion.translationY.value
                    scaleX = motion.scaleX.value + press.scale.value - 1f
                    scaleY = motion.scaleY.value + press.scale.value - 1f
                }
                .couiTouchMotion(motion, expanded = enabled)
                .couiPressMotion(press, enabled = enabled)
                .then(lightInput)
                .drawWithContent { contentLayer.record { this@drawWithContent.drawContent() } }
                .clip(shape)
                .couiToolbarFeedback(dark, interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .then(if (text) Modifier.padding(horizontal = 12.dp) else Modifier),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideCouiContent(
                color = if (enabled) contentColor else contentColor.copy(alpha = contentColor.alpha * 0.38f),
                textStyle = style.textStyle,
            ) { content() }
        }
    }
}
