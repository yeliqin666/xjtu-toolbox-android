package com.xjtu.toolbox.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior
import kotlin.math.roundToInt

/**
 * COUI 折叠顶栏的「双轨滚动」补偿工具。
 *
 * COUI 的顶栏可见位置 `offsetPx` 由临界阻尼弹簧追逻辑滚动 `contentOffsetPx`，快速滑动时会滞后。
 * 把 `contentTranslationY = offsetPx - contentOffsetPx` 加进滚动内容的顶部留白，列表会先追进
 * 顶栏下面的空隙、松手后再被弹簧拉回原位——就是 COUI demo 搜索栏和列表之间那种“磁铁”手感。
 *
 * 这两个工具都**在布局阶段**读 `contentTranslationY`，不把整页的重组拖进每一帧。
 */
@Composable
fun rememberCouiScrollContentPadding(
    base: PaddingValues,
    behavior: CouiTopBarScrollBehavior?,
): PaddingValues {
    val density = LocalDensity.current
    val baseTop = base.calculateTopPadding()
    return remember(base, behavior, density) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) =
                base.calculateLeftPadding(layoutDirection)

            override fun calculateRightPadding(layoutDirection: LayoutDirection) =
                base.calculateRightPadding(layoutDirection)

            override fun calculateTopPadding(): Dp {
                val extra = with(density) { (behavior?.contentTranslationY ?: 0f).toDp() }
                return (baseTop + extra).coerceAtLeast(0.dp)
            }

            override fun calculateBottomPadding() = base.calculateBottomPadding()
        }
    }
}

/** 用同一套差值撑开/收缩一个滚动内容里的顶部空白（[Spacer] 专用，布局阶段读状态）。 */
@Composable
fun Modifier.couiTopBarSpace(base: Dp, behavior: CouiTopBarScrollBehavior?): Modifier {
    val density = LocalDensity.current
    return this.layout { _, constraints ->
        val extraPx = (behavior?.contentTranslationY ?: 0f).roundToInt()
        val height = (with(density) { base.roundToPx() } + extraPx).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {}
    }
}
