package com.xjtu.toolbox.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BulletinMarkdown(text: String, bodyStyle: TextStyle = MiuixTheme.textStyles.body2) {
    val uriHandler = LocalUriHandler.current
    MarkdownText(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        onLink = { url -> runCatching { uriHandler.openUri(url.trim()) } },
        typesetMath = false,
        bodyStyle = bodyStyle,
    )
}
