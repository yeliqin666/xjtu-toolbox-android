package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.ceil
import kotlin.math.min

/**
 * **不采样版的液态玻璃面**：与 `drawBackdrop` 同一组参数、同一圈光影，只是不经过它的离屏层。
 *
 * 为什么不能干脆给 `drawBackdrop` 传一个空的采样源（库里有 `emptyBackdrop()`）：
 * `DrawBackdropNode` 不管有没有内容可采，**每帧都会 record + draw 一层卡片大小的离屏层**
 * （源为空也照录），卡片自己的内容还一直被放在 `CompositingStrategy.Offscreen` 的层里。
 * 也就是说「不采样」只省掉了采样那一趟与 `blur` / `lens` 两个 render effect，离屏层一分没省。
 * 而这一档（纯色 / 渐变背景，或壁纸已被模糊程度糊平）存在的理由**就是省性能**，
 * 省掉采样却留着离屏层等于白搭——所以这里不调 `drawBackdrop`，全靠直接画。
 *
 * 直接画**不等于少画**（2026-11 维护者原话：「我只是不要做采样，其他效果我都要」）。
 * `drawBackdrop` 那一路的观感由三样东西组成，这里三样都在、参数全部从库对象上读
 * （不在这里另存一份数字，库改默认值时两边一起变）：
 *
 * 1. **轮廓高光**（[highlight]）——最容易被漏掉、也是最像「玻璃」的那一层。库里画的是一条
 *    被**方向光**调制的描边：`intensity = pow(abs(dot(外法线, 光向)), falloff)`
 *    （公式见 [GlassRimMath]），所以四条直边等亮、四个圆角两亮两隐。这里按同一公式
 *    把一圈轮廓拆成「四段直边 + 四段圆角」分别上色：直边是同色描边，圆角用扫掠渐变
 *    （圆的法线方向就是圆心到该点的方向，扫掠角度天然等于法线角度，所以能逐度还原那条曲线），
 *    并保留库里的 [HighlightStyle.blendMode]（`Default` 是加色 `Plus`，光影是「叠亮」不是「盖住」）。
 *    描边宽度也照抄库里的算式 `ceil(width_px) * 2`：居中描边被 shape 裁掉外半边后，
 *    留下的内边正好 `ceil(width_px)` 像素，两条路的粗细才不会差一倍。
 * 2. **环境光投影**（[shadow]）—— [dropShadow]，半径 / 偏移 / 颜色 / 透明度全部取自 [Shadow]。
 * 3. **底色**—— 由调用方通过 [fill] 给（不采样时是那层「浅色更白 / 深色更深」的薄色）。
 *
 * 真正画不出来的只剩 [Highlight.blurRadius] 那半像素级的描边模糊（库里走 `MaskFilter`，
 * 平台专有 API，commonMain 里没有）：0.5dp 的细边上看不出来。
 * **没有**丢掉 `BlendMode.Plus`——直接画也能按 [BlendMode] 上色，不用离屏层（见 `Band`）。
 *
 * 全部走 `drawWithCache`：几何只跟尺寸 / 形状 / 高光参数有关，算一次，之后每帧照着画。
 */
fun Modifier.staticGlassSurface(
    highlight: Highlight,
    shadow: Shadow,
    shape: Shape,
    fill: Color,
): Modifier = this
    .dropShadow(shape) {
        // ShadowScope 继承自 Density：radius / offset 要像素 Float
        radius = shadow.radius.toPx()
        offset = Offset(shadow.offset.x.toPx(), shadow.offset.y.toPx())
        color = shadow.color
        alpha = shadow.alpha
    }
    .clip(shape)
    .background(fill, shape)
    .drawWithCache {
        // 高光的几何在 cache 块里算一次（尺寸 / 形状 / 高光参数变了才重建），
        // 之后每帧只把几段描边照着画一遍，不碰离屏层
        val rim = glassRim(
            highlight = highlight,
            shape = shape,
            size = size,
            layoutDirection = layoutDirection,
            density = this,
        )
        onDrawWithContent {
            drawContent()
            // 库里 Highlight 那层压在内容**上面**（`HighlightNode.draw` 先 drawContent 再画描边），
            // 这里保持同一顺序：图标与文字在细边上被叠亮一点，两条路才看不出换过材质
            rim?.draw(this)
        }
    }

/** 一圈轮廓高光：几段描边 + 一个公共的描边样式 / 混合模式 / 总透明度。 */
private class GlassRim(
    private val stroke: Stroke,
    private val blendMode: BlendMode,
    private val alpha: Float,
    private val bands: List<Band>,
) {
    fun draw(scope: DrawScope) {
        bands.forEach { it.draw(scope, stroke, blendMode, alpha) }
    }
}

/**
 * 轮廓的一段。
 *
 * 之所以能「不靠离屏层也加上 `BlendMode.Plus`」：`DrawScope` 的每个绘制函数都带
 * `blendMode` 参数，加色混合直接落到这一段描边上就行；库里要离屏层是为了让
 * 模糊 / 折射那一整套 render effect 有一个可采的层，不是为了混合模式。
 */
private sealed interface Band {
    fun draw(scope: DrawScope, stroke: Stroke, blendMode: BlendMode, alpha: Float)

    /** 一条直边（顶 / 右 / 底 / 左）：法线是常量，所以整段同一个强度。 */
    class Edge(
        private val from: Offset,
        private val to: Offset,
        private val color: Color,
    ) : Band {
        override fun draw(scope: DrawScope, stroke: Stroke, blendMode: BlendMode, alpha: Float) =
            with(scope) {
                drawLine(
                    brush = SolidColor(color),
                    start = from,
                    end = to,
                    strokeWidth = stroke.width,
                    // 平头：直边与圆角段在切点处「直着剪齐」，两段的描边正好接上，抗锯齿覆盖率
                    // 相加约等于满覆盖，看不出接缝。**不要用圆头**——圆头会多探进圆角一段，
                    // 那一小段被画两遍，八个接点各亮成一粒小珠子
                    cap = StrokeCap.Butt,
                    alpha = alpha,
                    blendMode = blendMode,
                )
            }
    }

    /** 一个圆角（90° 弧）：法线就是圆心到该点的方向，用扫掠渐变逐度还原强度曲线。 */
    class Corner(
        private val center: Offset,
        private val radius: Float,
        private val startAngle: Float,
        private val brush: Brush,
    ) : Band {
        override fun draw(scope: DrawScope, stroke: Stroke, blendMode: BlendMode, alpha: Float) =
            with(scope) {
                drawArc(
                    brush = brush,
                    startAngle = startAngle,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    alpha = alpha,
                    style = stroke,
                    blendMode = blendMode,
                )
            }
    }

    /**
     * 兜底：形状不是圆角矩形（`Outline.Generic`，随手画的路径）时没有「每条边的法线」可言，
     * 只能一圈同色。库在没有 runtime shader 的设备上也是这个退路。
     */
    class Whole(
        private val outline: Outline,
        private val color: Color,
    ) : Band {
        override fun draw(scope: DrawScope, stroke: Stroke, blendMode: BlendMode, alpha: Float) =
            with(scope) {
                drawOutline(outline, color = color, alpha = alpha, style = stroke, blendMode = blendMode)
            }
    }
}

/**
 * 把一圈轮廓高光拆成可画的几段；高光完全透明（或宽度为 0）时返回 null，一个像素都不画。
 *
 * 几何与光向的约定（三个地方必须一致，任一改了都要跟着改）：
 * - 屏幕坐标系 y 向下，所以「屏幕度」0 = 向右、90 = 向下、180 = 向左、270 = 向上；
 * - `HighlightStyle.Default.angle` 就是这个「屏幕度」，库里 shader 也是拿 `(cos, sin)` 当光向；
 * - `drawArc` 的 `startAngle` 与 `Brush.sweepGradient` 的色标都是从 +x 起、顺时针为正。
 */
private fun glassRim(
    highlight: Highlight,
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): GlassRim? {
    val style = highlight.style
    if (highlight.alpha <= 0f) return null

    // 只有 `HighlightStyle.Default` 有方向光那套参数（angle / falloff）；
    // Plain / Ambient 以及将来别的样式在库里没有 shader，一圈同色
    val light = style as? HighlightStyle.Default
    // 亮度基准与库里**逐字对齐**：`Default` 走 runtime shader，而 shader 返回的是
    // `color * intensity`、其中 color 的 alpha 被强制成 1
    // （`setColorUniform("color", color.copy(alpha = 1f))`）——所以这一档的亮度上限只有
    // `Highlight.alpha`，`style.color.alpha` 根本不参与。照抄库里的默认值去乘一下
    // `style.color.alpha` 会让不采样版比采样版暗一半，两块玻璃一眼就分得出来。
    // Plain / Ambient 没有 shader，照旧用 `style.color` 自己的 alpha。
    val rimColor = if (light != null) style.color.copy(alpha = 1f) else style.color
    if (rimColor.alpha <= 0f) return null

    // 与库里 `HighlightNode.configurePaint` 同一个算式：`ceil(width_px) * 2` 的居中描边
    // 被 shape 裁掉外半边，留下的内边正好 `ceil(width_px)` 像素
    val widthPx = with(density) { highlight.width.toPx() }.coerceAtMost(size.minDimension / 2f)
    val strokeWidth = ceil(widthPx) * 2f
    if (strokeWidth <= 0f) return null
    val stroke = Stroke(width = strokeWidth)

    val outline = shape.createOutline(size, layoutDirection, density)
    val roundRect = when (outline) {
        is Outline.Rounded -> outline.roundRect
        is Outline.Rectangle -> RoundRect(
            outline.rect.left,
            outline.rect.top,
            outline.rect.right,
            outline.rect.bottom,
        )
        is Outline.Generic -> return GlassRim(
            stroke = stroke,
            blendMode = style.blendMode,
            alpha = highlight.alpha,
            bands = listOf(Band.Whole(outline, rimColor)),
        )
    }

    // 圆角半径按「短边的一半」截断：圆饼（半径 = 短边一半）与胶囊（= 高度一半）都是这么算的，
    // 也免得拿到百分比修出来的超大值
    val limit = size.minDimension / 2f
    fun radiusOf(corner: CornerRadius): Float =
        min(min(corner.x, corner.y), limit).coerceAtLeast(0f)

    val tl = radiusOf(roundRect.topLeftCornerRadius)
    val tr = radiusOf(roundRect.topRightCornerRadius)
    val br = radiusOf(roundRect.bottomRightCornerRadius)
    val bl = radiusOf(roundRect.bottomLeftCornerRadius)

    val stops = light?.let { sweepStops(it, rimColor) }

    val left = roundRect.left
    val top = roundRect.top
    val right = roundRect.right
    val bottom = roundRect.bottom
    val bands = ArrayList<Band>(8)

    fun edge(from: Offset, to: Offset, normalDegrees: Float) {
        // 圆饼 / 胶囊上那条「直边」会缩成 0 长，没有画的必要
        if ((to - from).getDistance() < MIN_LENGTH_PX) return
        val k = light?.let { GlassRimMath.intensity(normalDegrees, it.angle, it.falloff) } ?: 1f
        bands += Band.Edge(from, to, rimColor.shadedBy(k))
    }

    fun corner(center: Offset, radius: Float, startDegrees: Float) {
        if (radius < MIN_RADIUS_PX) return
        val brush = stops?.let { Brush.sweepGradient(*it, center = center) }
            ?: SolidColor(rimColor)
        bands += Band.Corner(center, radius, startDegrees, brush)
    }

    // 四条直边的外法线：上 270、右 0、下 90、左 180（屏幕度）
    edge(Offset(left + tl, top), Offset(right - tr, top), 270f)
    corner(Offset(right - tr, top + tr), tr, 270f)
    edge(Offset(right, top + tr), Offset(right, bottom - br), 0f)
    corner(Offset(right - br, bottom - br), br, 0f)
    edge(Offset(right - br, bottom), Offset(left + bl, bottom), 90f)
    corner(Offset(left + bl, bottom - bl), bl, 90f)
    edge(Offset(left, bottom - bl), Offset(left, top + tl), 180f)
    corner(Offset(left + tl, top + tl), tl, 180f)

    return GlassRim(
        stroke = stroke,
        blendMode = style.blendMode,
        alpha = highlight.alpha,
        bands = bands,
    )
}

/**
 * 圆角那段扫掠渐变的色标：整圈按 [RIM_STOP_COUNT] 等分，每份取该角度的强度。
 *
 * 只做成一张表、四个圆角各配一个圆心就够——扫掠渐变的角度与「圆的法线方向」是同一个量，
 * 所以这张表与圆角的象限无关。表本身按「颜色 + 角度 + 衰减」缓存：一屏上几块卡片的参数
 * 一模一样，没必要每块各算一份（这是唯一还能在这一档里省下来的一处分配）。
 */
private fun sweepStops(style: HighlightStyle.Default, color: Color): Array<Pair<Float, Color>> {
    val key = RimStopsKey(color, style.angle, style.falloff)
    rimStopsCache[key]?.let { return it }
    val stops: Array<Pair<Float, Color>> = Array(RIM_STOP_COUNT + 1) { i ->
        val fraction = i.toFloat() / RIM_STOP_COUNT
        val k = GlassRimMath.intensity(fraction * 360f, style.angle, style.falloff)
        Pair(fraction, color.shadedBy(k))
    }
    // 键的组合就那么几种（每套主题色 + 一个光向）；真被喂满也只是几张几十项的数组
    if (rimStopsCache.size >= MAX_RIM_STOPS_CACHE) rimStopsCache.clear()
    rimStopsCache[key] = stops
    return stops
}

private data class RimStopsKey(val color: Color, val angle: Float, val falloff: Float)

private val rimStopsCache = HashMap<RimStopsKey, Array<Pair<Float, Color>>>()

private const val MAX_RIM_STOPS_CACHE = 8

/**
 * 整圈色标的段数：36 段 = 每 10°。
 *
 * 45° 的缺省光向正好落在格点上，线性插值造成的亮度偏差 < 0.4%；换个光角度（用户自己设）时
 * 最坏约 3%，落在一条 0.5dp 的细边上分辨不出来。再多就是拿每帧的 shader 参数换看不见的精度。
 */
private const val RIM_STOP_COUNT = 36

/** 按强度把描边色调淡（强度 0..1，乘在 alpha 上——加色混合下就是这个颜色「有多亮」）。 */
private fun Color.shadedBy(intensity: Float): Color =
    if (intensity >= 1f) this else copy(alpha = alpha * intensity)

/** 比这更小的圆角直接当直角（半像素以下，画出来只是一团糊）。 */
private const val MIN_RADIUS_PX = 0.5f

/** 比这更短的直边不画（圆饼 / 胶囊的「直边」长度就是 0）。 */
private const val MIN_LENGTH_PX = 0.01f
