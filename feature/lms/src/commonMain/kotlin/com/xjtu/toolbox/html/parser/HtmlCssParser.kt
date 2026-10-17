package com.xjtu.toolbox.html.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * 极简高效的 HTML 元素 inline style 属性解析器。
 * 支持常见字体颜色、背景色、字重、下划线/删除线。
 */
object HtmlCssParser {

    fun parseStyle(styleAttr: String?): SpanStyle? {
        if (styleAttr.isNullOrBlank()) return null
        var color: Color = Color.Unspecified
        var background: Color = Color.Unspecified
        var fontWeight: FontWeight? = null
        var fontStyle: FontStyle? = null
        var textDecoration: TextDecoration? = null

        val declarations = styleAttr.split(';')
        for (decl in declarations) {
            val colonIdx = decl.indexOf(':')
            if (colonIdx <= 0) continue
            val property = decl.substring(0, colonIdx).trim().lowercase()
            val value = decl.substring(colonIdx + 1).trim()

            when (property) {
                "color" -> {
                    parseColor(value)?.let { color = it }
                }
                "background-color", "background" -> {
                    parseColor(value)?.let { background = it }
                }
                "font-weight" -> {
                    if (value == "bold" || value == "bolder" || value.toIntOrNull()?.let { it >= 600 } == true) {
                        fontWeight = FontWeight.Bold
                    } else if (value == "normal" || value.toIntOrNull()?.let { it < 600 } == true) {
                        fontWeight = FontWeight.Normal
                    }
                }
                "font-style" -> {
                    if (value == "italic" || value == "oblique") {
                        fontStyle = FontStyle.Italic
                    }
                }
                "text-decoration", "text-decoration-line" -> {
                    if ("underline" in value) {
                        textDecoration = TextDecoration.Underline
                    } else if ("line-through" in value) {
                        textDecoration = TextDecoration.LineThrough
                    }
                }
            }
        }

        if (color == Color.Unspecified && background == Color.Unspecified &&
            fontWeight == null && fontStyle == null && textDecoration == null
        ) {
            return null
        }

        return SpanStyle(
            color = color,
            background = background,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = textDecoration
        )
    }

    /**
     * 解析颜色字符串，支持：
     * - 16进制: #rgb, #rgba, #rrggbb, #aarrggbb
     * - rgb / rgba: rgb(255, 0, 0), rgba(0, 0, 0, 0.5)
     * - 常见命名颜色: red, green, blue, black, white, gray, orange, yellow
     */
    fun parseColor(colorStr: String): Color? = try {
        val str = colorStr.trim().lowercase()
        when {
            str.startsWith("#") -> {
                val hex = str.substring(1)
                when (hex.length) {
                    3 -> { // #rgb -> #rrggbb
                        val r = hex[0].digitToInt(16) * 17
                        val g = hex[1].digitToInt(16) * 17
                        val b = hex[2].digitToInt(16) * 17
                        Color(r, g, b)
                    }
                    4 -> { // #rgba
                        val r = hex[0].digitToInt(16) * 17
                        val g = hex[1].digitToInt(16) * 17
                        val b = hex[2].digitToInt(16) * 17
                        val a = hex[3].digitToInt(16) * 17
                        Color(r, g, b, a)
                    }
                    6 -> { // #rrggbb
                        val v = hex.toLong(16)
                        Color(0xFF000000 or v)
                    }
                    8 -> { // #rrggbbaa (CSS4) or #aarrggbb (Android) -> 优先按 CSS4 #rrggbbaa 解析
                        val r = hex.substring(0, 2).toInt(16)
                        val g = hex.substring(2, 4).toInt(16)
                        val b = hex.substring(4, 6).toInt(16)
                        val a = hex.substring(6, 8).toInt(16)
                        Color(r, g, b, a)
                    }
                    else -> null
                }
            }
            str.startsWith("rgb(") && str.endsWith(")") -> {
                val parts = str.substring(4, str.length - 1).split(',')
                if (parts.size >= 3) {
                    val r = parts[0].trim().toInt().coerceIn(0, 255)
                    val g = parts[1].trim().toInt().coerceIn(0, 255)
                    val b = parts[2].trim().toInt().coerceIn(0, 255)
                    Color(r, g, b)
                } else null
            }
            str.startsWith("rgba(") && str.endsWith(")") -> {
                val parts = str.substring(5, str.length - 1).split(',')
                if (parts.size >= 4) {
                    val r = parts[0].trim().toInt().coerceIn(0, 255)
                    val g = parts[1].trim().toInt().coerceIn(0, 255)
                    val b = parts[2].trim().toInt().coerceIn(0, 255)
                    val alpha = parts[3].trim().toFloat().coerceIn(0f, 1f)
                    Color(r, g, b, (alpha * 255).toInt())
                } else null
            }
            str == "red" -> Color(0xFFE53935)
            str == "blue" -> Color(0xFF1E88E5)
            str == "green" -> Color(0xFF43A047)
            str == "orange" -> Color(0xFFFB8C00)
            str == "yellow" -> Color(0xFFFDD835)
            str == "purple" -> Color(0xFF8E24AA)
            str == "gray" || str == "grey" -> Color(0xFF757575)
            str == "black" -> Color(0xFF000000)
            str == "white" -> Color(0xFFFFFFFF)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
