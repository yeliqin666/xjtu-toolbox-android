package com.xjtu.toolbox.nav

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.reflect.KClass
import kotlin.math.roundToInt

/**
 * 首页格子 → 功能页的「从那一格放大出来」转场（plan2 PR V）。
 *
 * miuix-nav v1 不支持跨页面的共享元素，所以做法是：点格子时记下它在屏幕上的矩形和圆角，
 * 目的地的转场读这个矩形，让窗口从那一格长到全屏；页面等比缩放到刚好铺满窗口，
 * 先在那一格上淡入页面内容，再整体放大，所以看起来是那一格长成了页面，返回时再缩回那一格。
 * 转场是 `relativeDepth` 的纯函数，返回和系统预测式返回用的是同一个深度，
 * 所以同一个函数倒着走就是「缩回那一格」，而且跟手、可以反悔。
 *
 * 没有起点的（从全局搜索、深链、别的页面打开的）走和 miuix-nav 默认转场一样的平移。
 */
object ExpandOrigins {

    /** 起点：格子在根布局里的矩形（px）、圆角半径（px），以及返回时画在窗口里的源元素快照。 */
    data class Origin(
        val bounds: Rect,
        val cornerPx: Float,
        val snapshot: (@Composable (Modifier) -> Unit)? = null,
        /** 被点的那个元素：转场期间藏起来，飞走的是它自己，原地不留一份。 */
        val source: ExpandOriginSource? = null,
        /**
         * 圆钮自己的底色与有无投影（见 [ButtonMorphCover]）：按钮面板缩回按钮的最后一段渐变成它，
         * 落地那一帧和真按钮一模一样。null = 首页玻璃圆钮那种（页面底色半透明 + 投影）。
         */
        val restFill: androidx.compose.ui.graphics.Color? = null,
        val restShadow: Boolean = true,
    )

    /** 一次内容交接。进度仅在图层读，逐帧更新不重组源卡片。 */
    class ContentTransition(val origin: Origin, progress: Float, full: Size, val popping: Boolean) {
        var progress by mutableFloatStateOf(progress)
            internal set
        var full by mutableStateOf(full)
            internal set

        /** 转场已结束：原卡先放出来，叠加层再留两帧才撤（见 [finishContent]），中间不会有一帧两边都不画。 */
        var ending by mutableStateOf(false)
            internal set
    }

    /** 返回窗口：屏幕坐标里的矩形，以及窗口从格子长到全屏的进度（0 = 格子，1 = 全屏）。 */
    data class Window(val bounds: Rect, val grow: Float)

    private val contentState = mutableStateOf<ContentTransition?>(null)

    private val coveredFromOriginState = mutableStateOf(false)

    /**
     * 这个元素此刻正在转场里飞着。绘制阶段读。
     * 直接由进行中的转场推出来，不单独存状态：转场一结束自然恢复，不会一直藏着。
     */
    fun isHidden(source: ExpandOriginSource): Boolean {
        val transition = contentState.value ?: return false
        if (transition.origin.source !== source || transition.ending) return false
        // 有叠加层的（卡片、圆钮）：整段都「挪进叠加层」由它实时画（见 expandOriginSource），
        // 结束那一帧换回原位，前后是同一份画面。
        // 没有叠加层的：页面自己缩回原处并淡出，最后一小段露出原件
        return transition.origin.snapshot != null || transition.progress > SOURCE_HANDOFF
    }

    /** 紧贴主界面的那一页是从起点放大出来的：主界面被它盖住时才压暗（默认转场不压）。 */
    val coveredFromOrigin: State<Boolean> get() = coveredFromOriginState

    val contentTransition: State<ContentTransition?> get() = contentState

    internal fun updateContent(origin: Origin, progress: Float, full: Size, popping: Boolean) {
        val current = contentState.value
        if (current?.origin !== origin || current.popping != popping || current.ending) {
            contentState.value = ContentTransition(origin, progress, full, popping)
        } else {
            current.progress = progress
            current.full = full
        }
    }

    /**
     * 转场结束。不直接置空：这个调用常发生在绘制阶段，叠加层靠重组撤掉、原卡靠重绘放出来，
     * 两条路不保证同一帧生效，实测会空出一帧什么都没画（卡片闪一下）。
     * 所以先标 [ContentTransition.ending]：原卡立刻放出来，叠加层留两帧再由 [finishContent] 撤。
     */
    fun clearContent() {
        val current = contentState.value ?: return
        if (current.origin.snapshot == null) contentState.value = null else current.ending = true
    }

    /** 叠加层收尾：只撤自己那一次，期间若已开始新的转场就不动。 */
    internal fun finishContent(transition: ContentTransition) {
        if (contentState.value === transition) contentState.value = null
    }

    /**
     * 页面内容占多少（1 = 全是页面，0 = 全是卡片）。打开时到中央前后才交接成页面；
     * 返回时页面一开始缩就换回卡片，看到的是卡片飞回去。
     */
    fun pageContentAlpha(progress: Float, popping: Boolean = false): Float {
        val t = if (popping) ((progress - 0.40f) / 0.40f).coerceIn(0f, 1f)
        else ((progress - 0.26f) / 0.36f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 按和转场完全相同的公式算返回窗口，保证叠加层和页面裁剪严丝合缝。 */
    fun windowOf(origin: Origin, progress: Float, full: Size): Window =
        windowAt(origin, progress.coerceIn(0f, 1f), full)

    /** 窗口在长大进度 [grow] 时的矩形；[grow] 可以略大于 1（打开时的回弹）。 */
    internal fun windowAt(origin: Origin, grow: Float, full: Size): Window {
        return Window(bounds = ButtonZoom.window(origin.bounds, full, grow), grow = grow)
    }

    private var armed: Pair<String, Origin>? = null

    /**
     * 按页面类型记。转场是按类型注册的（`entry<T>(transition = …)`），拿不到具体的路由值；
     * 同一类型同时在栈里出现两份的情况（比如两个不同课程的思源学堂）实际不会从首页格子触发。
     */
    private val byType = mutableMapOf<KClass<out AppRoute>, Origin>()

    /** 格子被点时调用（[routeId] 即 [AppRoute.id]）；随后那一次跳转如果正好去这个路由，就用这个起点。 */
    fun arm(routeId: String, origin: Origin) {
        armed = routeId to origin
    }

    /**
     * [AppNavigator] 每次压栈时调用：只有紧接在格子点击之后、去同一个路由的那一次才用起点，
     * 否则把这个类型的旧起点清掉，免得从搜索进同一页时从上次那一格飞出来。
     */
    fun onPush(route: AppRoute, aboveRoot: Boolean) {
        clearContent()
        val a = armed
        armed = null
        val fromOrigin = a != null && a.first == route.id
        if (fromOrigin) byType[route::class] = a.second
        else byType.remove(route::class)
        if (aboveRoot) coveredFromOriginState.value = fromOrigin
    }

    /**
     * 首页的格子已经不在原来的位置了（比如切到了别的 tab），返回时不能再往那儿缩。
     * [com.xjtu.toolbox.main.AppRouter] 在一路退回首页并切 tab 时调用。
     */
    fun clear() {
        armed = null
        byType.clear()
        contentState.value = null
        coveredFromOriginState.value = false
    }

    internal fun originOf(type: KClass<out AppRoute>): Origin? = byType[type]
}

/**
 * 顶栏圆钮 ↔ 整页（容器变形）：窗口从按钮的矩形一路长到全屏，页面等比缩放铺满窗口、按窗口裁切；
 * 倒着走就缩回按钮。开头一小段淡入，让按钮溶进页面。
 */
object ButtonZoom {
    private const val FADE_END = 0.05f

    /** 页面放大时，底下那页退到的比例。 */
    const val RECEDE_SCALE = 0.9f

    /**
     * 底下那页此刻该退多深（压暗、缩小都按它）。导航库给的「被盖住深度」在返回快结束时会停在 0.2 上下，
     * 转场一结束才跳回 0——整页一下子变亮、尺寸回弹，就是返回最后闪的那一下。
     * 有从起点放大的转场在跑时改跟它的进度走：飞回的那一块落地时，底下这页正好退回原样。
     */
    fun recedeDepth(navDepth: Float): Float {
        val t = ExpandOrigins.contentTransition.value ?: return navDepth
        return if (t.ending) 0f else minOf(navDepth, t.progress)
    }

    /** 底下那页压暗的最大程度。 */
    const val RECEDE_DIM = 0.42f

    /** 接近匀速的时钟：节奏都在 [window] 的分段曲线里，这里不再叠一层减速。 */
    val Easing: Easing = CubicBezierEasing(0.3f, 0.15f, 0.6f, 0.95f)

    /** 起点是不是一颗圆（圆角达到短边一半）：圆钮和卡片共用几何，内容交接各自处理。 */
    fun isCircle(origin: ExpandOrigins.Origin): Boolean =
        origin.cornerPx * 2f >= origin.bounds.minDimension * 0.95f

    /**
     * 和弹出菜单同一种节奏，分两段：前段边移边放大，飞到屏幕中央；
     * 后段位置已经停稳，原地继续放大铺满。尺寸是两段叠加，交接处速度连续，高度比宽度晚一点撑开。
     */
    fun window(from: Rect, full: Size, u: Float, risesFromBottom: Boolean = false): Rect {
        val p = u.coerceIn(0f, 1f)
        // 起步速度为 0：卡片先以原样动起来，不会一下子跳大
        val flight = smoothStep(p / FLIGHT_END)
        // 飞行段只长一点，看得清是一小块在移动；铺满主要靠后段
        val growW = 0.22f * flight + 0.78f * smoothStep((p - 0.38f) / 0.62f)
        val growH = 0.16f * flight + 0.84f * smoothStep((p - 0.34f) / 0.66f)
        val cx = lerp(from.center.x, full.width / 2f, flight)
        // 底栏盖在页面上层：先升到它上方、屏幕偏下处，看得见离开了底栏，再放大回到全屏中心
        val cy = if (risesFromBottom) {
            lerp(
                lerp(from.center.y, full.height * BAR_FLIGHT_TARGET, flight),
                full.height / 2f,
                smoothStep((p - 0.38f) / 0.62f),
            )
        } else {
            lerp(from.center.y, full.height / 2f, flight)
        }
        val w = lerp(from.width, full.width, growW)
        val h = lerp(from.height, full.height, growH)
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /**
     * 圆钮变成页面时，页面内容的透明度：先是按钮长成的面板，长到一定大小后内容才在面板下浮出来。
     * 进度 5% 就整页显出来的话，一按下去按钮就成了「装着整页缩略图的圆」。
     */
    fun contentAlpha(u: Float): Float = smoothStep((u - 0.12f) / 0.25f)

    /** 盖在页面上的那块按钮面板（底色 + 原大图标）的透明度，和 [contentAlpha] 交叉。 */
    fun coverAlpha(u: Float): Float = 1f - smoothStep((u - 0.18f) / 0.25f)

    /** 面板底色从按钮那种半透明长成实底，起步时看起来还是那颗按钮。 */
    fun coverFill(u: Float): Float = 0.55f + 0.45f * smoothStep(u / 0.12f)

    /** 飞行段在总进度的这个位置结束，之后只原地放大。 */
    private const val FLIGHT_END = 0.55f

    /** 从底栏打开时，飞行段停在屏幕高度的这个位置。 */
    private const val BAR_FLIGHT_TARGET = 0.68f

    private fun smoothStep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** [progress]：0 = 按钮，1 = 全屏。[full] 是整页尺寸，[from] 与它在同一坐标系。 */
    fun apply(
        scope: GraphicsLayerScope,
        from: Rect,
        full: Size,
        progress: Float,
        endCorner: Float,
        risesFromBottom: Boolean = false,
    ) = with(scope) {
        val u = progress.coerceIn(0f, 1f)
        val win = window(from, full, u, risesFromBottom)
        val width = win.width.coerceAtLeast(1f)
        val height = win.height.coerceAtLeast(1f)
        val s = maxOf(width / full.width, height / full.height).coerceIn(0.01f, 1f)
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = s
        scaleY = s
        translationX = win.left
        translationY = win.top
        // 裁切形状写在缩放前的页面坐标里
        shape = PixelRoundRectShape(Rect(0f, 0f, width / s, height / s), lerp(from.minDimension / 2f, endCorner, u) / s)
        clip = true
        // 飞行时带投影，铺满前收掉
        shadowElevation = 20.dp.toPx() * (1f - u)
        alpha = (u / FADE_END).coerceIn(0f, 1f)
    }
}

/** 一个格子自己的位置记录器：点击时读的是**被点的这一格**，同一服务在首页出现两次也不会拿错。 */
class ExpandOriginSource internal constructor(private val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null
    internal var captureRequested by mutableStateOf(false)

    // 点击那一帧先录一次，叠加层第一帧就有东西画；之后转场里每帧由 [recordLive] 重录。
    // 平时直接绘制，不为每张首页卡常驻离屏渲染。
    internal fun capture(draw: androidx.compose.ui.graphics.drawscope.ContentDrawScope) {
        if (captureRequested) {
            record(draw)
            // 录完当帧要画一次，图层才算在用，后面还能再画
            with(draw) { drawLayer(layer) }
            captureRequested = false
        }
    }

    /** 转场里：只把此刻的画面录进图层（叠加层画它），原位不画。 */
    internal fun recordLive(draw: androidx.compose.ui.graphics.drawscope.ContentDrawScope) = record(draw)

    private fun record(draw: androidx.compose.ui.graphics.drawscope.ContentDrawScope) {
        layer.record(
            density = draw,
            layoutDirection = draw.layoutDirection,
            size = IntSize(draw.size.width.roundToInt(), draw.size.height.roundToInt()),
        ) {
            // drawContent 画在 draw 自己的画布上，要临时换成录制画布，否则录到的是空的
            val recording = drawContext.canvas
            draw.draw(draw, draw.layoutDirection, recording, draw.size) { draw.drawContent() }
        }
    }

    private val capturedContent: @Composable (Modifier) -> Unit = { modifier ->
        Canvas(modifier) {
            val recorded = layer.size
            if (recorded.width > 0 && recorded.height > 0) {
                scale(size.width / recorded.width, size.height / recorded.height, Offset.Zero) { drawLayer(layer) }
            }
        }
    }

    /** 在跳转之前调用。格子已经脱离布局（拿不到坐标）就什么都不记，退回普通转场。 */
    fun arm(
        routeId: String,
        cornerRadius: Dp,
        density: Density,
        snapshot: (@Composable (Modifier) -> Unit)? = null,
        /** 圆钮自己的底色与投影，见 [ExpandOrigins.Origin.restFill]。 */
        restFill: androidx.compose.ui.graphics.Color? = null,
        restShadow: Boolean = true,
    ) {
        val c = coordinates?.takeIf { it.isAttached } ?: return
        val bounds = c.boundsInRoot()
        if (bounds.isEmpty) return
        if (snapshot == null) captureRequested = true
        ExpandOrigins.arm(
            routeId,
            ExpandOrigins.Origin(
                bounds, with(density) { cornerRadius.toPx() }, snapshot ?: capturedContent, this,
                restFill = restFill, restShadow = restShadow,
            ),
        )
    }

    /**
     * 点击瞬间读这一块在窗口里的矩形：KIT 的 Bitmap 源要它。
     * 只读已有的坐标记录，不额外挂 `onGloballyPositioned`，避免首页滚动/拖动时每帧回调；
     * 格子已脱离布局时返回 null，调用方回落普通 Compose 转场。
     */
    fun boundsInWindow(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

@Composable
fun rememberExpandOriginSource(): ExpandOriginSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ExpandOriginSource(layer) }
}

/**
 * 起点元素的绘制：平时照常画；转场里它「挪进叠加层」——不在原位画，而是每一帧把**此刻的真实画面**
 * 录进同一个图层，叠加层画的就是它（同 Compose 共享元素 renderInSharedTransitionScopeOverlay 的做法）。
 * 叠加层与原位始终是同一份内容，转场结束换回原位那一帧像素不变，不会闪。
 */
fun Modifier.expandOriginSource(source: ExpandOriginSource): Modifier =
    onGloballyPositioned { source.coordinates = it }
        .drawWithContent {
            when {
                source.captureRequested -> source.capture(this)
                ExpandOrigins.isHidden(source) -> {
                    // 在绘制阶段读一次进度：转场每走一帧都让这里重画、重录。不读的话这一页被盖着时没人
                    // 触发它重画，图层里一直是点击那一刻的画面（还带着按压反馈），落地时和真元素对不上
                    ExpandOrigins.contentTransition.value?.progress
                    source.recordLive(this)
                }
                else -> drawContent()
            }
        }

/**
 * 给类型 [type] 的页面用的转场。起点每一帧现读，所以起点被清掉以后，返回动画会自动退回普通平移。
 *
 * `relativeDepth`（= animatedTop - index，已按源码核对）：
 * - `-1..0`：这一页正在进场或退场，-1 在屏幕外，0 是停稳的栈顶；
 * - `0..1`：这一页被上面一页盖住。miuix-nav 规定被盖住那一层的样子由**上面那一页**的转场决定，
 *   所以这里也要处理 `d > 0`：从格子放大出来时首页原地不动，只靠 NavDisplayEffects 的变暗；
 *   没有起点时照搬默认转场的视差。
 *
 * @param screenCornerPx 全屏时的圆角，传屏幕的物理圆角（rememberNavSystemCornerRadius），
 *   让放大到最后和系统的圆角裁剪接上。
 */
fun expandFromOrigin(type: KClass<out AppRoute>, screenCornerPx: Float): NavTransition =
    navGraphicsTransition(motion = ExpandMotion) { scope ->
        val d = scope.relativeDepth
        val origin = ExpandOrigins.originOf(type)
        val width = scope.layoutSize.width.toFloat()
        val rtl = scope.layoutDirection == LayoutDirection.Rtl
        if (d > 0f) {
            if (origin == null) {
                // 和 NavTransitions.MiuixDefault 一样：被盖住时往前缘视差四分之一屏宽，略微变淡
                val cover = d.coerceIn(0f, 1f)
                translationX = (if (rtl) 1f else -1f) * cover * width * 0.25f
                alpha = 1f - 0.1f * cover
            } else {
                // 上面那页放大时，底下这页慢慢往后退一点（压暗由主界面按深度自己画）
                val depth = ButtonZoom.recedeDepth(d).coerceIn(0f, 1f)
                val s = lerp(1f, ButtonZoom.RECEDE_SCALE, depth)
                scaleX = s
                scaleY = s
                shape = PixelRoundRectShape(
                    Rect(0f, 0f, width, scope.layoutSize.height.toFloat()),
                    lerp(0f, screenCornerPx, depth),
                )
                clip = true
            }
            // 完全被盖住：这一页不用画了
            if (d >= 1f) alpha = 0f
            return@navGraphicsTransition
        }
        if (origin == null) {
            // 默认转场的进场：从后缘整屏滑入。取整到整像素，避免圆角边缘在小数位置上闪一条细线
            translationX = kotlin.math.round((if (rtl) -1f else 1f) * (-d).coerceIn(0f, 1f) * width)
            return@navGraphicsTransition
        }
        val p = (1f + d).coerceIn(0f, 1f) // 0 = 还在那一格里，1 = 全屏
        val full = Size(width, scope.layoutSize.height.toFloat())
        if (full.width <= 0f || full.height <= 0f) return@navGraphicsTransition

        val isPop = scope.change is NavChange.Pop

        // miuix-nav 出栈收尾时会再报一帧深度 0（p 从 ≈0 跳回 1），isRunning 却还是 true，
        // 而且这一帧的 change 可能已经不是 Pop 了。照这一帧去画，叠加层按 p=1 全透明、原卡仍藏着，
        // 落地那一下就空一帧（真机日志实测）。所以不看方向：同一个起点、还没结束的转场里
        // 进度一帧跳过半程不可能是动画，一律按「已结束」处理
        val current = ExpandOrigins.contentTransition.value
        val reset = scope.isRunning && current != null && current.origin === origin &&
            !current.ending && kotlin.math.abs(p - current.progress) > 0.5f

        // 转场进行时登记进度：叠加层和「藏原件」都读它，结束就清掉
        if (scope.isRunning && !reset) ExpandOrigins.updateContent(origin, p, full, isPop) else ExpandOrigins.clearContent()
        // 刚走完的是出栈：退场页已经不该出现，这一帧不能按 p=1 整页画出来
        if (reset && current?.popping == true) {
            alpha = 0f
            return@navGraphicsTransition
        }

        // 从顶栏圆钮打开：页面从按钮长出来、返回时缩回按钮（见 ButtonZoom）。
        // 带图标的圆钮先由叠加层画成「按钮面板」，页面内容晚一点才浮出来（见 ButtonMorphCover）
        if (ButtonZoom.isCircle(origin)) {
            ButtonZoom.apply(this, origin.bounds, full, p, screenCornerPx)
            if (origin.snapshot != null) alpha = ButtonZoom.contentAlpha(p)
            compositingStrategy = modulateUnlessHidden(alpha)
            return@navGraphicsTransition
        }

        // 窗口在屏幕坐标里从被点的格子长到全屏。起始帧的窗口就是那一格的尺寸，
        // 页面按「铺满窗口」等比缩放，所以不会出现一张小页面浮在格子中间。
        // 内容交接和展开同时开始，不单独停在起点等待淡入。
        val win = ExpandOrigins.windowOf(origin, p, full)
        val winWidth = win.bounds.width.coerceAtLeast(1f)
        val winHeight = win.bounds.height.coerceAtLeast(1f)
        val s = maxOf(winWidth / full.width, winHeight / full.height).coerceIn(0.05f, 1.06f)

        // 页面左上角贴着窗口左上角；以左上角为轴缩放，窗口长大时页面跟着长。
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = s
        scaleY = s
        translationX = win.bounds.left
        translationY = win.bounds.top

        // 裁剪形状定义在缩放前的页面坐标里：窗口换算回页面坐标后就是左上角的一块。
        val radius = lerp(origin.cornerPx, screenCornerPx, win.grow.coerceIn(0f, 1f)) / s
        shape = PixelRoundRectShape(Rect(0f, 0f, winWidth / s, winHeight / s), radius)
        clip = true
        // 在展开初段淡入页面内容，中段之前页面就已经完全显出来。
        // 返回时页面一直保持不透明，缩到格子前最后一刻才淡出；中段的内容交接由叠加层完成。
        // 逐条绘制指令调透明度，避免整页先进离屏缓冲再合成。
        alpha = if (origin.snapshot != null) {
            if (isPop) {
                // 返回：卡片接手过半后页面跟着淡掉，不能一直垫在下面——卡片落地时是透明玻璃，会把页面透出来
                (ExpandOrigins.pageContentAlpha(p, popping = true) * 2.5f).coerceIn(0f, 1f)
            } else {
                // 打开：卡片起步时是透明玻璃，页面先不出现；卡面底色铺满后、开始交接前页面才垫上来
                val t = ((p - 0.20f) / 0.06f).coerceIn(0f, 1f)
                t * t * (3f - 2f * t)
            }
        } else if (isPop) {
            ((p - POP_FADE_END) / (POP_FADE_START - POP_FADE_END)).coerceIn(0f, 1f)
        } else {
            val fade = (p / ORIGIN_FADE_END).coerceIn(0f, 1f)
            fade * fade * (3f - 2f * fade)
        }
        compositingStrategy = modulateUnlessHidden(alpha)
    }

/**
 * 逐条指令调透明度（ModulateAlpha）省掉整页离屏，但它只淡这一层自己的绘制指令：页面里嵌套的子图层
 * （卡片、顶栏）和这一层的投影照样画。透明度到 0 时那些东西仍会缩在起点处显出来——圆钮返回最后
 * 那团发暗的内容和外面那圈投影就是它。到 0 时改回普通合成，系统整层跳过不画。
 */
private fun modulateUnlessHidden(alpha: Float): CompositingStrategy =
    if (alpha <= 0f) CompositingStrategy.Auto else CompositingStrategy.ModulateAlpha

/** 进度低于它时原件显示，高于它时藏起来，和叠加层的淡入淡出同一段。 */
private const val SOURCE_HANDOFF = 0.05f

/** 页面内容在展开初段接入，不单独停留。 */
private const val ORIGIN_FADE_END = 0.08f

/** 返回时窗口缩回格子后在格子上淡出，交还给原来的格子。 */
private const val POP_FADE_START = 0.06f
private const val POP_FADE_END = 0.015f

/**
 * 子页转场（程序化跳转）的节奏：420ms，ease-in-out(0.4, 0, 0.2, 1)。
 *
 * 页面内部的二级页如果自己做滑入（比如设置里的致谢页），也用这组值，
 * 这样点进去和压栈打开子页是同一个手感。
 */
object AppPageMotion {
    const val DurationMillis = 420
    val Easing: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/** 格子放大 / 缩回的节奏，不回弹。返回手势松手后的落定（完成或反悔）也一样。 */
private val ExpandMotion = top.yukonga.miuix.kmp.nav.transition.NavMotion(
    commit = top.yukonga.miuix.kmp.nav.transition.NavSettleSpec.Tween(
        durationMillis = GESTURE_SETTLE_MS,
        easing = ButtonZoom.Easing,
    ),
    cancel = top.yukonga.miuix.kmp.nav.transition.NavSettleSpec.Tween(
        durationMillis = GESTURE_SETTLE_MS,
        easing = ButtonZoom.Easing,
    ),
    programmatic = top.yukonga.miuix.kmp.nav.transition.NavSettleSpec.Tween(
        durationMillis = EXPAND_DURATION_MS,
        easing = ButtonZoom.Easing,
    ),
)

private const val EXPAND_DURATION_MS = 660
private const val GESTURE_SETTLE_MS = 420

/** 裁剪用的形状：页面上的一块圆角矩形，[radius] 是缩放前的像素半径。 */
private class PixelRoundRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
