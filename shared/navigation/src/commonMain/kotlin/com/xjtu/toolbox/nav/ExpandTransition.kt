package com.xjtu.toolbox.nav

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.reflect.KClass

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
    )

    /** 当前返回中的起点、进度和目的地尺寸；叠加层据此把源元素放回窗口里。 */
    data class Pop(val origin: Origin, val progress: Float, val full: Size)

    /** 返回窗口：屏幕坐标里的矩形，以及窗口从格子长到全屏的进度（0 = 格子，1 = 全屏）。 */
    data class Window(val bounds: Rect, val grow: Float)

    private val popState = mutableStateOf<Pop?>(null)

    val pop: State<Pop?> get() = popState

    internal fun updatePop(origin: Origin, progress: Float, full: Size) {
        popState.value = Pop(origin, progress.coerceIn(0f, 1f), full)
    }

    internal fun clearPop() {
        if (popState.value != null) popState.value = null
    }

    /** 按和转场完全相同的公式算返回窗口，保证叠加层和页面裁剪严丝合缝。 */
    fun windowOf(origin: Origin, progress: Float, full: Size): Window {
        val grow = ((progress - ORIGIN_HOLD) / (1f - ORIGIN_HOLD)).coerceIn(0f, 1f)
        return Window(
            bounds = Rect(
                lerp(origin.bounds.left, 0f, grow),
                lerp(origin.bounds.top, 0f, grow),
                lerp(origin.bounds.right, full.width, grow),
                lerp(origin.bounds.bottom, full.height, grow),
            ),
            grow = grow,
        )
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
    fun onPush(route: AppRoute) {
        clearPop()
        val a = armed
        armed = null
        if (a != null && a.first == route.id) byType[route::class] = a.second
        else byType.remove(route::class)
    }

    /**
     * 首页的格子已经不在原来的位置了（比如切到了别的 tab），返回时不能再往那儿缩。
     * [com.xjtu.toolbox.main.AppRouter] 在一路退回首页并切 tab 时调用。
     */
    fun clear() {
        armed = null
        byType.clear()
        clearPop()
    }

    internal fun originOf(type: KClass<out AppRoute>): Origin? = byType[type]
}

/** 一个格子自己的位置记录器：点击时读的是**被点的这一格**，同一服务在首页出现两次也不会拿错。 */
class ExpandOriginSource internal constructor() {
    internal var coordinates: LayoutCoordinates? = null

    /** 在跳转之前调用。格子已经脱离布局（拿不到坐标）就什么都不记，退回普通转场。 */
    fun arm(
        routeId: String,
        cornerRadius: Dp,
        density: Density,
        snapshot: (@Composable (Modifier) -> Unit)? = null,
    ) {
        val c = coordinates?.takeIf { it.isAttached } ?: return
        val bounds = c.boundsInRoot()
        if (bounds.isEmpty) return
        ExpandOrigins.arm(
            routeId,
            ExpandOrigins.Origin(bounds, with(density) { cornerRadius.toPx() }, snapshot),
        )
    }
}

@Composable
fun rememberExpandOriginSource(): ExpandOriginSource = remember { ExpandOriginSource() }

fun Modifier.expandOriginSource(source: ExpandOriginSource): Modifier =
    onGloballyPositioned { source.coordinates = it }

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
            }
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

        // 返回时页面一直保持到缩回格子前最后一刻才淡出，然后直接交还给原图标 / 横块。
        val isPop = scope.change is NavChange.Pop
        if (isPop && scope.isRunning && p > 0.01f) {
            ExpandOrigins.updatePop(origin, p, full)
        } else {
            ExpandOrigins.clearPop()
        }

        // 窗口在屏幕坐标里从被点的格子长到全屏。起始帧的窗口就是那一格的尺寸，
        // 页面按「铺满窗口」等比缩放，所以不会出现一张小页面浮在格子中间。
        // 开头一小段先停在格子上做淡入：页面从格子里长出来之前，先把格子本身换成页面内容，
        // 返回时也正好收回这一格再淡出，落点就是被点元素的位置。
        val win = ExpandOrigins.windowOf(origin, p, full)
        val winWidth = win.bounds.width.coerceAtLeast(1f)
        val winHeight = win.bounds.height.coerceAtLeast(1f)
        val s = maxOf(winWidth / full.width, winHeight / full.height).coerceIn(0.05f, 1f)

        // 页面左上角贴着窗口左上角；以左上角为轴缩放，窗口长大时页面跟着长。
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = s
        scaleY = s
        translationX = win.bounds.left
        translationY = win.bounds.top

        // 裁剪形状定义在缩放前的页面坐标里：窗口换算回页面坐标后就是左上角的一块。
        val radius = lerp(origin.cornerPx, screenCornerPx, win.grow) / s
        shape = PixelRoundRectShape(Rect(0f, 0f, winWidth / s, winHeight / s), radius)
        clip = true
        // 在格子上淡入页面内容；淡入完成后再开始放大，中段之前页面就已经完全显出来。
        // 返回时页面一直保持不透明，缩到格子前最后一刻才淡出；中段的内容交接由叠加层完成。
        // 逐条绘制指令调透明度，避免整页先进离屏缓冲再合成。
        alpha = if (isPop) {
            ((p - POP_FADE_END) / (POP_FADE_START - POP_FADE_END)).coerceIn(0f, 1f)
        } else {
            val fade = (p / ORIGIN_HOLD).coerceIn(0f, 1f)
            fade * fade * (3f - 2f * fade)
        }
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/** 起点停留 / 淡入占整段转场的比例。 */
private const val ORIGIN_HOLD = 0.04f

/** 返回时页面保持到缩回格子前最后一刻（约 0.045 进度）再淡出，避免中段出现空档。 */
private const val POP_FADE_START = 0.045f
private const val POP_FADE_END = 0.04f

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

/**
 * 格子放大 / 缩回的节奏。
 *
 * 用 420ms 的 ease-in-out：先在格子上淡入页面内容，中段加速放大，收尾再减速贴边。
 * 跟手的预测式返回仍用 miuix-nav 默认弹簧。
 */
private val ExpandMotion = top.yukonga.miuix.kmp.nav.transition.NavMotion(
    programmatic = top.yukonga.miuix.kmp.nav.transition.NavSettleSpec.Tween(
        durationMillis = AppPageMotion.DurationMillis,
        easing = AppPageMotion.Easing,
    ),
)

/** 裁剪用的形状：页面上的一块圆角矩形，[radius] 是缩放前的像素半径。 */
private class PixelRoundRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
