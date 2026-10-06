package com.xjtu.toolbox.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.isWideLayout
import top.yukonga.miuix.kmp.basic.VerticalDivider

/**
 * 宽屏共用布局件。窄屏一律无操作 / 只渲染主内容，手机上的排版不因为它们改变。
 */

/**
 * 正文限宽并居中。一行文字横跨整个平板宽度读起来很累，超过 [max] 就不再拉长。
 *
 * 加在**内容根**上（Scaffold 内容里的 LazyColumn / Column），**不要**加在 Scaffold 上，
 * 否则顶栏也跟着变窄。窄屏下返回原样，手机排版一点不动。
 */
@Composable
fun Modifier.readableWidth(max: Dp = 720.dp): Modifier =
    if (isWideLayout()) {
        this
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = max)
    } else {
        this
    }

/**
 * 宽屏的内容宽度上限，比 [readableWidth] 的 720dp 宽得多：平板横屏将近 1400dp，
 * 限到 720 左右各空出三分之一屏。多列卡片流用这个上限，只防止超宽屏（外接显示器）拉得太散。
 */
val WideContentMaxWidth = 1280.dp

/**
 * 卡片流：窄屏一列（和 LazyColumn 一模一样），宽屏按 [minColumnWidth] 自动分成两三列的瀑布流。
 *
 * 给「一竖条卡片」的页面用（空闲教室、成绩、考勤、加餐券……）。以前这些页面宽屏时用
 * [readableWidth] 把一列卡片限在 720dp 居中，平板横屏左右各空一大块；硬拉满一列又是
 * 一张卡横跨整屏、字挤在左边。分列是这两种之间正确的做法。
 *
 * 用瀑布流（staggered）而不是等高网格：卡片高低不一（有的带展开的明细），等高网格会在
 * 矮卡片下面留出大块空白。搜索框、标签、汇总这种要横跨全宽的项用 [fullLineItem]。
 *
 * 注意列表状态是 [LazyStaggeredGridState]，不是 LazyListState：分页加载（滚到底加载更多）
 * 看 `layoutInfo.visibleItemsInfo` 的写法两边一样，直接换类型即可。
 */
@Composable
fun AdaptiveCardGrid(
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 12.dp,
    /**
     * 列与列之间的间距。卡片自己带左右 16dp 外边距的页面（原来是 LazyColumn 里的
     * `Card(Modifier.padding(horizontal = 16.dp))`）传 0：两张卡的外边距加起来就是 32dp 的列间距。
     */
    horizontalSpacing: Dp = spacing,
    minColumnWidth: Dp = 360.dp,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    val wide = isWideLayout()
    LazyVerticalStaggeredGrid(
        columns = if (wide) StaggeredGridCells.Adaptive(minColumnWidth) else StaggeredGridCells.Fixed(1),
        modifier = if (wide) {
            modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WideContentMaxWidth)
        } else {
            modifier
        },
        state = state,
        contentPadding = contentPadding,
        verticalItemSpacing = spacing,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        content = content,
    )
}

/**
 * 按行对齐的卡片网格：给**卡片会竖着展开**的页面用（空闲教室展开节次、成绩展开分项、成绩单展开学期）。
 *
 * 瀑布流（[AdaptiveCardGrid]）把每一项排进当前最短的那一列：一张卡展开变高，后面的卡就重新找
 * 最短列，从左边跑到右边，收起又跑回来。按行对齐时一张卡展开只撑高它自己那一行，
 * 别的卡原地不动，阅读顺序也固定是从左到右一行行读。代价是同一行里矮的卡下面留点空。
 *
 * 窄屏一列，和 LazyColumn 一样。横跨全宽的项同样用 [fullLineItem]（这里是 LazyGridScope 的重载）。
 */
@Composable
fun AdaptiveRowGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 12.dp,
    horizontalSpacing: Dp = spacing,
    minColumnWidth: Dp = 360.dp,
    content: LazyGridScope.() -> Unit,
) {
    val wide = isWideLayout()
    LazyVerticalGrid(
        columns = if (wide) GridCells.Adaptive(minColumnWidth) else GridCells.Fixed(1),
        modifier = if (wide) {
            modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WideContentMaxWidth)
        } else {
            modifier
        },
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        content = content,
    )
}

/** [AdaptiveRowGrid] 里横跨所有列的一项。 */
fun LazyGridScope.fullLineItem(
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyGridItemScope.() -> Unit,
) = item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = contentType, content = content)

/** [AdaptiveCardGrid] 里横跨所有列的一项（搜索框、分段标签、汇总卡、「加载更多」）。 */
fun LazyStaggeredGridScope.fullLineItem(
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyStaggeredGridItemScope.() -> Unit,
) = item(key = key, contentType = contentType, span = StaggeredGridItemSpan.FullLine, content = content)

/**
 * 宽屏时把一段本来是「一竖条卡片」的普通 Column 内容排成两栏：[first] 在左、[second] 在右，
 * 两栏各自从上往下排；窄屏按 first、second 的顺序竖着排，和原来一样。
 *
 * 给不在 Lazy 列表里、用 verticalScroll 的页面用（WebVPN、校历、体测这类内容不多的页）。
 */
@Composable
fun AdaptiveTwoColumns(
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    /** 同一栏里上下相邻两块之间的间距（窄屏时 first、second 连成一栏，也用它）。 */
    verticalSpacing: Dp = 0.dp,
    firstWeight: Float = 1f,
    secondWeight: Float = 1f,
) {
    if (!isWideLayout()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
            first()
            second()
        }
        return
    }
    Row(
        modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WideContentMaxWidth),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(firstWeight), verticalArrangement = Arrangement.spacedBy(verticalSpacing)) { first() }
        Column(Modifier.weight(secondWeight), verticalArrangement = Arrangement.spacedBy(verticalSpacing)) { second() }
    }
}

/**
 * 「左列表 + 右详情」分屏。
 *
 * 宽屏：左栏固定 [listWidth]，一条分隔线，右栏占满剩余宽度。
 * 窄屏：只渲染 [list]——怎么进详情（弹窗 / 全屏 Dialog / push 一页）由各页面保持自己原来的逻辑，
 * 这里不替它们决定。
 *
 * 返回键的统一约定：**详情有内容时先清详情，再走原返回逻辑**，由各页面自己写 `BackHandler`，
 * 因为「详情有没有内容」只有页面自己知道。
 */
@Composable
fun TwoPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listWidth: Dp = 360.dp,
) {
    if (!isWideLayout()) {
        list()
        return
    }
    Row(modifier.fillMaxSize()) {
        Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}
