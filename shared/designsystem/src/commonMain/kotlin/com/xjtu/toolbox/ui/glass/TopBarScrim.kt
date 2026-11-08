package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * 顶栏遮罩：**不采样、不模糊**，只是一条「暗色黑 / 亮色白 → 透明」的竖向渐变。
 * 只画不量：不改顶栏尺寸，内容排版和留白一概不受影响。
 *
 * 为什么最后不做了（2026-11，第五次真机反馈后维护者拍板）：顶栏这一块要的其实只有
 * 「把滚上来的内容压暗 / 压亮、让标题看得清」，而为了它去采样 + 模糊，每帧要付一次整屏
 * 离屏采样加一次高斯模糊——七层、两级、单层都试过，最省的那版仍是这条渐变的几十倍。
 * 于是顶栏**不再采样**，直接上渐变色：暗色纯黑、亮色纯白，从顶边实色一路淡到透明。
 *
 * 注意这里刻意不再接 `Backdrop`：顶栏不要采样源，调用方连采样层都不用录
 * （`layerBackdrop` 那一次整屏离屏渲染可以整个省掉）。
 *
 * [titled] 的顶栏上半段多压一点，保证大标题、返回键那一段的字足够清楚；
 * 只有圆钮、没有标题的（首页）从顶边到底边线性淡出。
 */
@Composable
fun Modifier.topBarScrim(titled: Boolean = true): Modifier {
    val scrim = if (LocalIsDarkTheme.current) Color.Black else Color.White
    // 收敛色必须带同一个 RGB（`scrim.copy(alpha = 0f)`），不能写 Color.Transparent：
    // 前者是「同色淡出」，后者在渐变里会往透明黑插值，白遮罩下端会泛灰
    val brush = if (titled) Brush.verticalGradient(
        0f to scrim,
        0.55f to scrim.copy(alpha = 0.78f),
        1f to scrim.copy(alpha = 0f),
    ) else Brush.verticalGradient(0f to scrim, 1f to scrim.copy(alpha = 0f))
    return drawBehind { drawRect(brush) }
}
