package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun CouiText(
    modifier: Modifier = Modifier,
    text: String,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalCouiTextStyle.current,
) {
    val contentColor = if (color.isSpecified) color else if (style.color.isSpecified) style.color else CouiTheme.contentColor
    BasicText(text, modifier, style.merge(TextStyle(color = contentColor, fontSize = fontSize,
        fontWeight = fontWeight, textAlign = textAlign ?: TextAlign.Unspecified, lineHeight = lineHeight)),
        overflow = overflow, softWrap = softWrap, maxLines = maxLines)
}

@Composable
internal fun CouiIcon(modifier: Modifier = Modifier, imageVector: ImageVector, contentDescription: String?,
    tint: Color = CouiTheme.contentColor) {
    CouiIcon(modifier, rememberVectorPainter(imageVector), contentDescription, tint)
}

@Composable
internal fun CouiIcon(modifier: Modifier = Modifier, painter: Painter, contentDescription: String?,
    tint: Color = CouiTheme.contentColor) {
    // Image 的默认图片语义与 M3 Icon 不同，装饰图标不建立额外语义节点。
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    }
    Image(painter, null, modifier.then(semantics), colorFilter = if (tint.isSpecified) ColorFilter.tint(tint) else null)
}
