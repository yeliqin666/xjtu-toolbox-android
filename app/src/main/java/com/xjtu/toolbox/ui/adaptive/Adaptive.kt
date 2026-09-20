package com.xjtu.toolbox.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
