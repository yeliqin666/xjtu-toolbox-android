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
import androidx.compose.ui.graphics.Brush
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
 * 观感 = **纯色背景下的液态玻璃卡片**那一套（`HomeTileBoard` 的 `flatGlassFill`、
 * `HomeTileFaceBox` 的 `blurGlass = false` 分支）：一层很淡的染色玻璃面 + 顶部一道白色顶光 +
 * 0.7dp 白细高光边 + 一圈同色柔光投影。数字用主题色（铃铛那枚传品牌红）。
 *
 * **不模糊、不折射，也绝不采样背景**（2026-11 维护者定）：15dp 的尺度上模糊与折射只会糊成
 * 一团，还不够付那次离屏采样；「玻璃感」全靠**顶光 + 高光边 + 柔光投影**三样做出来，所以它在
 * 任何背景（纯色 / 渐变 / 壁纸 / 别人家的顶栏）上都成立，也不依赖调用方手里有没有 `Backdrop`。
 *
 * 染色面的透明度按明暗各取一档：玻璃要透出底下一点东西才算玻璃，透太多字又压不住——浅色下
 * 面本身亮、叠淡一点就够；深色下要更浓一点才看得出那层「有色玻璃」。
 *
 * 放在 `:shared:designsystem` 而不是某个 feature 里，是因为首页悬浮条与通知铃铛分属两个 feature，
 * 而 feature 之间不许互相依赖。
 *
 * @param count 数目；<=0 时由调用方决定要不要画（这里不吞 0，画出来就是一枚 `0`）。
 * @param accent 强调色（数字与柔光），默认跟随主题色。
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, accent: Color = Color.Unspecified) {
    val dark = LocalIsDarkTheme.current
    val tint = accent.takeIf { it != Color.Unspecified } ?: MiuixTheme.colorScheme.primary
    val label = if (count > 99) "99+" else count.toString()
    // 第一层：卡片那张「薄色玻璃面」本身（HomeTileFaceBox 的 flatGlassFill 同一组值），
    // 浅色更白、深色更深——小圆标因此先是一块玻璃，再谈颜色
    val glassFill = if (dark) Color.Black.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.52f)
    // 第二层：一层淡染色，把「这是一枚带颜色的玻璃钉」说出来。压太浓就成实心彩点（以前就是那样）
    val wash = tint.copy(alpha = if (dark) 0.42f else 0.34f)
    // 顶光：从顶上压下来的一道白，越往下越淡——玻璃「被光打亮」的那一条
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.20f else 0.46f),
        0.6f to Color.White.copy(alpha = 0.06f),
        1f to Color.Transparent,
    )
    // 高光边：和卡片同一档浓度（浅色更明显）
    val hairline = Color.White.copy(alpha = if (dark) 0.18f else 0.66f)
    // 数字：玻璃面是淡的，字就得用**比染色更深**的墨色才压得住——深色下把强调色往白里提，
    // 浅色下往黑里压（浅底上直接写主题色，9.5sp 的小字会发飘）
    val ink = if (dark) lerp(tint, Color.White, 0.45f) else lerp(tint, Color.Black, 0.3f)
    Box(
        modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            // 柔光：同一形状的阴影，ambient/spot 都染成强调色，小圆标才有「浮起来的玻璃钉」的观感
            .shadow(
                elevation = 4.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = tint.copy(alpha = 0.35f),
                spotColor = tint.copy(alpha = 0.55f),
            )
            .background(glassFill, CircleShape)
            .background(wash, CircleShape)
            .background(sheen, CircleShape)
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
