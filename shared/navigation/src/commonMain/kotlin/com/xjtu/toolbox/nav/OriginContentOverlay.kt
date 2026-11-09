package com.xjtu.toolbox.nav

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp

/** 源卡片与目的页共用形变窗口；位置、裁剪、透明度只在绘制阶段更新。 */
@Composable
fun OriginContentOverlay(screenCornerPx: Float, windowColor: androidx.compose.ui.graphics.Color) {
    val transition = ExpandOrigins.contentTransition.value ?: return
    // 转场结束后原卡已经放出来了，叠加层再盖两帧才撤，保证交接那一下没有空帧
    if (transition.ending) {
        androidx.compose.runtime.LaunchedEffect(transition) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            ExpandOrigins.finishContent(transition)
        }
    }
    val origin = transition.origin
    val snapshot = origin.snapshot ?: return
    if (ButtonZoom.isCircle(origin)) {
        ButtonMorphCover(origin.bounds, { transition.full }, { transition.progress }, screenCornerPx, windowColor) { snapshot(Modifier.fillMaxSize()) }
        return
    }
    val density = LocalDensity.current
    val originWidth = with(density) { origin.bounds.width.toDp() }
    val originHeight = with(density) { origin.bounds.height.toDp() }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = transition.progress
            val window = ExpandOrigins.windowOf(origin, p, transition.full).bounds
            translationX = window.left
            translationY = window.top
            shape = ContentWindowShape(
                Size(window.width, window.height),
                lerp(origin.cornerPx, screenCornerPx, p),
            )
            clip = true
            // 整个卡面先合成再淡出，文字、玻璃高光与底色不会各自叠加变深。
            // 贴近原位时不淡出：原卡整段藏着，转场结束那一帧一次换手，不和原卡叠在一起
            alpha = 1f - ExpandOrigins.pageContentAlpha(p, transition.popping)
        }
            // 窗口比卡片长得快：多出来的部分用页面底色填上。
            // 回到原位附近时底色退掉，只剩卡面本身的材质，和原处的玻璃卡对得上
            .drawBehind {
                val t = ((transition.progress - 0.03f) / 0.17f).coerceIn(0f, 1f)
                val fill = t * t * (3f - 2f * t)
                if (fill > 0f) drawRect(windowColor.copy(alpha = windowColor.alpha * fill))
            },
    ) {
        Box(
            Modifier.size(originWidth, originHeight).graphicsLayer {
                val window = ExpandOrigins.windowOf(origin, transition.progress, transition.full).bounds
                val scale = window.width / origin.bounds.width.coerceAtLeast(1f)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationY = (window.height - origin.bounds.height * scale) / 2f
            },
        ) { snapshot(Modifier.fillMaxSize()) }
    }
}

/**
 * 圆钮变成页面时盖在上面的「按钮面板」：和页面同一个窗口（[ButtonZoom.apply]），
 * 底色从按钮的半透明长成实底，图标保持原来大小居中；页面内容在它下面浮上来后它再淡出。
 * 返回时倒着走，最后缩成的就是那颗按钮。
 *
 * @param from 按钮的矩形（与 [full] 同一坐标系）
 * @param icon 按钮里的图标，按按钮原尺寸画
 */
@Composable
fun ButtonMorphCover(
    from: Rect,
    full: () -> Size,
    progress: () -> Float,
    screenCornerPx: Float,
    panelColor: androidx.compose.ui.graphics.Color,
    icon: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val iconWidth = with(density) { from.width.toDp() }
    val iconHeight = with(density) { from.height.toDp() }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val u = progress()
            ButtonZoom.apply(this, from, full(), u, screenCornerPx)
            alpha = ButtonZoom.coverAlpha(u)
        }.drawBehind {
            drawRect(panelColor.copy(alpha = panelColor.alpha * ButtonZoom.coverFill(progress())))
        },
    ) {
        // 图标按原尺寸画：外层把整页缩到窗口大小，这里反向缩回去，并放在窗口（页面左上那一块）正中
        Box(
            Modifier.size(iconWidth, iconHeight).graphicsLayer {
                val size = full()
                if (size.width <= 0f) return@graphicsLayer
                val window = ButtonZoom.window(from, size, progress())
                val s = maxOf(window.width / size.width, window.height / size.height).coerceIn(0.01f, 1f)
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                scaleX = 1f / s
                scaleY = 1f / s
                translationX = window.width / (2f * s) - from.width / 2f
                translationY = window.height / (2f * s) - from.height / 2f
            },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { icon() }
    }
}

private class ContentWindowShape(private val window: Size, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, window.width, window.height), CornerRadius(radius)))
}
