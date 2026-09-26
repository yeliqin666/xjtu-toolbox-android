package com.xjtu.toolbox.nav

import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.reflect.KClass

/**
 * 首页格子 → 功能页的「从那一格放大出来」转场（plan2 PR V）。
 *
 * miuix-nav v1 不支持跨页面的共享元素，所以做法是：点格子时记下它在屏幕上的矩形和圆角，
 * 目的地的转场读这个矩形，把整页裁剪成一个从那一格长到全屏的圆角矩形。
 * 转场是 `relativeDepth` 的纯函数，返回和系统预测式返回用的是同一个深度，
 * 所以同一个函数倒着走就是「缩回那一格」，而且跟手、可以反悔。
 *
 * 没有起点的（从全局搜索、深链、别的页面打开的）走和 miuix-nav 默认转场一样的平移。
 */
object ExpandOrigins {

    /** 起点：格子在根布局里的矩形（px）和圆角半径（px）。 */
    data class Origin(val bounds: Rect, val cornerPx: Float)

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
    internal fun onPush(route: AppRoute) {
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
    }

    internal fun originOf(type: KClass<out AppRoute>): Origin? = byType[type]
}

/** 一个格子自己的位置记录器：点击时读的是**被点的这一格**，同一服务在首页出现两次也不会拿错。 */
class ExpandOriginSource internal constructor() {
    internal var coordinates: LayoutCoordinates? = null

    /** 在跳转之前调用。格子已经脱离布局（拿不到坐标）就什么都不记，退回普通转场。 */
    fun arm(routeId: String, cornerRadius: Dp, density: Density) {
        val c = coordinates?.takeIf { it.isAttached } ?: return
        val bounds = c.boundsInRoot()
        if (bounds.isEmpty) return
        ExpandOrigins.arm(routeId, ExpandOrigins.Origin(bounds, with(density) { cornerRadius.toPx() }))
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

        // 整页再带一点缩放（0.92 → 1，以格子中心为轴），比单纯把裁剪框撑开更有「长出来」的感觉。
        // 裁剪形状定义在缩放之前的坐标里，所以起点要按缩放倒推，保证 p = 0 时屏幕上看到的正好是那一格。
        val s = lerp(0.92f, 1f, p)
        val pivotX = origin.bounds.center.x
        val pivotY = origin.bounds.center.y
        transformOrigin = TransformOrigin(pivotX / full.width, pivotY / full.height)
        scaleX = s
        scaleY = s

        val startLeft = pivotX + (origin.bounds.left - pivotX) / 0.92f
        val startTop = pivotY + (origin.bounds.top - pivotY) / 0.92f
        val startRight = pivotX + (origin.bounds.right - pivotX) / 0.92f
        val startBottom = pivotY + (origin.bounds.bottom - pivotY) / 0.92f
        val rect = Rect(
            lerp(startLeft, 0f, p),
            lerp(startTop, 0f, p),
            lerp(startRight, full.width, p),
            lerp(startBottom, full.height, p),
        )
        val radius = lerp(origin.cornerPx, screenCornerPx, p) / s
        shape = OffsetRoundRectShape(rect, radius)
        clip = true
        // 页面内容在前 30% 的进度里淡入：刚开始框还很小，满屏文字挤在里面只会显得乱。
        // 透明度逐条绘制指令去调，不要默认的整层离屏：整页带裁剪、alpha < 1 时默认策略会先把
        // 一整屏画进离屏缓冲再合成，转场头几帧因此掉帧。淡入只有一瞬间，叠加处的细微差别看不出来。
        alpha = (p / 0.3f).coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/**
 * 格子放大 / 缩回的节奏。
 *
 * miuix-nav 默认的程序化压栈、出栈是 500ms，缓动模拟 0.8 秒响应的弹簧：起步慢、收尾拖。
 * 平移转场配这条曲线还行；从一个小格子长到全屏，大部分时间都耗在最后贴边那一截，显得慢。
 * 换成 380ms 的「强调减速」曲线（起步就快、快速收住），跟手的预测式返回仍用默认弹簧。
 */
private val ExpandMotion = top.yukonga.miuix.kmp.nav.transition.NavMotion(
    programmatic = top.yukonga.miuix.kmp.nav.transition.NavSettleSpec.Tween(
        durationMillis = 380,
        easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f),
    ),
)

/** 裁剪用的形状：一个画在任意位置上的圆角矩形（graphicsLayer 的 shape 默认铺满整层）。 */
private class OffsetRoundRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
