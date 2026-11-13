package com.xjtu.toolbox.ui.glass.coui

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiCapsuleShape
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** 低于这个不透明度就当完全透明：材质、投影和内容都画不出来，不该再录制/绘制。 */
private const val InvisibleAlpha = 0.001f

data class CouiFloatingMaterial(
    val firstColor: Color,
    val firstBlendMode: BlendMode,
    val secondColor: Color,
    val edgeAlpha: Float,
    val innerGlowAlpha: Float,
    val secondBlendMode: BlendMode = BlendMode.LUMINOSITY,
    val blurRadius: Float = 25f,
    val strokeType: Int = 1,
    val index: Int = 2,
    val extension: Int = 0,
    val lightColor: Color = Color.White,
    val themeTint: Color = Color.Transparent,
    val edgeWidthDp: Float = 2.5f,
    val innerGlowScale: Float = 1f,
)

/** 长度均为像素; lightZ=0 保留原厂 renderer 默认高度, smoothCorner 对应底栏的 fullsmooth Outline。 */
data class CouiFloatingShadow(
    val elevation: Float,
    val color: Color,
    val lightY: Float,
    val lightZ: Float,
    val lightRadius: Float,
    val causticColor: Color,
    val layout: Rect,
    val sdf: Float,
    val power: Float = 0.6f,
    val powerMix: Float = 0.6f,
    val speed: Float = 18f,
    val smoothCorner: Boolean = false,
)

/** 创建时直接验证原生片段能否编译; 宿主负责报告失败, 不在绘制时吞没错误。 */
class CouiFloatingRenderer private constructor(internal val surfaceCount: Int) {
    constructor() : this(3)

    internal val standalone = surfaceCount == 1
    internal val shaders = if (standalone) listOf(RuntimeShader(couiFloatingStandaloneShader()))
        else listOf(RuntimeShader(couiFloatingShader(1)), RuntimeShader(couiFloatingShader(2)))
    internal val causticShaders = List(surfaceCount) { RuntimeShader(CouiCausticShader) }
    internal val shadowRenderer = CouiShadowRenderer(surfaceCount)
    internal val destinationEffect = RenderEffect.createRuntimeShaderEffect(
        RuntimeShader("uniform shader image; half4 main(float2 p) { return image.eval(p); }"), "image",
    )

    companion object {
        fun standalone(): CouiFloatingRenderer = CouiFloatingRenderer(1)
        /** 搜索框与取消按钮的真实双主体链；不创建第三个布局或内容节点。 */
        fun pair(): CouiFloatingRenderer = CouiFloatingRenderer(2)
    }
}

/**
 * 单主体 BaseShader; 不绑定底栏, 不创建融球资源。
 * foregroundComposition 为 true 时材质和光晕仅输出前景; 共用 backdrop, 源须覆盖不透明 SDR 页面。
 * beforeContent 在材质之后、内容之前直接混合到目标；接收内容的缓存裁剪路径，调用方不得修改。
 * 回调坐标仍为节点本地坐标，变换及裁剪由调用方提供。
 * renderer、contentLayer 与返回的 Modifier 绑定一个绘制节点，不跨节点复用。
 */
@Composable
fun Modifier.couiFloatingSurface(
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    material: CouiFloatingMaterial,
    shadow: CouiFloatingShadow,
    shape: Shape,
    contentLayer: GraphicsLayer,
    contourGlowEnabled: Boolean,
    foregroundComposition: Boolean = false,
    beforeContent: (DrawScope.(Path) -> Unit)? = null,
    surface: () -> CouiFloatingSurface,
): Modifier = couiFloatingSurface(backdrop, renderer, material, shadow, shape, contentLayer,
    contourGlowEnabled, foregroundComposition, beforeContent, useMenuLightEdge = false, surface = surface)

@Composable
internal fun Modifier.couiFloatingSurface(
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    material: CouiFloatingMaterial,
    shadow: CouiFloatingShadow,
    shape: Shape,
    contentLayer: GraphicsLayer,
    contourGlowEnabled: Boolean,
    foregroundComposition: Boolean,
    beforeContent: (DrawScope.(Path) -> Unit)?,
    useMenuLightEdge: Boolean,
    surface: () -> CouiFloatingSurface,
): Modifier {
    require(renderer.standalone)
    return floatingSurfaces(backdrop, renderer, listOf(material), listOf(shadow), listOf(shape),
        listOf(contentLayer), contourGlowEnabled, foregroundComposition,
        useMenuLightEdge = useMenuLightEdge,
        beforeContent = beforeContent?.let { draw -> { _, path -> draw(path) } }) { listOf(surface()) }
}

/** 三主体在同一节点按 Z 排序，同 Z 保留宿主子项顺序；资源与返回的 Modifier 不跨节点复用。菜单 index 1，两侧附件 index 2。 */
@Composable
fun Modifier.couiFloatingSurfaces(
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    materials: List<CouiFloatingMaterial>,
    shadows: List<CouiFloatingShadow>,
    shapes: List<Shape>,
    contentLayers: List<GraphicsLayer>,
    contourGlowEnabled: Boolean,
    surfaces: () -> List<CouiFloatingSurface>,
): Modifier {
    require(renderer.surfaceCount == 3)
    require(materials.size == 3 && shadows.size == 3 && shapes.size == 3 && contentLayers.size == 3)
    require(materials.map { it.index }.sorted() == listOf(1, 2, 2))
    return floatingSurfaces(backdrop, renderer, materials, shadows, shapes, contentLayers, contourGlowEnabled, surfaces = surfaces)
}

/** 双主体的采样、融球和光效；顺序与 material.index 必须分别为 1、2。 */
@Composable
fun Modifier.couiFloatingPair(
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    materials: List<CouiFloatingMaterial>,
    shadows: List<CouiFloatingShadow>,
    shapes: List<Shape>,
    contentLayers: List<GraphicsLayer>,
    contourGlowEnabled: Boolean,
    beforeContent: (DrawScope.(Int, Path) -> Unit)? = null,
    drawOrder: List<Int> = listOf(0, 1),
    foregroundComposition: Boolean = false,
    surfaces: () -> List<CouiFloatingSurface>,
): Modifier {
    require(renderer.surfaceCount == 2)
    require(materials.size == 2 && shadows.size == 2 && shapes.size == 2 && contentLayers.size == 2)
    require(materials.map { it.index } == listOf(1, 2))
    require(drawOrder.sorted() == listOf(0, 1))
    return floatingSurfaces(backdrop, renderer, materials, shadows, shapes, contentLayers, contourGlowEnabled,
        foregroundComposition = foregroundComposition,
        beforeContent = beforeContent, drawOrder = drawOrder, surfaces = surfaces)
}

@Composable
private fun Modifier.floatingSurfaces(
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    materials: List<CouiFloatingMaterial>,
    shadows: List<CouiFloatingShadow>,
    shapes: List<Shape>,
    contentLayers: List<GraphicsLayer>,
    contourGlowEnabled: Boolean,
    foregroundComposition: Boolean = false,
    useMenuLightEdge: Boolean = false,
    beforeContent: (DrawScope.(Int, Path) -> Unit)? = null,
    drawOrder: List<Int>? = null,
    surfaces: () -> List<CouiFloatingSurface>,
): Modifier {
    val recordingDensity = LocalDensity.current
    val density = recordingDensity.density
    val view = LocalView.current
    val shadowLayers = List(renderer.surfaceCount) { rememberGraphicsLayer() }
    val causticLayers = List(renderer.surfaceCount) { rememberGraphicsLayer() }
    val materialLayers = List(renderer.surfaceCount) { rememberGraphicsLayer() }
    // Capsule 轮廓只依赖像素尺寸; 每个槽位仅保留当前轮廓, 不积累动画历史。
    val capsuleOutlines = remember(renderer) {
        arrayOfNulls<Pair<Size, Outline>>(renderer.surfaceCount)
    }
    val materialInputs = remember(materials) {
        materials.map { material ->
            // 顶栏默认半径为零, 仍须保留采样及两层混色。
            val blur = if (material.blurRadius == 0f) {
                RenderEffect.createOffsetEffect(0f, 0f)
            } else {
                RenderEffect.createBlurEffect(material.blurRadius, material.blurRadius, Shader.TileMode.MIRROR)
            }
            val color1 = RenderEffect.createColorFilterEffect(BlendModeColorFilter(material.firstColor.toArgb(), material.firstBlendMode), blur)
            RenderEffect.createColorFilterEffect(BlendModeColorFilter(material.secondColor.toArgb(), material.secondBlendMode), color1)
        }
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    return onGloballyPositioned { coordinates = it }.drawWithCache {
        val target = coordinates
        val nodes = surfaces()
        require(nodes.size == renderer.surfaceCount)
        if (target?.isAttached == true && size.width > 0f && size.height > 0f &&
            // 不可见的主体不参与绘制，也不该用它的尺寸卡住整条链：
            // 搜索栏收起取消胶囊时那一面可以是 0 宽，但输入胶囊本身还要正常画。
            nodes.all { !it.visible || (it.bounds.width > 0f && it.bounds.height > 0f) }
        ) {
            val viewport = target.localBoundingBoxOf(target.findRootCoordinates(), false)
            val commands = mutableListOf<GraphicsLayer>()
            val patches = mutableListOf<FloatingPatch>()
            fun appendPatch(layer: GraphicsLayer, crop: Rect,
                blendMode: ComposeBlendMode = ComposeBlendMode.Src,
                includePrefix: Boolean = true, effect: (Rect) -> RenderEffect) {
                val clipped = crop.intersect(viewport)
                if (clipped.width <= 0f || clipped.height <= 0f) return
                val bounds = Rect(floor(clipped.left), floor(clipped.top), ceil(clipped.right), ceil(clipped.bottom))
                layer.topLeft = IntOffset(bounds.left.toInt(), bounds.top.toInt())
                layer.clip = true
                // 完整 patch 用 Src; 分离后的透明前景只使用 SrcOver。
                layer.blendMode = blendMode
                layer.renderEffect = effect(bounds).asComposeRenderEffect()
                patches.add(FloatingPatch(layer, bounds, if (includePrefix) commands.toList() else emptyList()))
                commands.add(layer)
            }
            val outlines = nodes.mapIndexed { index, node ->
                val shape = shapes[index]
                val outlineSize = node.bounds.size
                if (shape === CouiCapsuleShape) {
                    val cached = capsuleOutlines[index]
                    if (cached?.first == outlineSize) {
                        cached.second
                    } else {
                        shape.createOutline(outlineSize, layoutDirection, this).also {
                            capsuleOutlines[index] = outlineSize to it
                        }
                    }
                } else {
                    capsuleOutlines[index] = null
                    shape.createOutline(outlineSize, layoutDirection, this)
                }
            }
            val beforeClips = if (beforeContent != null) outlines.map { outline ->
                if (outline is Outline.Generic) outline.path else Path().apply { addOutline(outline) }
            } else emptyList()
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            checkNotNull(view.display).getRealMetrics(metrics)
            // PJZ110 renderer 的默认资源与显示尺寸校准; 不继承其他 OEM 的不同光照主题。
            val defaultLightZ = 500f * density * (2f + min(metrics.widthPixels, metrics.heightPixels) /
                (450f * metrics.density)) / 3f
            val windowOrigin = target.localToWindow(Offset.Zero)
            nodes.forEachIndexed { index, node ->
                if (!node.visible) return@forEachIndexed
                contentLayers[index].apply {
                    translationX = node.bounds.left
                    translationY = node.bounds.top
                    scaleX = node.scaleX
                    scaleY = node.scaleY
                    alpha = node.alpha
                }
                shadowLayers[index].apply {
                    translationX = node.bounds.center.x - node.bounds.width * node.scaleX * 0.5f
                    translationY = node.bounds.center.y - node.bounds.height * node.scaleY * 0.5f
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                    clip = false
                    // alpha 不触发按主体尺寸离屏, 避免收放时裁掉外部半影。
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                contentLayers[index].setOutline(outlines[index])
                contentLayers[index].clip = true
            }
            floatingDrawGroups(shadows.map { it.elevation }).forEach { indices ->
                // 全透明的浮层（收起动画的末尾、alpha 归零的按钮）整套都不画：
                // 阴影、光晕、材质补丁和内容层都录不出一像素，白跑采样与着色器。
                val drawn = indices.filter { nodes[it].visible && nodes[it].alpha > InvisibleAlpha }
                val group = if (drawOrder == null) drawn else drawn.sortedBy { drawOrder.indexOf(it) }
                group.forEach { index ->
                    commands.add(shadowLayers[index])
                    // 前景模式下, 原 Shader 在此阈值内严格输出透明, 无需录制背景或创建光晕图层。
                    // 完整 destination 模式仍需回填, 不能套用这一短路。
                    if (contourGlowEnabled && (!foregroundComposition ||
                            shadows[index].causticColor.alpha * nodes[index].alpha > 0.001f)) {
                        val node = nodes[index]
                        val shadow = shadows[index]
                        val optical = Rect(shadow.layout.left, shadow.layout.top,
                            node.bounds.width + shadow.layout.right, node.bounds.height + shadow.layout.bottom)
                        // 顶栏不回填背景; 默认路径仍保留完整 destination, 不改成 PLUS 光贡献。
                        appendPatch(causticLayers[index], node.mapRect(optical),
                            blendMode = if (foregroundComposition) ComposeBlendMode.SrcOver else ComposeBlendMode.Src) { bounds ->
                            renderer.causticShaders[index].apply {
                                setIntUniform("u_foregroundComposition", if (foregroundComposition) 1 else 0)
                                setFloatUniform("u_offset", bounds.left - node.bounds.left, bounds.top - node.bounds.top)
                                setFloatUniform("u_invScale", 1f / node.scaleX, 1f / node.scaleY)
                                setFloatUniform("u_commonArray", floatArrayOf(0f, 0f, 0f, 0f, node.bounds.width,
                                    node.bounds.height, node.resolvedCornerRadius, 0f, 0f, 0f, 0f, 0f))
                                setFloatUniform("u_pyrolysisArray", floatArrayOf(shadow.causticColor.red, shadow.causticColor.green,
                                    shadow.causticColor.blue, shadow.causticColor.alpha * node.alpha, shadow.layout.left, shadow.layout.top,
                                    shadow.layout.right, shadow.layout.bottom, shadow.sdf, shadow.power, shadow.powerMix,
                                    shadow.speed, 0.45f, 0.6f))
                            }.let { RenderEffect.createRuntimeShaderEffect(it, "u_originShader") }
                        }
                    }
                }
                group.forEach { index ->
                    val node = nodes[index]
                    val material = materials[index]
                    appendPatch(materialLayers[index], node.materialRect(material.extension),
                        blendMode = if (foregroundComposition) ComposeBlendMode.SrcOver else ComposeBlendMode.Src,
                        includePrefix = !foregroundComposition) { bounds ->
                        val height = bounds.height
                        val origin = bounds.topLeft
                        val ball = node.ball(height, origin)
                        val shader = renderer.shaders[if (renderer.standalone) 0 else material.index - 1]
                        val common = floatArrayOf(bounds.width * 0.5f, height * 0.5f, ball[0], ball[1], ball[2],
                            ball[3], ball[4], ball[5], ball[6], ball[7], height, 0f)
                        shader.setFloatUniform("u_size", bounds.width, height)
                        // 只淡出材质前景; 最后合成的 destination 必须保持原 alpha。
                        shader.setFloatUniform("u_alpha", node.alpha)
                        shader.setFloatUniform("uUseMenuLightEdge", if (useMenuLightEdge) 1f else 0f)
                        shader.setFloatUniform("u_commonArray", common)
                        if (renderer.standalone) {
                            shader.setFloatUniform("u_baseShapeCtxArray", floatingShapeContext(ball[2], ball[3], ball[5], ball[4]))
                        } else {
                            val activeBalls = floatingBallSlots(index, materials.map { it.index }).map { slot ->
                                if (nodes[slot].visible) nodes[slot].ball(height, origin) else FloatArray(8)
                            }
                            // Native Shader 的 influence 容量为 3；未注册槽位保持零，不产生绘制/内容节点。
                            val balls = List(3) { slot -> activeBalls.getOrNull(slot) ?: FloatArray(8) }
                            balls.forEachIndexed { i, linked ->
                                shader.setFloatUniform("u_ball${i + 1}Array", linked)
                                shader.setFloatUniform("u_ball${i + 1}ShapeCtxArray", floatingShapeContext(linked[2], linked[3], linked[5], linked[4]))
                            }
                            shader.setFloatUniform("u_preCmpArray12", floatingPair(balls[0], balls[1], 4f * density))
                            shader.setFloatUniform("u_preCmpArray13", floatingPair(balls[0], balls[2], 4f * density))
                            shader.setFloatUniform("u_preCmpArray23", floatingPair(balls[1], balls[2], 4f * density))
                        }
                        val edge = floatingEdge(ball, material.edgeWidthDp * density,
                            if (contourGlowEnabled) material.edgeAlpha else 0f, material.strokeType)
                        val glow = floatingInnerGlow(ball, if (contourGlowEnabled) material.innerGlowAlpha else 0f,
                            material.strokeType, material.innerGlowScale)
                        for (parameters in listOf(edge, glow)) {
                            parameters[0] = material.lightColor.red
                            parameters[1] = material.lightColor.green
                            parameters[2] = material.lightColor.blue
                        }
                        shader.setFloatUniform("u_edgeArray", edge)
                        shader.setFloatUniform("u_shadowArray", glow)
                        shader.setFloatUniform("u_shadowShapeCtxArray", floatingShadowShapeContext(ball, glow))
                        shader.setColorUniform("u_color", Color.Transparent.toArgb())
                        shader.setColorUniform("u_themeTint", material.themeTint.toArgb())
                        val effect = RenderEffect.createChainEffect(
                            RenderEffect.createRuntimeShaderEffect(shader, "u_originShader"), materialInputs[index],
                        )
                        // 分开材质前景与完整背景, 不让材质 patch 外沿覆盖实际 destination。
                        // shortcut: 采样源不含兄弟前景; 需严格对齐重叠光效时应补全 destination 绘制顺序。
                        if (foregroundComposition) effect else
                            RenderEffect.createBlendModeEffect(renderer.destinationEffect, effect, BlendMode.SRC_OVER)
                    }
                    commands.add(contentLayers[index])
                }
            }
            val drawCommands = commands.toList()
            onDrawWithContent {
                // 子项只录制透明前景; 真实输出遵守 shadow/caustic -> material/content 的 Z 分组顺序。
                drawContent()
                shadowLayers.forEachIndexed { index, layer ->
                    if (!nodes[index].visible || nodes[index].alpha <= InvisibleAlpha) return@forEachIndexed
                    val bounds = nodes[index].bounds
                    val node = nodes[index]
                    val recordSize = IntSize(ceil(bounds.width * node.scaleX).toInt().coerceAtLeast(1),
                        ceil(bounds.height * node.scaleY).toInt().coerceAtLeast(1))
                    // 纯投影不录制 drawContent; 显式 Density 避免重定向外层作用域后形成循环引用。
                    layer.record(density = recordingDensity, layoutDirection = layoutDirection, size = recordSize) {
                        drawIntoCanvas { canvas ->
                            val roundedRect = node.cornerRadius != null &&
                                node.resolvedCornerRadius < min(bounds.width, bounds.height) * 0.5f
                            renderer.shadowRenderer.draw(canvas.nativeCanvas, index, bounds.width, bounds.height,
                                node.scaleX, node.scaleY, windowOrigin.x + layer.translationX,
                                windowOrigin.y + layer.translationY, node.alpha, shadows[index].color.toArgb(),
                                shadows[index].elevation, shadows[index].lightY,
                                if (shadows[index].lightZ > 0f) shadows[index].lightZ else defaultLightZ,
                                shadows[index].lightRadius,
                                if (roundedRect) CouiShadowOutline.RoundRect
                                else if (shadows[index].smoothCorner) CouiShadowOutline.SmoothCapsule else CouiShadowOutline.Oval,
                                radius = node.resolvedCornerRadius, clipping = !roundedRect)
                        }
                    }
                }
                patches.forEach { patch ->
                    patch.layer.record(size = IntSize(patch.bounds.width.toInt(), patch.bounds.height.toInt())) {
                        translate(-patch.bounds.left, -patch.bounds.top) {
                            backdrop.draw(this, target)
                            patch.prefix.forEach { drawLayer(it) }
                        }
                    }
                }
                drawCommands.forEach { layer ->
                    if (beforeContent != null) {
                        val index = contentLayers.indexOf(layer)
                        if (index >= 0) beforeContent.invoke(this, index, beforeClips[index])
                    }
                    drawLayer(layer)
                }
            }
        } else {
            onDrawWithContent { drawContent() }
        }
    }
}

private data class FloatingPatch(val layer: GraphicsLayer, val bounds: Rect, val prefix: List<GraphicsLayer>)

private fun GraphicsLayer.setOutline(outline: Outline) {
    when (outline) {
        is Outline.Rectangle -> setRectOutline(outline.rect.topLeft, outline.rect.size)
        is Outline.Rounded -> setPathOutline(Path().apply { addRoundRect(outline.roundRect) })
        is Outline.Generic -> setPathOutline(outline.path)
    }
}
