package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.util.nowOnCampus
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.datetime.LocalDateTime
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页头卡液体的参数，对应 [LiquidBackground] 的同名参数。 */
data class LiquidMood(
    val colors: LiquidColors,
    val seed: Float,
    val speed: Float,
    val crease: Float,
    val animated: Boolean = true,
)

/**
 * 颜色以一天时段的配色为底、掺一部分主题主色，再按深浅模式和天色明暗压；
 * 流速随白天活跃、下一项安排临近而加快，折痕随临近加深；
 * 形状每天换一种，流速再带一点当天的随机。
 *
 * [appearance] 里用户显式选过的项会覆盖自动结果；`auto` 项保持上面的自动行为。
 */
@Composable
fun rememberLiquidMood(
    nextStartAt: LocalDateTime?,
    examSoon: Boolean,
    appearance: HeroAppearance = HeroAppearance(),
): LiquidMood {
    val primary = MiuixTheme.colorScheme.primary
    val dark = LocalIsDarkTheme.current
    val automatic = remember(primary, dark, nextStartAt, examSoon) {
        val now = nowOnCampus()
        val hour = now.hour + now.minute / 60f
        // 6 点到 22 点之间按正弦升落，正午 1，夜里 0
        val daylight = sin((hour - 6f) / 16f * PI).toFloat().coerceIn(0f, 1f)
        // 下一项一刻钟内开始算 1，三小时外算 0；有临近的考试再紧一档
        val minutes = nextStartAt?.takeIf { it.date == now.date }
            ?.let { (it.hour - now.hour) * 60 + it.minute - now.minute }
        val rush = minutes?.let { 1f - ((it - 15) / 165f).coerceIn(0f, 1f) } ?: 0f
        val tension = (rush + if (examSoon) 0.3f else 0f).coerceIn(0f, 1f)
        val day = Random(now.date.toEpochDays())

        val shade = (if (dark) 0.4f else 0.2f) + 0.25f * (1f - daylight)
        val base = paletteAt(hour)
        // 主题色只掺一部分，份量每天略有不同；底色跟着一天的时段走
        val tint = 0.25f + 0.2f * day.nextFloat()
        fun mix(c: Color) = lerp(c, primary, tint)
        val colors = LiquidColors(
            dark = lerp(mix(base.dark), Color.Black, 0.3f + 0.4f * shade),
            mid = lerp(mix(base.mid), Color.Black, shade).capLuminance(0.2f),
            // 白字压在最亮处也要立得住
            light = lerp(mix(base.light), Color.Black, 0.5f * shade).capLuminance(0.36f),
        )
        LiquidMood(
            colors = colors,
            seed = day.nextFloat() * 20f,
            speed = (0.55f + 0.35f * daylight + 0.6f * tension) * (0.9f + 0.2f * day.nextFloat()),
            crease = 0.5f + 0.9f * tension,
        )
    }
    return remember(automatic, appearance) { appearance.applyTo(automatic) }
}

internal fun HeroAppearance.applyTo(automatic: LiquidMood): LiquidMood {
    val selected = when (palette) {
        "midnight" -> LiquidPalettes.ClassicMidnight
        "orchid" -> LiquidPalettes.Orchid
        "blossom" -> LiquidPalettes.Blossom
        "lagoon" -> LiquidPalettes.Lagoon
        "honey" -> LiquidPalettes.Honey
        "custom" -> {
            val color = CourseColors.parseHex(colorHex) ?: LiquidPalettes.Midnight.mid
            LiquidColors(lerp(color, Color.Black, 0.7f), color, lerp(color, Color.White, 0.6f))
        }
        else -> null
    }
    val colors = when {
        selected == null -> automatic.colors
        // 午夜蓝是原版头卡那组手工调好的三色：白字和 scrim 已经按它校过，
        // 不再套自动压暗，保证和旧版逐值一致。
        palette == "midnight" -> selected
        else -> LiquidColors(
            selected.dark.capLuminance(0.06f),
            selected.mid.capLuminance(0.2f),
            selected.light.capLuminance(0.36f),
        )
    }
    return automatic.copy(
        colors = colors,
        seed = if (seed < 0) automatic.seed else seed / 100f,
        speed = when (motion) {
            "gentle" -> 0.35f
            "flowing" -> 0.85f
            "lively" -> 1.6f
            else -> automatic.speed
        },
        crease = when (motion) {
            "gentle" -> 0.25f
            "flowing" -> 0.65f
            "lively" -> 1.25f
            else -> automatic.crease
        },
        animated = motion != "still",
    )
}

/** 一天里的底色：夜里午夜蓝，清晨蜜橙，上午湖绿，午后回到蓝，傍晚粉，入夜紫。相邻两档之间渐变。 */
private val DAY_PALETTES = listOf(
    0f to LiquidPalettes.Midnight, 6f to LiquidPalettes.Honey, 9f to LiquidPalettes.Lagoon,
    14f to LiquidPalettes.Midnight, 18f to LiquidPalettes.Blossom, 21f to LiquidPalettes.Orchid,
    24f to LiquidPalettes.Midnight,
)

private fun paletteAt(hour: Float): LiquidColors {
    val i = DAY_PALETTES.indexOfLast { it.first <= hour }.coerceIn(0, DAY_PALETTES.lastIndex - 1)
    val (h0, a) = DAY_PALETTES[i]
    val (h1, b) = DAY_PALETTES[i + 1]
    val t = ((hour - h0) / (h1 - h0)).coerceIn(0f, 1f)
    return LiquidColors(lerp(a.dark, b.dark, t), lerp(a.mid, b.mid, t), lerp(a.light, b.light, t))
}

private fun Color.capLuminance(max: Float): Color {
    var c = this
    repeat(8) { if (c.luminance() > max) c = lerp(c, Color.Black, 0.15f) }
    return c
}
