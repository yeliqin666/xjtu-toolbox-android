package com.xjtu.toolbox.html

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.html.model.HtmlStyle
import com.xjtu.toolbox.html.parser.HtmlParser
import com.xjtu.toolbox.html.renderer.HtmlBlockRenderer
import com.xjtu.toolbox.html.renderer.HtmlImageLoader

/**
 * HTML 内容 Compose 原生渲染组件。
 *
 * @param html HTML 富文本字符串
 * @param modifier 外部修饰符
 * @param style 排版与色彩样式，默认适配 MIUIX 主题与暗色模式
 * @param imageLoader 取图片字节（dev 上是 Coil + 已登录的 OkHttpClient；这里交给调用方，比如思源学堂走 `LmsSource.imageBytes` 带登录态）；
 *   null = 不加载图片，只显示说明文字
 * @param baseUrl 相对地址（`/api/...`、`//host/...`）补全用的站点根，如 `https://lms.xjtu.edu.cn`
 * @param onLinkClick 超链接点击回调，为 null 时使用系统默认行为处理
 */
@Composable
fun HtmlContent(
    html: String,
    modifier: Modifier = Modifier,
    style: HtmlStyle = HtmlStyle.default(),
    imageLoader: HtmlImageLoader? = null,
    baseUrl: String? = null,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    if (html.isBlank()) return

    // 解析只依赖这三样：链接色、代码底色（进 AnnotatedString），以及「要不要反色」。
    // 不把整个 style 当 key：它含 ColorFilter（非 data class，每次反色都会新建实例），
    // 拿它作 key 会让深色模式下的每次重组都重新解析一遍 HTML。
    val document = remember(html, style.linkColor, style.codeBackgroundColor, style.isDarkInverted, onLinkClick) {
        HtmlParser.parse(html, style = style, onLinkClick = onLinkClick)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(style.blockSpacing)
    ) {
        for (block in document.blocks) {
            HtmlBlockRenderer(
                block = block,
                style = style,
                imageLoader = imageLoader,
                baseUrl = baseUrl,
            )
        }
    }
}
