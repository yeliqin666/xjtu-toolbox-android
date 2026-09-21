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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
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
 *             topAppBarScrollBehavior = scrollBehavior,   // 往下拉先展开大标题，再算下拉刷新
 *             contentPadding = PaddingValues(top = top),  // ③ 下拉指示器从顶栏下面出来
 *         ) { LazyColumn(contentPadding = PaddingValues(top = top + 8.dp, ...)) { ... } }
 *     }
 * }
 * ```
 * - 为什么要铺到顶栏下面：玻璃像一块透镜，背后是纯色，折射出来也还是纯色，只会得到一层灰膜；
 *   内容从顶栏下面滚过去，玻璃才看得出来。
 * - 为什么采样源挂在内容容器上、不挂在 Scaffold 上：顶栏在 Scaffold 里面，录整个 Scaffold
 *   就成了「玻璃采样自己」的环，RenderThread 直接 SIGSEGV。
 * - 采样源要挂在滚动（`verticalScroll`）**之前**：挂在后面录下的是整条跟着滚的长内容，
 *   不是屏幕上这块视口，顶栏按屏幕位置采样就对不上，看起来只是透明、没有模糊。
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
 * 顶栏本身的玻璃：色彩增强 + 模糊，再压一层表面色保证标题看得清。见 [glassBarSurface]。
 */
@Composable
fun Modifier.glassTopBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return glassBarSurface(backdrop, glassBarTint())
}

/** 顶栏玻璃上压的那层表面色。标题、标签、周胶囊的字靠它保证看得清。 */
@Composable
fun glassBarTint(): Color = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f)

/**
 * 贴边顶栏的玻璃画法。二级页（[glassTopBar]）、主界面日程顶栏、校园卡顶栏共用这一份。
 *
 * 和悬浮的玻璃底栏不同，顶栏是贴着屏幕上沿、横跨整宽的一整块，所以：
 * - **不要投影、不要边缘高光**：kyant 的 drawBackdrop 默认带一圈 24dp 的黑色投影和描边高光，
 *   放在悬浮胶囊上是「浮起来」，放在贴边顶栏上就是一道灰影压在内容上，像老式 Android 的
 *   elevation 分界线，把顶栏和内容硬生生切开。两样都显式关掉。
 * - **不要折射**：lens 会把底边十几 dp 里的内容拉弯，看起来像底边多了一条扭曲的暗带。
 *   折射适合有圆角轮廓的悬浮物，整宽直边的顶栏用不上。
 * - **模糊要够**：20dp。8dp 时经过顶栏的粗体标题、分类小字还读得出轮廓，看着像「没虚化」。
 * - **采样范围要比顶栏大一圈**（`padding`）：模糊只在采样范围里混像素，范围外面按 TileMode 补。
 *   范围和顶栏一样大时，越靠近顶栏上下边缘可混的像素越少，下沿接缝那一行、贴着屏幕顶边的
 *   那一行几乎是清楚的——设置页里「教务通知」这种小标题滚到那里，看着就是「这里没虚化」。
 *   往外扩 24dp（大于模糊半径），接缝处也能混到下面的真实内容。屏幕顶边外面没有内容，
 *   用 Decal（外面当透明）而不是 Clamp：Clamp 会把最边上那一排像素拉长，文字反而更清楚。
 * - **底边就是一刀干净的边**：试过在底边外面再铺一段表面色渐隐，那一段只有色没有模糊，
 *   内容经过时是一行清清楚楚的字隔着一层纱，比硬边更糟，已去掉。
 *
 * 直角要写成 `RoundedCornerShape(0.dp)`：`RectangleShape` 开折射会闪退，这里虽然不开，
 * 统一写法更保险。
 */
fun Modifier.glassBarSurface(backdrop: Backdrop, tint: Color): Modifier =
    this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(0.dp) },
            effects = {
                padding = maxOf(padding, 24.dp.toPx())
                vibrancy()
                blur(20.dp.toPx(), TileMode.Decal)
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(tint) },
        )

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
