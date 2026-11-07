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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一枚「有几个」的小圆标（`1` / `12` / `99+`）。
 *
 * **不采样、不模糊、不折射，就是一层半透明压在底下那层玻璃上**（2026-11 维护者定）。
 *
 * 为什么不采样：这枚圆标自己**不吃**模糊与折射（15dp 的尺度上那两层只会糊成一团），
 * 既然不糊不折，`drawBackdrop` 的离屏次采样就纯属白付——采了也只是把它当成一片要走
 * `vibrancy()` 的底图，而 `vibrancy` 那点饱和度提升在 15dp 上根本看不出来。所以这里
 * 只做三件不需要采样的事：**半透明染色 + 白细高光边 + 同色柔光投影**，直接叠在底下那块
 * 玻璃（悬浮条 / 圆钮）上面，透出它本身的颜色。这和「纯色背景下的卡片」是同一个道理：
 * 底上已经没有细节可糊可折时，卡面也只是半透明薄色叠上去，不再走 `drawBackdrop`。
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
    val hairline = Color.White.copy(alpha = if (dark) 0.18f else 0.66f)
    Box(
        modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            // 柔光：同一形状的阴影染成强调色，小圆标才像「钉在那块玻璃上的钉子」而不是贴纸
            .shadow(
                elevation = 4.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = tint.copy(alpha = 0.35f),
                spotColor = tint.copy(alpha = 0.55f),
            )
            .background(wash, CircleShape)
            .border(0.7.dp, hairline, CircleShape)
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
