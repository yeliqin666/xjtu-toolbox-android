package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一枚「有几个」的小圆标（`1` / `12` / `99+`）。
 *
 * **走的是和卡片同一套液态玻璃**（`com.kyant.backdrop` 的 `drawBackdrop`），只是把 `blur()`
 * 与 `lens()` 两层关掉（2026-11 维护者定）：15dp 的尺度上模糊和折射只会糊成一团，还不够付那次
 * 离屏位移采样；剩下的 `vibrancy()`（活力）+ `Highlight`（轮廓高光）+ `floatingGlassShadow`
 * （环境光投影）三样已经足以让它看起来是一小块被光打亮的玻璃。这与「纯色背景下的液态玻璃卡片」
 * 完全同一条路子（`HomeTileFaceBox` 的 `blurGlass = false` 分支），不是另画一套。
 *
 * [backdrop] 为 null（这一帧没有采样源、或这一端不做液态玻璃）时退回「薄色 + 高光边 + 柔光」，
 * 至少不会画成一片透明。
 *
 * 放在 `:shared:designsystem` 而不是某个 feature 里，是因为首页悬浮条与通知铃铛分属两个 feature，
 * 而 feature 之间不许互相依赖。
 *
 * @param count 数目；<=0 时由调用方决定要不要画（这里不吞 0，画出来就是一枚 `0`）。
 * @param accent 染色与柔光的强调色，默认跟随主题色。
 * @param backdrop 采样源（卡片层 / 内容层都行，调用方给什么就用什么）。
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    backdrop: Backdrop? = null,
) {
    val dark = LocalIsDarkTheme.current
    val tint = accent.takeIf { it != Color.Unspecified } ?: MiuixTheme.colorScheme.primary
    val label = if (count > 99) "99+" else count.toString()
    // 数字：玻璃面是半透的，字得比染色更深/更亮才压得住
    val ink = if (dark) lerp(tint, Color.White, 0.45f) else lerp(tint, Color.Black, 0.3f)
    // 染色层：同一块玻璃上的「有色」那一点点，压太浓就成实心彩点（以前就是那样）
    val wash = tint.copy(alpha = if (dark) 0.50f else 0.42f)
    // 退回方案用的薄色（与 HomeTileFaceBox 的 flatGlassFill 同一组值）
    val flatFill = if (dark) Color.Black.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.52f)
    val hairline = Color.White.copy(alpha = if (dark) 0.18f else 0.66f)
    val glow = remember(tint, dark) { floatingGlassShadow(tint, dark) }

    val material = when {
        backdrop != null -> Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                // 采样范围往外扩一圈：边缘处也有真实内容可混，高光才不会断
                padding = maxOf(padding, 5.dp.toPx())
                vibrancy()
                // 这里**故意不写** blur() 与 lens()：见类注释
            },
            highlight = { Highlight.Default.copy(alpha = 0.65f) },
            shadow = { glow },
            onDrawSurface = { drawRect(wash) },
        )
        else -> Modifier
            .shadow(4.dp, CircleShape, clip = false, ambientColor = tint.copy(alpha = 0.35f), spotColor = tint.copy(alpha = 0.55f))
            .background(flatFill, CircleShape)
            .background(wash, CircleShape)
            .border(0.7.dp, hairline, CircleShape)
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
