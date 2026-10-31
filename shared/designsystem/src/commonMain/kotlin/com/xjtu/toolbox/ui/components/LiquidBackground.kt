package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * 液体流动背景，复刻 [useplanes.com/components/shader-tabs](https://useplanes.com/components/shader-tabs)：
 * 三层 fbm 噪声（domain warping）在 dark / mid / light 三色之间来回混合，
 * 再叠一道 crease 暗折痕。颜色、seed、速度都可调，和站点上的 `colors` / `seed` 一一对应。
 *
 * 背景本身是深色的，白色文字直接放上去即可；如果要在亮部放小号字，调用方应再压一层暗色 scrim
 * （首页的液体卡就是这么做的）。
 *
 * Android 13+ 走 RuntimeShader（AGSL），更低版本与桌面 / Web 退化为站点 canvas 加载前的
 * 135° 线性渐变。
 */
@Composable
fun LiquidBackground(
    colors: LiquidColors,
    modifier: Modifier = Modifier,
    /** 换一组值就换一种流动形状，不影响配色。 */
    seed: Float = 0f,
    /** 流速倍率，1 为站点默认。 */
    speed: Float = 1f,
    /** 折痕强度，1 为站点默认；0 关掉暗折痕。 */
    crease: Float = 1f,
    animated: Boolean = true,
) {
    // 和站点的 IntersectionObserver 一样：滚出视口就停掉，别让看不见的卡继续跑噪声。
    val window = LocalWindowInfo.current
    var inView by remember { mutableStateOf(true) }
    PlatformLiquidBackground(
        modifier = modifier.onGloballyPositioned { coords ->
            val bounds = coords.boundsInWindow()
            val visible = bounds.bottom > 0f && bounds.top < window.containerSize.height &&
                bounds.right > 0f && bounds.left < window.containerSize.width
            if (inView != visible) inView = visible
        },
        colors = colors,
        seed = seed,
        speed = speed,
        crease = crease,
        animated = animated && inView,
    )
}

/** 液体背景的三层颜色，顺序对应站点上的 dark / mid / light。 */
@Immutable
data class LiquidColors(val dark: Color, val mid: Color, val light: Color)

/** 站点内置的五组配色。 */
object LiquidPalettes {
    val Midnight = LiquidColors(Color(0xFF0B1A66), Color(0xFF2F7CFF), Color(0xFFA9D5FF))
    val Orchid = LiquidColors(Color(0xFF4A0F8F), Color(0xFFC14DFF), Color(0xFFF2C7FF))
    val Blossom = LiquidColors(Color(0xFF5A1670), Color(0xFFEF5BD8), Color(0xFFFFD1F3))
    val Lagoon = LiquidColors(Color(0xFF0A4A3A), Color(0xFF2FD6A6), Color(0xFFC9FFF0))
    val Honey = LiquidColors(Color(0xFF5C2A00), Color(0xFFFF8A2B), Color(0xFFFFE1C2))

    /** 首页头卡最早那组固定蓝（原 `LiquidCardColors`），「午夜蓝」选项仍然用它。 */
    val ClassicMidnight = LiquidColors(Color(0xFF070D33), Color(0xFF1F5FD6), Color(0xFF7DA4E0))
}

/** 平台切口本体：Android 走着色器，其余端走 [FallbackLiquidBackground]。 */
@Composable
internal expect fun PlatformLiquidBackground(
    modifier: Modifier,
    colors: LiquidColors,
    seed: Float,
    speed: Float,
    crease: Float,
    animated: Boolean,
)

/** 没有 RuntimeShader 时的降级：和站点 canvas 出来之前的 CSS 背景一致。 */
@Composable
internal fun FallbackLiquidBackground(modifier: Modifier, colors: LiquidColors) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        0f to colors.dark,
                        0.6f to colors.mid,
                        1f to colors.light,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                )
            },
    )
}
