package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.TextUnit

/**
 * 页面级 COUI 采样源：一页正文（背景 + 滚动内容）录成的一层，供 COUI 顶栏的渐变模糊与按钮材质采样。
 *
 * 对应 COUI-Kit 的 `CouiBackdrop`，但 commonMain 不能直接暴露 Android 类型，所以包一层接口；
 * Android 的 actual 内部转成 `CouiBackdrop`。其它平台没有实现（null），顶栏退回不模糊的实色/旧样式。
 */
interface CouiPageBackdrop {
    /** 把采样层挂到页面正文节点上。同一子树只能挂一次。 */
    fun recordInto(modifier: Modifier): Modifier
}

/** 每页自己建一份（[rememberPageGlass] 里顺带创建）；非 Android 平台为 null。 */
@Composable
expect fun rememberCouiPageBackdrop(): CouiPageBackdrop?

/** 页面顶栏读取本页采样源用；由页面内容层（[glassSource] / 首页背景层）提供。 */
val LocalCouiPageBackdrop = staticCompositionLocalOf<CouiPageBackdrop?> { null }

/**
 * COUI 自己的折叠顶栏滚动行为。
 *
 * Android 的 actual 就是 COUI-Kit `CouiTopAppBarScrollBehavior`（内部持有
 * `CouiCollapsingAppBarState`：正负滚动增量、临界阻尼弹簧、松手吸附、`contentPadding`
 * 修正全在原厂代码里），本接口只把 `nestedScrollConnection` 交给页面接
 * `Modifier.nestedScroll`。其它平台没有 COUI 顶栏，actual 包一层 MIUIX `ScrollBehavior`，
 * 回退到 MIUIX `TopAppBar` 时再取出。
 */
interface CouiTopBarScrollBehavior {
    /** 挂到滚动容器祖先上的连接；行为语义见 COUI `CouiCollapsingAppBarState`。 */
    val nestedScrollConnection: NestedScrollConnection

    /**
     * 正文是不是滚到顶栏下面了（按钮据此从纯图标切成玻璃钮，折叠状态据此摆正）。一般由连接自己从滚动事件里判断；
     * 不经过嵌套滚动的程序化滚动（聊天自动滚到底等）由页面用这里补报。
     */
    fun reportScrolled(scrolled: Boolean) {}
}

/** 每页（或每个 tab）一份 COUI 折叠状态；与 COUI `CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior` 等价。 */
@Composable
expect fun rememberCouiTopAppBarScrollBehavior(): CouiTopBarScrollBehavior

/**
 * COUI 渐变模糊顶栏表面：背景铺「模糊 + 渐隐色」，把录好的表面通过 `LocalCouiToolbarBackdrop`
 * 交给顶栏按钮采样。
 *
 * 首页那条自定义顶栏（头像 + 三颗按钮）暂时不换成 COUI 的 AppBar 组件，只把模糊换成这一层，
 * 所以单独开一个入口；[pageBackdrop] 为 null 时只是实色底，内容原样画。
 */
@Composable
expect fun CouiHomeTopBarSurface(
    pageBackdrop: CouiPageBackdrop?,
    gradientColor: Color,
    modifier: Modifier = Modifier,
    /** 按钮玻璃的不透明度（绘制时读）：正文没滚到顶栏下时为 0。模糊底不受它管，常驻。 */
    glassAlpha: () -> Float = { 1f },
    content: @Composable () -> Unit,
)

/**
 * 顶栏动作按钮的公共入口：各页顶栏右上角的图标按钮、首页编辑态的齿轮 / 对勾都用它。
 *
 * Android 上有顶栏采样源（[LocalCouiPageBackdrop] 那一路，COUI 顶栏表面内）时是 COUI
 * `TopAppBarButton` 的移植（AGSL 材质、边缘光、跟手、按压，40dp 视觉 + 48dp 交互尺寸）；
 * 没有采样源（经典风格、MIUIX 旧顶栏、非 Android 平台）时退回半透明圆钮，
 * 同样 40dp 视觉 + 48dp 交互、按压反馈，并且 [enabled] 生效。
 *
 * [content] 是按钮内容槽（一般是一颗 `Icon`），[enabled] 为 false 时点击不触发、内容变淡。
 */
@Composable
expect fun CouiToolbarActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
)

/**
 * 顶栏上的胶囊文字按钮（如「发布」「提交」）。
 *
 * Android 上复用的就是 [CouiToolbarActionButton] 那套 COUI `TopAppBarButton`，只是走原版的
 * `text` 分支：宽 ≥ 60dp、高 40dp、内容水平留 12dp，宽高不等时自动用胶囊形状，
 * AGSL 材质、边缘光、内光晕、轮廓投影、跟手形变、按压弹簧与圆钮完全一致。
 * 没有顶栏采样源时退回半透明胶囊，尺寸（40dp 高、最少 60dp 宽）与玻璃版对齐。
 */
@Composable
expect fun CouiToolbarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

/**
 * 二级页 / 主界面 tab 的 COUI 顶栏。
 *
 * Android 上就是 COUI-Kit 的 `LargeTopAppBar` / `TopAppBar`（原版 `AppBarLayout` 测量与
 * `CouiTopAppBarSurface` 渐变模糊，未改一行版式），折叠进度由 COUI 自己的
 * [CouiTopBarScrollBehavior] 驱动：页面把它接到滚动容器祖先的 `Modifier.nestedScroll` 上即可。
 *
 * 一级页（首页 / 日程 / 学辅 / 我的）用折叠版（`pinned = false`）；二级页统一 `pinned = true`，
 * 标题固定在收起态、不随上滑变小，模糊与一级页同一套 COUI 渐变模糊——此时 [scrollBehavior]
 * 的连接也不再吃掉滚动（顶栏本来就不折叠，正文不该先停 58dp）。
 *
 * [pageBackdrop] 有值时走 COUI 玻璃；没有时画 COUI 版式 + 实色底。
 * 非 Android 平台的 actual 直接回退到 MIUIX `TopAppBar`，行为与改造前一致。
 */
@Composable
expect fun CouiPageTopAppBar(
    title: String,
    largeTitle: String = title,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    scrollBehavior: CouiTopBarScrollBehavior? = null,
    pageBackdrop: CouiPageBackdrop? = null,
    /** 大字下面的小字，对应 MIUIX `TopAppBar` 的 `subtitle`；刚展开时可见，收起时淡出。 */
    subtitle: String? = null,
    /** 覆盖标题文字色；Unspecified 时用 Miuix 主题的 onSurface。 */
    titleColor: Color = Color.Unspecified,
    /** 直接按收起态画（钉死的小标题），不再跟随滚动进度。二级页统一用它。 */
    pinned: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    /**
     * 覆盖标题字号；null = 各版式自己的默认值。
     * 钉死小标题想让字号和「大标题收起后」一致时传 `CouiTopAppBarDefaults.titleOnlyCollapsedSize`。
     */
    titleSize: TextUnit? = null,
)
