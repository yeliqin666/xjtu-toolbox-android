package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.xjtu.toolbox.ui.glass.coui.CouiFabDefaults
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingRenderer
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingShadow
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingSurface
import com.xjtu.toolbox.ui.glass.coui.CouiShape
import com.xjtu.toolbox.ui.glass.coui.LocalCouiContourGlowEnabled
import com.xjtu.toolbox.ui.glass.coui.LocalCouiDarkTheme
import com.xjtu.toolbox.ui.glass.coui.couiFloatingSurface
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * Android 实现：COUI 单主体浮层（与 `CouiGlassCard` 同一条渲染路径）。
 *
 * 材质复用 [CouiFabDefaults.material]（原厂有色浮层的 AGSL 材质、边缘光、内光晕），
 * 形状是 [CouiShape] 平滑圆角；投影给全透明，外圈光影由调用方自己决定（气泡那圈主色柔影）。
 */
@Composable
actual fun CouiGlassPanel(
    pageBackdrop: CouiPageBackdrop?,
    modifier: Modifier,
    cornerRadius: Dp,
    tint: Color,
    fallbackColor: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = pageBackdrop.androidBackdrop()
    if (backdrop == null) {
        CouiGlassPanelFallback(modifier, cornerRadius, fallbackColor, content)
        return
    }
    val dark = LocalIsDarkTheme.current
    val density = LocalDensity.current.density
    val contour = LocalCouiContourGlowEnabled.current
    val resolvedTint = if (tint.isSpecified) tint
    else if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f)
    val shape = remember(cornerRadius) { CouiShape(cornerRadius, cornerWeight = 2f) }
    val cornerRadiusPx = cornerRadius.value * density
    val material = remember(resolvedTint, dark) { CouiFabDefaults.material(resolvedTint, dark) }
    val renderer = remember { CouiFloatingRenderer.standalone() }
    val contentLayer = rememberGraphicsLayer()
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }
    CompositionLocalProvider(LocalCouiDarkTheme provides dark) {
        // 尺寸由内容决定：材质层用 matchParentSize（不参与定尺寸），内容层也不再 fillMaxSize。
        // 之前两层都 fillMaxSize，放进 Column 后会被撑满剩余高度，整块玻璃糊满屏幕。
        Box(modifier.onSizeChanged { boundsSize = it }) {
            Box(
                Modifier
                    .matchParentSize()
                    .couiFloatingSurface(
                        backdrop = backdrop,
                        renderer = renderer,
                        material = material,
                        shadow = CouiPanelNoShadow,
                        shape = shape,
                        contentLayer = contentLayer,
                        contourGlowEnabled = contour,
                        foregroundComposition = true,
                    ) {
                        CouiFloatingSurface(
                            bounds = Rect(
                                Offset.Zero,
                                Size(boundsSize.width.toFloat(), boundsSize.height.toFloat()),
                            ),
                            scaleX = 1f,
                            scaleY = 1f,
                            cornerWeight = 2f,
                            // 显式给半径：不传会按 min(宽,高)/2 画成胶囊，长条输入框就圆过头了。
                            cornerRadius = cornerRadiusPx,
                        )
                    },
            )
            Box(
                Modifier
                    .drawWithContent { contentLayer.record { this@drawWithContent.drawContent() } }
                    .clip(shape),
            ) {
                content()
            }
        }
    }
}

/** 面板不要 COUI 那层投影：气泡 / 输入框的外圈光影由调用方给。 */
private val CouiPanelNoShadow = CouiFloatingShadow(
    elevation = 0f,
    color = Color.Transparent,
    lightY = 0f,
    lightZ = 0f,
    lightRadius = 0f,
    causticColor = Color.Transparent,
    layout = Rect.Zero,
    sdf = 1f,
)
