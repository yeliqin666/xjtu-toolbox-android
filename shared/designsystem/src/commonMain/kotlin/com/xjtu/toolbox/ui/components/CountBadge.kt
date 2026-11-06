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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一枚「有几个」的小圆标（`1` / `12` / `99+`）：半透明主题色 + 白色细高光边 + 一圈同色柔光投影。
 *
 * 视觉配方与首页顶栏铃铛那枚未读角标（`feature/notice` · `InboxScreen` 的 `UnreadBadge`）**同源**
 * ：它也是「半透明底色 + 0.6dp 白边 + 4dp 同色柔光」——都是 15dp 上下的小面积，**绝不采样背景**
 * （那个尺度上模糊 / 折射只会糊成一团，还多付一次离屏采样，见 home-interaction-spec §A14）。
 * 区别只有一个：那枚是红的（表示「要处理」），这枚吃主题色（表示「有这么多个」，不是警告）。
 *
 * 放在 `:shared:designsystem` 而不是某个 feature 里，是因为首页悬浮条与通知铃铛分属两个 feature，
 * 而 feature 之间不许互相依赖；这套配方以后别处要用（比如底栏角标）也直接复用。
 *
 * @param count 数目；<=0 时由调用方决定要不要画（这里不吞 0，画出来就是一枚 `0`）。
 * @param accent 底色，默认跟随主题色。
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, accent: Color = Color.Unspecified) {
    val tint = accent.takeIf { it != Color.Unspecified } ?: MiuixTheme.colorScheme.primary
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            // 柔光：同一形状的阴影，ambient/spot 都染成底色，小圆标才有「发光钉在玻璃上」的观感
            .shadow(
                elevation = 4.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = tint.copy(alpha = 0.5f),
                spotColor = tint.copy(alpha = 0.7f),
            )
            .background(tint.copy(alpha = 0.92f), CircleShape)
            .border(0.6.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 3.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 9.5.sp,
            lineHeight = 9.5.sp,
            fontWeight = FontWeight.Bold,
            // 底色是主题色：白字在浅色主题下也够对比（铃铛那枚同样用白字）
            color = Color.White,
        )
    }
}
