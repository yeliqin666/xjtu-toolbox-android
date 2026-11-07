package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.glass.glassHairline
import com.xjtu.toolbox.ui.glass.glassSheen
import com.xjtu.toolbox.ui.glass.staticGlassShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一枚「有几个」的小圆标（`1` / `12` / `99+`）。
 *
 * **不采样，但液态玻璃的效果一样不少**（2026-11 维护者定）：只有「必须采样才画得出来」的
 * 那几样才不做——模糊、折射、以及 `vibrancy` 对底色的饱和度提升（15dp 的尺度上那两层只会
 * 糊成一团，`vibrancy` 在这么小的面积上也看不出来）。其余全部保留，用纯绘制的方式补：
 * 半透明染色面 + 顶部打光（[glassSheen]）+ 轮廓高光（[glassHairline]）+ 同色环境光投影
 * （[staticGlassShadow]，与卡片的 `floatingGlassShadow` 同一组参数）。这样它才像一枚
 * 「钉在玻璃上的小钉子」，而不是一张贴纸。
 *
 * 放在 `:shared:designsystem` 而不是某个 feature 里，是因为首页悬浮条与通知铃铛分属两个 feature，
 * 而 feature 之间不许互相依赖。
 *
 * @param count 数目；<=0 时由调用方决定要不要画（这里不吞 0，画出来就是一枚 `0`）。
 * @param accent 染色与柔光的强调色，默认跟随主题色。
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, accent: Color = Color.Unspecified) {
    val dark = LocalIsDarkTheme.current
    val tint = accent.takeIf { it != Color.Unspecified } ?: MiuixTheme.colorScheme.primary
    val label = if (count > 99) "99+" else count.toString()
    // 数字：底色是半透明染色，字要更深（浅色）/ 更亮（深色）才压得住
    val ink = if (dark) lerp(tint, Color.White, 0.45f) else lerp(tint, Color.Black, 0.3f)
    // 染色面：比实心色淡得多——它的作用是「往底下那块玻璃里掺一点色」，不是盖住它
    val wash = tint.copy(alpha = if (dark) 0.50f else 0.42f)
    Box(
        modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            // 环境光投影：与卡片同一组参数，小圆标才像浮在玻璃上的钉子
            .staticGlassShadow(tint, dark, CircleShape)
            .background(wash, CircleShape)
            .background(glassSheen(dark), CircleShape)
            .border(0.7.dp, glassHairline(dark), CircleShape)
            .padding(horizontal = 3.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 9.5.sp,
            lineHeight = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = ink,
        )
    }
}
