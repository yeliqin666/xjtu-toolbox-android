package com.xjtu.toolbox.html.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.html.model.HtmlBlock
import com.xjtu.toolbox.html.model.HtmlStyle
import com.xjtu.toolbox.html.model.ParagraphPart
import okhttp3.Call
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HtmlBlockRenderer(
    block: HtmlBlock,
    style: HtmlStyle,
    callFactory: Call.Factory? = null,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is HtmlBlock.Heading -> {
            HeadingRenderer(block, style, modifier)
        }
        is HtmlBlock.Paragraph -> {
            ParagraphRenderer(block, style, callFactory, modifier)
        }
        is HtmlBlock.CodeBlock -> {
            CodeBlockRenderer(block, style, modifier)
        }
        is HtmlBlock.BlockQuote -> {
            BlockQuoteRenderer(block, style, callFactory, modifier)
        }
        is HtmlBlock.ListBlock -> {
            ListRenderer(block, style, callFactory, modifier)
        }
        is HtmlBlock.HorizontalRule -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(style.dividerColor)
            )
        }
        is HtmlBlock.ImageBlock -> {
            HtmlImage(
                url = block.url,
                alt = block.alt,
                callFactory = callFactory,
                cornerRadius = style.imageCornerRadius,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun ParagraphRenderer(
    paragraph: HtmlBlock.Paragraph,
    style: HtmlStyle,
    callFactory: Call.Factory?,
    modifier: Modifier = Modifier,
) {
    val spacing = if (paragraph.isPureImage) 0.dp else 6.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        for (part in paragraph.parts) {
            when (part) {
                is ParagraphPart.Text -> {
                    Text(
                        text = part.text,
                        style = style.bodyStyle,
                        color = style.textColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is ParagraphPart.Image -> {
                    HtmlImage(
                        url = part.url,
                        alt = part.alt,
                        callFactory = callFactory,
                        cornerRadius = style.imageCornerRadius,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadingRenderer(
    heading: HtmlBlock.Heading,
    style: HtmlStyle,
    modifier: Modifier = Modifier,
) {
    val textStyles = MiuixTheme.textStyles
    val headingStyle = when (heading.level) {
        1 -> textStyles.title1.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp)
        2 -> textStyles.title2.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        3 -> textStyles.title3.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        4 -> textStyles.title4.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        else -> style.bodyStyle.copy(fontWeight = FontWeight.Bold)
    }

    Text(
        text = heading.text,
        style = headingStyle,
        color = style.textColor,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeBlockRenderer(
    codeBlock: HtmlBlock.CodeBlock,
    style: HtmlStyle,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(style.codeBackgroundColor)
            .padding(12.dp)
    ) {
        Text(
            text = codeBlock.code,
            style = style.bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            color = style.codeTextColor,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun BlockQuoteRenderer(
    quote: HtmlBlock.BlockQuote,
    style: HtmlStyle,
    callFactory: Call.Factory?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(style.quoteBackgroundColor)
            .padding(vertical = 4.dp),
    ) {
        // 引用左侧竖条
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .background(style.quoteBorderColor, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(style.blockSpacing),
        ) {
            for (inner in quote.blocks) {
                HtmlBlockRenderer(inner, style, callFactory)
            }
        }
    }
}

@Composable
private fun ListRenderer(
    listBlock: HtmlBlock.ListBlock,
    style: HtmlStyle,
    callFactory: Call.Factory?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listBlock.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val bullet = if (listBlock.ordered) "${index + 1}." else "•"
                Text(
                    text = bullet,
                    style = style.bodyStyle,
                    color = style.textColor,
                    fontWeight = FontWeight.Medium,
                )
                ParagraphRenderer(
                    paragraph = item,
                    style = style,
                    callFactory = callFactory,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
