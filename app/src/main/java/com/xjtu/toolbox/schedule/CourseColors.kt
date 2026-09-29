package com.xjtu.toolbox.schedule

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.account.AccountContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 课程自定义颜色：按课程名存、按账号分开。课表和思源学堂共用，同名课程改一处两处生效。
 */
object CourseColors {
    private lateinit var app: Context

    /** 每次改色加一；[of] 读它来订阅，改完所有用到课程色的地方立刻重组。 */
    private var version by mutableIntStateOf(0)

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs() = app.getSharedPreferences("course_colors${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)

    fun of(courseName: String): Color? {
        if (version < 0 || !::app.isInitialized) return null
        val key = courseName.trim()
        val p = prefs()
        return if (p.contains(key)) Color(p.getInt(key, 0)) else null
    }

    /** [color] 为 null 恢复默认。 */
    fun set(courseName: String, color: Color?) {
        val key = courseName.trim()
        prefs().edit().apply { if (color == null) remove(key) else putInt(key, color.toArgb()) }.apply()
        version++
    }

    fun toHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

    /** 认 `#RRGGBB`、`RRGGBB`、`#RGB`，其余返回 null。 */
    fun parseHex(text: String): Color? {
        val s = text.trim().removePrefix("#")
        val full = when (s.length) {
            3 -> s.map { "$it$it" }.joinToString("")
            6 -> s
            else -> return null
        }
        return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }
}

@Composable
fun CourseColorDialog(courseName: String, current: Color, onDismiss: () -> Unit) {
    var color by remember { mutableStateOf(current) }
    var hex by remember { mutableStateOf(CourseColors.toHex(current)) }
    WindowDialog(
        show = true,
        title = "课程颜色",
        summary = "课表和思源学堂里的「$courseName」一起换",
        onDismissRequest = onDismiss,
    ) {
        Column {
            ColorPalette(
                color = color,
                onColorChanged = {
                    color = it.copy(alpha = 1f)
                    hex = CourseColors.toHex(color)
                },
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = hex,
                onValueChange = { text ->
                    hex = text
                    CourseColors.parseHex(text)?.let { color = it }
                },
                label = "Hex 色值",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(
                    text = "恢复默认",
                    onClick = { CourseColors.set(courseName, null); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "确定",
                    onClick = { CourseColors.set(courseName, color); onDismiss() },
                    enabled = CourseColors.parseHex(hex) != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
