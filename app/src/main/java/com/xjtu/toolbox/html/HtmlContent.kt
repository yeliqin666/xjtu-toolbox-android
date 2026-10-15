package com.xjtu.toolbox.html

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.html.model.HtmlStyle
import com.xjtu.toolbox.html.parser.HtmlParser
import com.xjtu.toolbox.html.renderer.HtmlBlockRenderer
import okhttp3.Call

/**
 * HTML 内容 Compose 原生渲染组件。
 *
 * @param html HTML 富文本字符串
 * @param modifier 外部修饰符
 * @param style 排版与色彩样式，默认适配 MIUIX 主题与暗色模式
 * @param callFactory 网络请求工厂（如传递已登录的 [okhttp3.OkHttpClient] 带 Cookie 请求受保护的图片）
 * @param onLinkClick 超链接点击回调，为 null 时使用系统默认行为处理
 */
@Composable
fun HtmlContent(
    html: String,
    modifier: Modifier = Modifier,
    style: HtmlStyle = HtmlStyle.default(),
    callFactory: Call.Factory? = null,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    if (html.isBlank()) return

    val document = remember(html, style.linkColor, style.codeBackgroundColor, onLinkClick) {
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
                callFactory = callFactory,
            )
        }
    }
}
