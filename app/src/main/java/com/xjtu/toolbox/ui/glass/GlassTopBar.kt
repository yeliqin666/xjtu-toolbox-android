package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.drawBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页的玻璃顶栏。用法：
 * ```
 * val glass = rememberPageGlass()                        // 经典风格下是 null
 * Scaffold(topBar = {
 *     TopAppBar(..., color = glassBarColor(glass), modifier = Modifier.glassTopBar(glass))
 * }) { padding ->
 *     // 内容铺到顶栏下面并作为采样源；采样源要挂在滚动容器外面
 *     Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass)) {
 *         val top = padding.glassTop(glass)                   // 顶部留白放进滚动内容
 *         LazyColumn(contentPadding = PaddingValues(top = top + 8.dp, ...)) { ... }
 *     }
 * }
 * ```
 * 采样源别挂在整个 Scaffold 上（顶栏会采样自己，RenderThread 崩溃）；
 * 不跟着滚的横幅、筛选条要自己让出顶栏高度，别压在玻璃后面。
 */
val LocalGlassStyle = compositionLocalOf { false }

/** 这一页的玻璃采样源；经典风格时为 null。每页自己建一份，不借主界面的。 */
@Composable
fun rememberPageGlass(): LayerBackdrop? {
    val backdrop = rememberLayerBackdrop()
    return if (LocalGlassStyle.current) backdrop else null
}

/**
 * 录下这一层作为采样源；[backdrop] 为 null 时什么都不做。
 * 同时让内容跟着顶栏折叠走：页面按稳定的最大留白排版，实际矮多少就在布局阶段上移多少。
 */
fun Modifier.glassSource(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return this.layerBackdrop(backdrop).shiftUpBy {
        val follow = topBarFollows[backdrop] ?: return@shiftUpBy 0.dp
        follow.stable - follow.current()
    }
}

/**
 * 内容往上挪 [amount]（布局阶段算），高度补上同样的量，底边不动。
 * 高度必须随位移变：固定多量一截的话，折叠后内容底部会落到可视区外面、滑不到。
 */
private fun Modifier.shiftUpBy(amount: () -> Dp): Modifier =
    this.layout { measurable, constraints ->
        val shift = amount().roundToPx().coerceAtLeast(0)
        val c = if (constraints.hasBoundedHeight) {
            constraints.copy(minHeight = constraints.minHeight + shift, maxHeight = constraints.maxHeight + shift)
        } else constraints
        val placeable = measurable.measure(c)
        layout(placeable.width, constraints.constrainHeight(placeable.height - shift)) {
            placeable.place(0, -shift)
        }
    }

/** 自己管采样层的页面（主界面各 tab、校园卡）：稳定的顶部留白，配合 [followTopBar] 补位移。 */
@Composable
fun rememberStableTopPadding(padding: PaddingValues): androidx.compose.runtime.State<Dp> {
    val stable = remember { mutableStateOf(Snapshot.withoutReadObservation { padding.calculateTopPadding() }) }
    LaunchedEffect(padding) {
        snapshotFlow { padding.calculateTopPadding() }.collect { if (it > stable.value) stable.value = it }
    }
    return stable
}

/** 内容按 [stableTop] 留白排版，实际顶栏（[padding]）矮多少就往上挪多少。只在布局阶段读。 */
fun Modifier.followTopBar(stableTop: () -> Dp, padding: PaddingValues): Modifier =
    shiftUpBy { stableTop() - padding.calculateTopPadding() }

/** 顶栏高度：[stable] 是见过的最大值，[current] 只在布局阶段读。 */
private class TopBarFollow(initial: Dp) {
    var stable by mutableStateOf(initial)
    var padding: PaddingValues? = null
    fun current(): Dp = padding?.calculateTopPadding() ?: stable
}

private val topBarFollows = java.util.WeakHashMap<LayerBackdrop, TopBarFollow>()

/** 顶栏底色：玻璃时透明，否则是 surface。 */
@Composable
fun glassBarColor(backdrop: LayerBackdrop?): Color =
    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface

/** 二级页顶栏的玻璃，见 [glassBarSurface]。 */
@Composable
fun Modifier.glassTopBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return glassBarSurface(backdrop, glassBarTint())
}

/** 顶栏玻璃上压的表面色，保证字看得清。 */
@Composable
fun glassBarTint(): Color = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f)

/**
 * 贴边顶栏的玻璃：色彩增强 + 模糊，再压一层 [tint]。不投影、不折射（整宽直边用不上）；
 * 采样范围比顶栏大一圈，边缘也有真实内容可混；屏幕顶边外按透明处理（Clamp 会把边缘像素拉长）。
 *
 * 参数要 remember：drawBackdrop 按 lambda 是否相等判断参数变没变，每次重组传新 lambda
 * 会让它每次都重建效果。
 */
@Composable
fun Modifier.glassBarSurface(backdrop: Backdrop, tint: Color): Modifier {
    val surface = remember(backdrop, tint) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { BarShape },
            effects = {
                padding = maxOf(padding, 24.dp.toPx())
                vibrancy()
                blur(20.dp.toPx(), TileMode.Decal)
            },
            highlight = null,
            shadow = null,
            // 采样层里没有页面底色，先垫一层不透明的底，否则字周围模糊后几乎不剩东西
            onDrawBehind = { drawRect(tint.copy(alpha = 1f)) },
            onDrawSurface = { drawRect(tint) },
        )
    }
    return this then surface
}

// 直角写成 RoundedCornerShape(0.dp)：RectangleShape 开折射会闪退
private val BarShape = RoundedCornerShape(0.dp)

/** 玻璃时内容容器不吃顶部 padding（铺到顶栏下面）；经典时原样返回。 */
@Composable
fun PaddingValues.withoutTop(backdrop: LayerBackdrop?): PaddingValues {
    if (backdrop == null) return this
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(dir),
        end = calculateEndPadding(dir),
        bottom = calculateBottomPadding(),
    )
}

/**
 * 玻璃时放进滚动内容的顶部留白（经典时为 0）。返回见过的最大顶栏高度而不是实时高度，
 * 免得顶栏折叠时整页每帧重组；差额由 [glassSource] 在布局阶段补。
 */
@Composable
fun PaddingValues.glassTop(backdrop: LayerBackdrop?): Dp {
    if (backdrop == null) return 0.dp
    val follow = remember(backdrop) {
        topBarFollows.getOrPut(backdrop) {
            TopBarFollow(Snapshot.withoutReadObservation { calculateTopPadding() })
        }
    }
    follow.padding = this
    LaunchedEffect(follow, this) {
        snapshotFlow { calculateTopPadding() }.collect { if (it > follow.stable) follow.stable = it }
    }
    return follow.stable
}
