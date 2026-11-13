package com.xjtu.toolbox.ui.glass.coui

import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** COUI 语义排版预设，可通过 [rememberCouiTextStyle] 获取 Compose 样式。 */
enum class CouiTextKind(internal val size: Float, internal val family: String, internal val weight: Int) {
    /** 列表项的主文字，例如选项名或操作名。 */
    ItemTitle(16f, "sans-serif-medium", 500),

    /** 列表项的次要文字，用于补充说明主文字。 */
    ItemSummary(13f, "sans-serif", 400),

    /** 紧凑顶栏的参考排版。顶栏默认标题取此处的字号，字体和字重沿用 [HomeTitle]。 */
    TopBar(18f, "sans-serif-medium", 550),

    /** 首页和大标题顶栏的展开样式，也是普通顶栏默认字体与字重的来源。 */
    HomeTitle(40f, "sans-serif-semibold", 700),

    /** 常驻搜索栏的输入文字和占位提示。 */
    Search(15f, "sans-serif", 400),

    /** 列表项末端的当前值或状态，字号以 dp 固定。 */
    ItemValue(14f, "sans-serif", 400),

    /** 分组容器上方的标题。 */
    GroupTitle(12f, "sans-serif-medium", 500),

    /** 分组下方的说明文字，默认从起始侧对齐。 */
    GroupFooter(12f, "sans-serif", 400),
}

/** 系统资源决定排版；列表项标题和说明文字默认起始对齐。 */
@Composable
fun Text(modifier: Modifier = Modifier, text: String, kind: CouiTextKind, color: Color = Color.Unspecified,
    onLineCount: (Int) -> Unit = {}, textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    val style = rememberCouiTextStyle(kind)
    BasicText(text, modifier,
        style = style.copy(color = color.takeOrElse { CouiTheme.contentColor },
            textAlign = textAlign ?: when (kind) {
                CouiTextKind.ItemTitle, CouiTextKind.ItemSummary,
                CouiTextKind.GroupFooter -> TextAlign.Start
                else -> TextAlign.Center
            }),
        onTextLayout = { onLineCount(it.lineCount) }, maxLines = maxLines, overflow = overflow)
}

@Composable
fun rememberCouiTextStyle(kind: CouiTextKind): TextStyle = rememberCouiTextStyle(kind, kind.weight)

@Composable
internal fun rememberCouiSystemTextStyle(kind: CouiTextKind, size: Float = kind.size): TextStyle {
    val weight = kind.weight
    val adjustment = LocalConfiguration.current.fontWeightAdjustment
    // Compose 的已加载字体会直接返回 Typeface，需在创建字体前应用系统字重补偿。
    val adjustedWeight = if (adjustment == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) weight
        else (weight + adjustment).coerceIn(1, 1000)
    return rememberCouiTextStyle(kind, adjustedWeight, size)
}

@Composable
private fun rememberCouiTextStyle(kind: CouiTextKind, weight: Int, size: Float = kind.size): TextStyle {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(kind, weight, size, configuration, density) {
        val typeface = Typeface.create(Typeface.create(kind.family, Typeface.NORMAL), weight, false)
        val pixels = if (kind == CouiTextKind.ItemValue) size * density.density
            else with(density) { size.sp.toPx().roundToInt().toFloat() }
        couiTextStyle(typeface, weight, pixels, couiLineSpacing(kind),
            includeFontPadding = kind != CouiTextKind.TopBar && kind != CouiTextKind.HomeTitle,
            density = density)
    }
}

private fun couiLineSpacing(kind: CouiTextKind): Float = when (kind) {
    CouiTextKind.GroupTitle -> 1.143f
    CouiTextKind.GroupFooter -> 1.143f
    CouiTextKind.ItemTitle -> 1.158f
    CouiTextKind.TopBar -> 1.1429f
    CouiTextKind.ItemSummary -> 1.1739f
    CouiTextKind.HomeTitle -> 1f
    CouiTextKind.Search -> 1.1321f
    CouiTextKind.ItemValue -> 1.2245f
}
