package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页（成绩、空闲教室、校历……）的玻璃顶栏。
 *
 * 用法，四步，缺一步都不行：
 * ```
 * val glass = rememberPageGlass()                        // 经典风格下是 null
 * Scaffold(topBar = {
 *     TopAppBar(..., color = glassBarColor(glass), modifier = Modifier.glassTopBar(glass))
 * }) { padding ->
 *     // ① 内容容器不再吃 padding 的顶部，改成铺到顶栏下面，并录成采样源
 *     Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass)) {
 *         // ② 顶部留白放进滚动内容里（LazyColumn 的 contentPadding / 纵向滚动里的 Spacer）
 *         val top = padding.glassTop(glass)
 *         PullToRefresh(
 *             ...,
 *             contentPadding = PaddingValues(top = top),  // ③ 下拉指示器从顶栏下面出来
 *         ) { LazyColumn(contentPadding = PaddingValues(top = top + 8.dp, ...)) { ... } }
 *     }
 * }
 * ```
 * - 为什么要铺到顶栏下面：玻璃像一块透镜，背后是纯色，折射出来也还是纯色，只会得到一层灰膜；
 *   内容从顶栏下面滚过去，玻璃才看得出来。
 * - 为什么采样源挂在内容容器上、不挂在 Scaffold 上：顶栏在 Scaffold 里面，录整个 Scaffold
 *   就成了「玻璃采样自己」的环，RenderThread 直接 SIGSEGV。
 * - 不滚动的横幅、筛选条这类固定在顶部的东西，要么自己让出顶栏高度（在它上面放
 *   `Spacer(Modifier.height(top))`，下面的列表就不用再留），要么挪进列表里跟着滚。
 *   **别让它们被压在玻璃后面**。
 */
val LocalGlassStyle = compositionLocalOf { false }

/**
 * 这一页的玻璃采样源；界面风格选「经典」时返回 null，所有玻璃点退回不透明。
 *
 * 每一页自己建一份，不借主界面的 [LocalAppBackdrop]：二级页盖在首页上面，
 * 采首页的内容没有意义，还会让两层采样源互相嵌套。
 */
@Composable
fun rememberPageGlass(): LayerBackdrop? {
    val backdrop = rememberLayerBackdrop()
    return if (LocalGlassStyle.current) backdrop else null
}

/** 录下这一层作为玻璃的采样源；[backdrop] 为 null 时什么都不做。 */
fun Modifier.glassSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this

/** 顶栏的底色：玻璃时透明，否则就是原来的 surface。 */
@Composable
fun glassBarColor(backdrop: LayerBackdrop?): Color =
    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface

/**
 * 顶栏本身的玻璃：色彩增强 + 模糊 + 轻微折射，再压一层表面色保证标题看得清。
 * 直角要写成 `RoundedCornerShape(0.dp)`：`RectangleShape` 开折射会闪退。
 */
@Composable
fun Modifier.glassTopBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    val tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(0.dp) },
        effects = {
            // 折射需要的采样余量先让出来
            padding = maxOf(padding, 16.dp.toPx())
            vibrancy()
            blur(8.dp.toPx(), TileMode.Clamp)
            lens(refractionHeight = 12.dp.toPx(), refractionAmount = 16.dp.toPx())
        },
        onDrawSurface = { drawRect(tint) },
    )
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

/** 玻璃时要放进滚动内容里的顶部留白（= 顶栏高度）；经典时为 0，布局和原来一样。 */
fun PaddingValues.glassTop(backdrop: LayerBackdrop?): Dp =
    if (backdrop != null) calculateTopPadding() else 0.dp
