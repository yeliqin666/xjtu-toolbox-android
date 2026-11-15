package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * 通用 COUI 玻璃面板：圆角矩形 + 原厂浮层材质（AGSL 材质、边缘光、内光晕）。
 *
 * 给小岱输入框、屁岱主动气泡这类「内容层上面的浮层」用。采样源用 [pageBackdrop]
 * （由调用方决定采哪一层：输入框采聊天列表那层、气泡采页面内容那层），
 * **不能**直接把浮层自己录进去的那层传进来——那会形成 RenderNode 环。
 *
 * 拿不到采样源（经典风格 / 非 Android 平台）时退回 [CouiGlassPanelFallback]：
 * 纯白或纯黑实底，不带任何半透明玻璃感——和「界面质感：经典」是同一种观感。
 */
@Composable
expect fun CouiGlassPanel(
    pageBackdrop: CouiPageBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    /** 面板染色（含 alpha）；[Color.Unspecified] 用主题表面色。 */
    tint: Color = Color.Unspecified,
    /** 兜底实底颜色；[Color.Unspecified] = 纯白（浅色）/ 纯黑（深色）。承白字的表面要传自己的主色。 */
    fallbackColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
)

/**
 * 没有 COUI 材质时的兜底面板：纯白（浅色）/ 纯黑（深色）实底。
 *
 * 用户定的规矩：兜底一律纯白或纯黑，不要再摆半透明玻璃（那会让人以为是玻璃没生效）。
 */
@Composable
internal fun CouiGlassPanelFallback(
    modifier: Modifier,
    cornerRadius: Dp,
    fallbackColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val fill = if (fallbackColor == Color.Unspecified) {
        if (dark) Color.Black else Color.White
    } else {
        fallbackColor
    }
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(fill),
    ) {
        content()
    }
}
