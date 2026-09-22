package com.xjtu.toolbox.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.shadow.Shadow

/**
 * 悬浮玻璃（底栏胶囊、屁岱气泡这类真正浮在内容上面的东西）的阴影。
 *
 * 阴影只给「浮起来」的东西：贴边的顶栏不投影（见 [glassBarSurface]），卡片用
 * [com.xjtu.toolbox.ui.components.appCardShadow]。
 *
 * 不用 kyant 的默认值（24dp 半径、向下 6dp、10% 纯黑）：纯黑阴影在浅色页面上是一块脏灰，
 * 显得老气。这里把黑色往主题色里掺四成，半径放大、偏移加大、浓度压低，影子更散、更柔，
 * 带一点主题色的环境光，看起来是被页面的光托起来的。深色模式下背景本来就暗，
 * 阴影得浓一些才看得出。
 */
fun floatingGlassShadow(accent: Color, isDark: Boolean): Shadow = Shadow(
    radius = 28.dp,
    offset = DpOffset(0.dp, 10.dp),
    color = lerp(Color.Black, accent, 0.4f).copy(alpha = if (isDark) 0.45f else 0.16f),
)
