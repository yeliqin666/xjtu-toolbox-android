package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一枚「有几个」的小圆标（`1` / `12` / `99+`）。
 *
 * **和卡片同一套液态玻璃的参数，但不采样**（2026-11 维护者定）：轮廓高光是同一份
 * `Highlight.Default.copy(alpha = 0.65f)`、环境光投影是同一份 [floatingGlassShadow]，
 * 走 `drawBackdrop` 但 **`sampling = false`**（vendored backdrop 的开关，见 
 * `thirdparty/backdrop/README.md`）：不录采样层、不跑 `effects`、不建离屏层，而高光与投影
 * 仍由库自己画——这枚 15dp 的小圆标下面本来就没有可采的东西，`vibrancy` / `blur` / `lens` 
 * 一个都用不上。参数只有一处来源，所以它和卡片之间不会慢慢漂成两套材质。
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
    val glow = remember(tint, dark) { floatingGlassShadow(tint, dark) }
    val rim = remember { Highlight.Default.copy(alpha = 0.65f) }

    // 和首页卡片同一条路：sampling = false 只跳过「录一层采样结果」，高光 / 投影由库自己画，
    // 所以这枚小圆标与卡片之间不会漂成两套材质
    val material = remember(wash, glow, rim) {
        Modifier.drawBackdrop(
            backdrop = emptyBackdrop(),
            shape = { CircleShape },
            sampling = false,
            effects = {},
            highlight = { rim },
            shadow = { glow },
            onDrawSurface = { drawRect(wash) },
        )
    }
    Box(
        modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            .then(material)
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
