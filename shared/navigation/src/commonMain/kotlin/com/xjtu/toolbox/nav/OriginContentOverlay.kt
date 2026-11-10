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
        ButtonMorphCover(
            origin.bounds, { transition.full }, { transition.progress }, screenCornerPx, windowColor,
            restFill = origin.restFill, restShadow = origin.restShadow,
            gone = { transition.ending },
        ) { snapshot(Modifier.fillMaxSize()) }
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
            // 贴近原位时不淡出：原卡整段藏着，转场结束那一帧一次换手，不和原卡叠在一起。
            // 结束后叠加层还留两帧（防空帧），这两帧必须不画：原卡已经放出来了，两张半透明玻璃卡叠着会亮一下。
            // 「藏叠加层」和「放原卡」都在绘制阶段读同一个 ending，同一帧生效
            alpha = if (transition.ending) 0f else 1f - ExpandOrigins.pageContentAlpha(p, transition.popping)
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
    /** 按钮自己的底色：贴近按钮的那一段从它渐变到 [panelColor]，落地时和真按钮一致。null = 首页玻璃圆钮的画法。 */
    restFill: androidx.compose.ui.graphics.Color? = null,
    /** 按钮本身没有投影时为 false：贴近按钮时投影收掉，不在落地那一帧多出一圈阴影。 */
    restShadow: Boolean = true,
    /** 转场已结束、真按钮已经放出来：面板这一帧起不画，免得和真按钮叠一帧（绘制阶段读）。 */
    gone: () -> Boolean = { false },
    icon: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val iconWidth = with(density) { from.width.toDp() }
    val iconHeight = with(density) { from.height.toDp() }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val u = progress()
            ButtonZoom.apply(this, from, full(), u, screenCornerPx)
            // 按钮本身没投影：贴近按钮时面板还是半透明的，再小的投影也会透过半透明内容把整颗按钮压暗一层，
            // 落地那帧一归零就是一跳。只在面板已经铺成不透明底色之后（进度过了交接段）才带投影
            if (!restShadow) shadowElevation *= opaquePanel(u)
            alpha = if (gone()) 0f else ButtonZoom.coverAlpha(u)
        }.drawBehind {
            val u = progress()
            val fill = if (restFill != null) {
                // 全透明的起点按面板色的透明版算：Color.Transparent 是透明的黑，直接插值会在中段压出一层灰
                val start = if (restFill.alpha == 0f) panelColor.copy(alpha = 0f) else restFill
                androidx.compose.ui.graphics.lerp(start, panelColor, nearButton(u))
            } else {
                panelColor.copy(alpha = panelColor.alpha * ButtonZoom.coverFill(u))
            }
            drawRect(fill)
        },
    ) {
        // 两层同一份按钮画面，按「离按钮多远」整块交接（和底色同一条进度）：
        // 贴近按钮时按钮铺满窗口，窗口里只有它，没有一圈露出的面板底色；
        // 离开按钮后是面板上原尺寸的按钮，当作面板上的图标
        ButtonLayer(iconWidth, iconHeight, from, full, progress, fillWindow = true, icon)
        ButtonLayer(iconWidth, iconHeight, from, full, progress, fillWindow = false, icon)
    }
}

/** 按钮画面放在窗口（页面左上那一块）正中：外层把整页缩到窗口大小，这里反向缩回去。 */
@Composable
private fun ButtonLayer(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    from: Rect,
    full: () -> Size,
    progress: () -> Float,
    fillWindow: Boolean,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier.size(width, height).graphicsLayer {
            val size = full()
            if (size.width <= 0f) return@graphicsLayer
            val u = progress()
            val window = ButtonZoom.window(from, size, u)
            val s = maxOf(window.width / size.width, window.height / size.height).coerceIn(0.01f, 1f)
            // 铺满：按窗口长边放大，四周不留缝（多出的部分由外层的窗口形状裁掉）
            val grow = if (fillWindow) maxOf(window.width / from.width, window.height / from.height, 1f) else 1f
            val k = nearButton(u)
            alpha = if (fillWindow) 1f - k else k
            transformOrigin = TransformOrigin(0.5f, 0.5f)
            scaleX = grow / s
            scaleY = grow / s
            translationX = window.width / (2f * s) - from.width / 2f
            translationY = window.height / (2f * s) - from.height / 2f
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { icon() }
}

/** 面板已经铺成不透明底色的程度：底色在 0–12% 铺满，之后才开始有投影。 */
private fun opaquePanel(u: Float): Float {
    val t = ((u - 0.12f) / 0.2f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 离按钮多远：0 = 就是按钮，展开到 12% 时到 1（和 [ButtonZoom.coverFill] 同一段）。 */
private fun nearButton(u: Float): Float {
    val t = (u / 0.12f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private class ContentWindowShape(private val window: Size, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, window.width, window.height), CornerRadius(radius)))
}
