package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.ui.components.BackButton
import androidx.compose.foundation.layout.RowScope
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页的玻璃顶栏。用法：
 * ```
 * val glass = rememberPageGlass()                        // 经典风格下是 null
 * Scaffold(topBar = {
 *     GlassTopAppBar(title = "标题", glass = glass, scrollBehavior = scrollBehavior, onBack = onBack)
 *     // 或者直接写 CouiPageTopAppBar(..., color = glassBarColor(glass),
 *     //     pageBackdrop = couiPageBackdropFor(glass))
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

/** 二级页被用户放到了底栏上（没有上一级）：[com.xjtu.toolbox.ui.components.BackButton] 不画。 */
val LocalTabRoot = compositionLocalOf { false }

/** 这一页的玻璃采样源；经典风格时为 null。每页自己建一份，不借主界面的。 */
@Composable
fun rememberPageGlass(): LayerBackdrop? {
    val glassStyle = LocalGlassStyle.current
    val backdrop = rememberLayerBackdrop(cache = true)
    // COUI 顶栏的模糊采的是这一页正文，和 kyant 的采样层一起建；经典风格顶栏是实色，
    // 不必白录一层（非 Android 平台这里也返回 null）。
    val couiBackdrop = if (glassStyle) rememberCouiPageBackdrop() else null
    couiPageBackdrops[backdrop] = couiBackdrop
    // 页面离开组合后把登记撤掉，别把整页的采样层留在内存里
    DisposableEffect(backdrop, couiBackdrop) {
        onDispose { couiPageBackdrops.remove(backdrop) }
    }
    return if (glassStyle) backdrop else null
}

/** 页面的 COUI 采样源，键是这一页的 kyant 采样层；见 [rememberPageGlass]。 */
private val couiPageBackdrops = mutableMapOf<LayerBackdrop, CouiPageBackdrop?>()

/**
 * 取 [glass] 这一页的 COUI 采样源：null = 经典风格 / 非 Android / 页面没有建。
 * 顶栏拿它决定是铺 COUI 渐变模糊还是画实色底。
 */
fun couiPageBackdropFor(glass: LayerBackdrop?): CouiPageBackdrop? =
    if (glass == null) null else couiPageBackdrops[glass]

/**
 * 录下这一层作为采样源；[backdrop] 为 null 时什么都不做。
 * 同时让内容跟着顶栏折叠走：页面按稳定的最大留白排版，实际矮多少就在布局阶段上移多少。
 */
fun Modifier.glassSource(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    val couiBackdrop = couiPageBackdrops[backdrop]
    return this.layerBackdrop(backdrop)
        .then(if (couiBackdrop != null) couiBackdrop.recordInto(Modifier) else Modifier)
        // 采样层自己垫一层页面底色：页面底色是 Scaffold 在采样层外面画的，录下来是透明的，
        // 顶栏的模糊遇到透明像素直接放行，不在卡片上的字（「共 N 项」之类）就会清清楚楚透出顶栏
        .composed { background(MiuixTheme.colorScheme.surface) }
        .shiftUpBy {
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

/** 顶栏跟随状态。 */
private val topBarFollows = mutableMapOf<LayerBackdrop, TopBarFollow>()

/**
 * 二级页的标准顶栏：COUI 大标题折叠顶栏（非 Android 回退 MIUIX 顶栏）、左上角返回。
 * 和一级页同一套：上滑先收标题，模糊底常驻。[scrollBehavior] 必须接到正文的滚动上
 * （`Modifier.nestedScroll` 或交给 `AppPullToRefresh`），否则大标题收不起来。
 */
@Composable
fun GlassTopAppBar(
    title: String,
    glass: LayerBackdrop?,
    scrollBehavior: CouiTopBarScrollBehavior,
    onBack: () -> Unit,
    largeTitle: String = title,
    /** 大标题下面的一行小字，收起时淡出。 */
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    CouiPageTopAppBar(
        title = title,
        largeTitle = largeTitle,
        subtitle = subtitle,
        color = glassBarColor(glass),
        scrollBehavior = scrollBehavior,
        pageBackdrop = couiPageBackdropFor(glass),
        navigationIcon = { BackButton(onBack) },
        actions = actions,
        bottomContent = bottomContent,
    )
}

/** 顶栏底色：COUI 顶栏自己画背景，玻璃时透明；经典风格是 surface。 */
@Composable
fun glassBarColor(backdrop: LayerBackdrop?): Color =
    // COUI 的折叠顶栏由 CouiTopAppBarSurface 铺模糊（渐隐色那层已去掉），底色必须透出来；
    // 经典风格（没有采样源）才用实色，页面传进来的这个值最终就是它的背景。
    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface

/** 二级页顶栏的玻璃，见 [glassBarSurface]。 */
@Composable
fun Modifier.glassTopBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    // COUI 的折叠顶栏自己铺渐隐模糊，再叠一层 kyant 渐进模糊就是两层模糊
    if (couiGlassBarSupported) return this
    return glassBarSurface(backdrop, glassBarTint())
}

/**
 * 旧玻璃兜底：给仍然用 MIUIX 钉死小标题顶栏的页准备（那种顶栏没有自己的表面，
 * 只能靠 [glassBarSurface] 压在内容上）。Android 各页的顶栏已全部换成
 * [CouiPageTopAppBar]（pinned = true）的 COUI 表面，这里目前没有调用方，留作回退入口。
 */
@Composable
fun Modifier.glassPinnedTopBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return glassBarSurface(backdrop, glassBarTint())
}

/** 顶栏玻璃上压的表面色，保证字看得清。 */
@Composable
fun glassBarTint(): Color = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f)

/** 贴边顶栏的玻璃：与首页同一套无边界渐变模糊，见 [progressiveBlurBar]。只画不量。 */
@Composable
fun Modifier.glassBarSurface(backdrop: Backdrop, tint: Color): Modifier = progressiveBlurBar(backdrop, tint)

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
    DisposableEffect(backdrop) { onDispose { topBarFollows.remove(backdrop) } }
    return follow.stable
}
