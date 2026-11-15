package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.glass.CouiGlassPanel
import com.xjtu.toolbox.ui.glass.LocalCouiPageBackdrop
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一枚「有几个」的小圆标（`1` / `12` / `99+`）。
 *
 * 材质用 [CouiGlassPanel]：顶栏 / 底栏里有 COUI 采样源时就是原厂浮层材质（AGSL、边缘光、内光晕），
 * 拿不到采样源（经典风格、模态层里）时落到兜底的染色实底。旧的 kyant `Highlight` +
 * `floatingGlassShadow` 那套（`drawBackdrop(sampling = false)`）已删掉，不再和首页卡片共用参数。
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
    CouiGlassPanel(
        pageBackdrop = LocalCouiPageBackdrop.current,
        modifier = modifier,
        cornerRadius = 7.5.dp,
        tint = wash,
        // 兜底（没有采样源）用染色实底：角标上承的是深色数字，不能是纯白 / 纯黑。
        fallbackColor = tint.copy(alpha = if (dark) 0.9f else 0.82f),
    ) {
        Box(
            Modifier.height(15.dp).widthIn(min = 15.dp).padding(horizontal = 3.5.dp),
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
}
