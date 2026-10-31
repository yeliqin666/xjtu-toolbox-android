package com.xjtu.toolbox.html.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HTML 内容排版与视觉样式规范
 *
 * 配色一律先按**明亮模式**取一套（[light]），暗色模式不另取一套色，而是把这一套整体过一遍
 * `invert(100%) hue-rotate(180deg)`（推导见 [com.xjtu.toolbox.html.model.cssInvertHueRotate180]）。
 * 这么做的理由是正文里的颜色有三个
 * 来源，只有第一个能靠主题自动跟随：
 *
 * 1. 我们给的默认色（正文 / 链接 / 代码底 / 引用条）；
 * 2. 上游 HTML 里写死的行内 `color` / `background-color` —— **不跟随主题**，这是 issue 309
 *    「半黑半白」的根因：正文跟着主题变浅、写死的黑字不变；
 * 3. 图片 —— 完全不归主题管。
 *
 * 换成「整体反色」以后三条走同一条规则：写死的 `#000` 变白、写死的 `#FFF` 底变黑，与正文的
 * 相对关系保持不变。图片走同一套矩阵（[imageColorFilter]）：转成图片的公式、白底的文档截图
 * 都会翻成深底浅字，公式在暗色下就是白的。注意这一条对照片类图片同样生效——反色后是负片，
 * 这是「整层反色」这个做法的固有代价，换来的是公式与正文一定对得上。
 */
@Immutable
data class HtmlStyle(
    val textColor: Color,
    val linkColor: Color,
    val codeBackgroundColor: Color,
    val codeTextColor: Color,
    val quoteBorderColor: Color,
    val quoteBackgroundColor: Color,
    val dividerColor: Color,
    val bodyStyle: TextStyle,
    val imageCornerRadius: Dp = 8.dp,
    /**
     * 图片的像素滤镜。深色模式下是 `invert(100%) hue-rotate(180deg)` 的颜色矩阵（与文字同一套
     * 系数，见 [cssInvertHueRotateColorMatrix]），这样「白底黑字」的公式图会变成「黑底白字」——
     * 公式在深色页面上就成了白色。明亮模式下是 null（原样）。
     */
    val imageColorFilter: ColorFilter? = null,
    /**
     * 这份样式是否已经过 [invertHueRotate180]（即当前是深色模式）。
     *
     * 解析层要拿它决定行内写死的颜色要不要一起换算（[com.xjtu.toolbox.html.parser.HtmlParser]）。
     * 不另读一次主题，是为了让「解析用的样式」与「画出来的样式」永远是同一份，不会因为两处各读
     * 一次 `LocalIsDarkTheme` 而出现对不上的瞬间。
     */
    val isDarkInverted: Boolean = false,
    val blockSpacing: Dp = 10.dp,
    val paragraphSpacing: Dp = 8.dp,
) {
    companion object {
        /**
         * 明亮模式那一套色 —— 也就是「反色之前的原色」。
         *
         * 入参 [accent] 是链接取色。**它必须是明亮模式下的链接色**：深色模式调用时传固定的
         * [LightLink]，不能传 `MiuixTheme.colorScheme.primary`（那是深色模式的浅蓝，再反一次会
         * 变成深蓝、在深底上读不出来）。见 [default]。
         */
        @Composable
        fun light(accent: Color = LightLink): HtmlStyle {
            val textStyles = MiuixTheme.textStyles
            return HtmlStyle(
                // 明亮模式的墨色固定在近黑：它就是「反色前的原色」，暗色下由换算得到浅色。
                textColor = LightInk,
                linkColor = accent,
                codeBackgroundColor = LightInk.copy(alpha = 0.08f),
                codeTextColor = LightInk,
                quoteBorderColor = accent.copy(alpha = 0.7f),
                quoteBackgroundColor = LightInk.copy(alpha = 0.04f),
                dividerColor = LightInk.copy(alpha = 0.12f),
                // bodyStyle 的 color 也要钉成明亮模式的原色：MIUIX 的 textStyles 已经带当前主题的
                // 文字色，深色模式下它本身就是浅色，再反一次会翻成深色（渲染时虽被 textColor 覆盖，
                // 但反色逻辑必须拿「原色」算，否则这里就成了一个会随调用顺序变化的陷阱）。
                bodyStyle = textStyles.body2.copy(color = LightInk),
                imageCornerRadius = 8.dp,
                imageColorFilter = null,
                blockSpacing = 10.dp,
                paragraphSpacing = 8.dp,
            )
        }

        /**
         * 按当前主题取样式：浅色给 [light] 原样，深色给它的整体反色版。
         *
         * 所有调用方（包括 [com.xjtu.toolbox.html.HtmlContent] 的默认参数）都走这一个入口，
         * 别再各自判断深浅色，否则又会出现两套口径。
         */
        @Composable
        fun default(): HtmlStyle {
            if (!LocalIsDarkTheme.current) {
                // 浅色：链接跟随应用主题色（自定义主题色、Monet 都生效）。
                return light(accent = MiuixTheme.colorScheme.primary)
            }
            // 深色：反色前的原色必须取「明亮模式那一套」，而深色模式下拿不到浅色主题的 primary
            // （MiuixTheme.colorScheme 已经是深色那一套），所以链接固定用明亮模式的蓝。
            // 这条限制只影响「深色模式下的链接色是否跟随自定义主题色」，可读性不受影响。
            return light(accent = LightLink).invertHueRotate180()
        }

        /** 明亮模式的正文墨色（近黑，不用纯黑，与纸面反差更柔）。 */
        internal val LightInk = Color(0xFF1A1A1A)

        /** 明亮模式的链接蓝：与 [com.xjtu.toolbox.html.parser.HtmlInlineParser] 的无主题兜底色同值。 */
        internal val LightLink = Color(0xFF1E88E5)
    }

    /**
     * 整份样式过一遍 `invert(100%) hue-rotate(180deg)`。
     *
     * 正文色与各种底色都是纯色，逐色换算与 CSS 逐像素滤镜等价
     * （推导见 [com.xjtu.toolbox.html.model.cssInvertHueRotate180]）。
     * [bodyStyle] 自带的 `color` 也要一起换：某些调用方会 copy 出带颜色的正文样式，漏了它
     * 就会出现「段落色反了、里面的文字没反」。
     */
    internal fun invertHueRotate180(): HtmlStyle = copy(
        textColor = cssInvertHueRotate180(textColor),
        linkColor = cssInvertHueRotate180(linkColor),
        codeBackgroundColor = cssInvertHueRotate180(codeBackgroundColor),
        codeTextColor = cssInvertHueRotate180(codeTextColor),
        quoteBorderColor = cssInvertHueRotate180(quoteBorderColor),
        quoteBackgroundColor = cssInvertHueRotate180(quoteBackgroundColor),
        dividerColor = cssInvertHueRotate180(dividerColor),
        bodyStyle = bodyStyle.copy(
            color = cssInvertHueRotate180(
                if (bodyStyle.color == Color.Unspecified) textColor else bodyStyle.color
            ),
        ),
        imageColorFilter = ColorFilter.colorMatrix(cssInvertHueRotateColorMatrix()),
        isDarkInverted = true,
    )
}
