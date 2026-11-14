package com.xjtu.toolbox.ui.glass.coui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt

/**
 * 大标题与可选常驻搜索槽；省略搜索槽时，标题缩小后固定在顶栏，不淡出。
 * state 的连接须挂在实际内容滚动容器的祖先。
 * dark 只控制本顶栏，搜索槽由宿主传入相同模式或使用共同的局部主题；标题色 alpha 与动画相乘。
 */
@Composable
fun CollapsingTopAppBar(
    modifier: Modifier = Modifier,
    state: CouiCollapsingAppBarState,
    searchState: CouiSearchBarState = rememberCouiSearchBarState(false),
    title: String,
    color: Color = Color.Unspecified,
    backdrop: CouiBackdrop = checkNotNull(LocalCouiBackdrop.current) {
        "顶栏需要显式 backdrop 或 ProvideCouiBackdrop 作用域"
    },
    dark: Boolean = CouiTheme.isDark,
    searchBar: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val hasSearchBar = searchBar != null
    val searchActive = hasSearchBar && searchState.active
    val titleColor = if (color == Color.Unspecified) CouiTheme.colorSchemeFor(dark).onSurface else color
    val titleStyle = if (hasSearchBar) CouiTopAppBarDefaults.expandedTitleTextStyle() else remember(density) {
        val typeface = Typeface.create(Typeface.create("sans-serif-semibold", Typeface.NORMAL), 700, false)
        couiTextStyle(typeface, 700, with(density) { CouiCollapsingAppBarDefaults.expandedTitleSize.toPx() },
            1f, includeFontPadding = false, density = density)
    }
    val descent = remember(titleStyle, density) { Paint().apply {
        typeface = Typeface.create(Typeface.create("sans-serif-semibold", Typeface.NORMAL),
            titleStyle.fontWeight?.weight ?: 700, false)
        textSize = with(density) { titleStyle.fontSize.toPx() }
    }.fontMetrics.descent }
    val titleHeight = with(density) {
        (if (hasSearchBar) CouiCollapsingAppBarDefaults.titleHeight else CouiCollapsingAppBarDefaults.titleOnlyHeight)
            .roundToPx().toFloat()
    }
    val collapsedHeight = with(density) { CouiCollapsingAppBarDefaults.collapsedTitleHeight.roundToPx().toFloat() }
    val titleBottom = with(density) { CouiCollapsingAppBarDefaults.titleBottom.toPx().toInt().toFloat() }
    val start = with(density) {
        (if (hasSearchBar) CouiCollapsingAppBarDefaults.titleStart else CouiCollapsingAppBarDefaults.titleOnlyStart)
            .toPx().toInt().toFloat()
    }
    val offset = state.offsetPx.coerceIn(0f, if (hasSearchBar) titleHeight else titleHeight - collapsedHeight)
    val f = (offset / (titleHeight - collapsedHeight)).coerceIn(0f, 1f)
    val titleFraction = if (hasSearchBar) f else CouiCollapsingAppBarDefaults.titleEasing.transform(f)
    val collapsedScale = if (hasSearchBar) .7f else
        CouiCollapsingAppBarDefaults.collapsedTitleSize / CouiCollapsingAppBarDefaults.expandedTitleSize
    val p = if (hasSearchBar) searchState.pageProgress.coerceIn(0f, 1f) else 0f
    SideEffect {
        state.updateRange(density, hasSearchBar, CouiCollapsingAppBarDefaults.titleOnlyHeight,
            CouiCollapsingAppBarDefaults.collapsedTitleHeight)
        state.updateSearch(hasSearchBar && searchState.expanded, searchActive)
    }
    CouiTopAppBarSurface(modifier.fillMaxWidth(), backdrop,
        referenceBlurRadius = with(density) { CouiCollapsingAppBarDefaults.referenceBlurRadiusPx.toDp() },
        bottomExtension = if (hasSearchBar) CouiCollapsingAppBarDefaults.searchBlurExtension
            else CouiCollapsingAppBarDefaults.titleOnlyBlurExtension,
        gradientSpeed = 3f,
        // 正文直接跟随已接受的滚动，标题保留弹簧跟随；模糊不能等标题追上后才补亮。
        backgroundAlpha = if (!hasSearchBar || searchActive) 1f else
            (state.contentOffsetPx / (30 * density.density)).coerceIn(0f, 1f),
        topExtension = { with(density) { offset.toDp() } }) {
        Layout(modifier = Modifier.statusBarsPadding(), content = {
            BasicText(title, Modifier.semantics { heading() }.graphicsLayer {
                alpha = if (hasSearchBar) (1f - f) * searchState.contentAlpha else 1f
                scaleX = (1f - (1f - collapsedScale) * titleFraction) *
                    (if (hasSearchBar) .8f + .2f * searchState.contentAlpha else 1f)
                scaleY = scaleX
                translationY = -200f * p
                transformOrigin = TransformOrigin(if (layoutDirection == LayoutDirection.Rtl) 1f else 0f, 0f)
            }, style = titleStyle.copy(color = titleColor),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (searchBar != null) Box(propagateMinConstraints = true) { searchBar() }
        }) { children, constraints ->
            val titleNode = children[0].measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = (constraints.maxWidth - 2 * start.roundToInt()).coerceAtLeast(0)))
            val search = children.getOrNull(1)?.measure(constraints.copy(minWidth = constraints.maxWidth, minHeight = 0))
            val remainingTitle = (titleHeight - offset).coerceAtLeast(if (hasSearchBar) 0f else collapsedHeight)
            val searchY = remainingTitle
            layout(constraints.maxWidth, (searchY + (search?.height ?: 0)).roundToInt()) {
                val expandedY = if (hasSearchBar) titleHeight - titleBottom - titleNode.height + descent
                    else with(density) { CouiCollapsingAppBarDefaults.titleOnlyTop.toPx() }
                val collapsedY = titleHeight - collapsedHeight +
                    (collapsedHeight - titleNode.height * (if (hasSearchBar) 1f else collapsedScale)) / 2
                val titleY = expandedY + (collapsedY - expandedY) * titleFraction - offset
                titleNode.placeRelative((start - if (hasSearchBar) (8 * density.density) * f else 0f).roundToInt(),
                    titleY.roundToInt())
                search?.placeRelative(0, searchY.roundToInt())
            }
        }
    }
}
