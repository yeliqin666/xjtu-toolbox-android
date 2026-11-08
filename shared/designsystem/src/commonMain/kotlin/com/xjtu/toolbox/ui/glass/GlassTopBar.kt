package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.PaddingValues
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
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.ui.components.BackButton
import androidx.compose.foundation.layout.RowScope
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页的顶栏：玻璃风格下画一条渐变遮罩（暗色黑 / 亮色白，见 [topBarScrim]）。用法：
 * ```
 * val glass = rememberPageGlass()                        // 经典风格下是 null
 * Scaffold(topBar = {
 *     TopAppBar(..., color = glassBarColor(glass), modifier = Modifier.glassTopBar(glass))
 * }) { padding ->
 *     // 内容铺到顶栏下面；不跟着滚的横幅、筛选条要自己让出顶栏高度，别压在遮罩后面
 *     Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass)) {
 *         val top = padding.glassTop(glass)                   // 顶部留白放进滚动内容
 *         LazyColumn(contentPadding = PaddingValues(top = top + 8.dp, ...)) { ... }
 *     }
 * }
 * ```
 * [glassSource] 只管顶栏折叠时的布局位移，**不再录采样层**（顶栏已经不采样了）。
 */
val LocalGlassStyle = compositionLocalOf { false }

/** 二级页被用户放到了底栏上（没有上一级）：[com.xjtu.toolbox.ui.components.BackButton] 不画。 */
val LocalTabRoot = compositionLocalOf { false }

/**
 * 这一页的玻璃采样源；经典风格时为 null。每页自己建一份，不借主界面的。
 *
 * 顶栏从 2026-11 起不再采样（见 [topBarScrim]），所以这个对象现在只当**「玻璃风格 + 每页一份」
 * 的哨兵**用：`glassTopBar` / `glassBarColor` / `withoutTop` / `glassTop` 都靠它判 null，
 * `glassSource` 也不再往它里面录内容（那次整屏离屏渲染已经省掉）。
 */
@Composable
fun rememberPageGlass(): LayerBackdrop? {
    val backdrop = rememberLayerBackdrop()
    return if (LocalGlassStyle.current) backdrop else null
}

/**
 * 让内容跟着顶栏折叠走：页面按稳定的最大留白排版，实际矮多少就在布局阶段上移多少。
 * [backdrop] 为 null（经典风格）时什么都不做。
 *
 * 以前这里还挂着 `layerBackdrop`，把整页录一份给顶栏采样；顶栏改成渐变遮罩后没人采样了，
 * 就把那一次**整屏离屏渲染**摘掉了——每个二级页每帧省一趟（40 来个页面都吃这口）。
 */
fun Modifier.glassSource(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return shiftUpBy {
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

/** 二级页的标准顶栏：玻璃（经典风格下不透明）、大标题随滚动折叠、左上角返回。 */
@Composable
fun GlassTopAppBar(
    title: String,
    glass: LayerBackdrop?,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    largeTitle: String = title,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = title,
        largeTitle = largeTitle,
        color = glassBarColor(glass),
        modifier = Modifier.glassTopBar(glass),
        scrollBehavior = scrollBehavior,
        navigationIcon = { BackButton(onBack) },
        actions = actions,
        bottomContent = bottomContent,
    )
}

/** 顶栏底色：玻璃时透明，否则是 surface。 */
@Composable
fun glassBarColor(backdrop: LayerBackdrop?): Color =
    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface

/**
 * 二级页顶栏的渐变遮罩，见 [topBarScrim]。只画不量。
 *
 * 参数是「这一页走不走玻璃风格」的哨兵：null = 经典风格，顶栏自己铺不透明底色，不画遮罩。
 */
@Composable
fun Modifier.glassTopBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return Modifier.topBarScrim()
}

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
