package com.xjtu.toolbox.schedule

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
import com.xjtu.toolbox.platform.keyValueStore
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 课程自定义颜色：按课程名存、按账号分开。课表和思源学堂共用，同名课程改一处两处生效。
 *
 * 从 :app 搬进 commonMain 的改动只有存储这一处：`Context.getSharedPreferences(...)`
 * 换成 [keyValueStore] —— Android actual 仍是**同名同文件**的 SharedPreferences
 * （`"course_colors" + AccountContext.safeSuffix()`，与搬迁前逐字一致），jvm 走内存、
 * Web 走 localStorage。因此 `init(context)` 连同那个 `lateinit var app: Context` 一起没有了
 * （这正是它能进 commonMain 的原因），`XjtuApp` 里的 `CourseColors.init(this)` 已删。
 *
 * `of()` 用 `contains` 而不是「getInt 的默认值」来区分「用户设过色」与「没设过、按课名哈希
 * 取默认色」—— 后者区分不出来（用户完全可能正好把颜色设成默认色）。
 *
 * 另一处不可移植的写法也在这里被换掉：`String.format("%06X")`（JVM 专有、且在 JVM 上是
 * **默认导入**，所以任何基于 import 的判据都看不见它）改成了手写补零。
 */
object CourseColors {

    /** 每次改色加一；[of] 和 [revision] 读它来订阅，改完所有用到课程色的地方立刻重组。 */
    private var version by mutableIntStateOf(0)

    val revision: Int get() = version

    private fun store() = keyValueStore("course_colors" + AccountContext.safeSuffix())

    fun of(courseName: String): Color? {
        if (version < 0) return null
        val key = courseName.trim()
        val s = store()
        return if (s.contains(key)) Color(s.getInt(key, 0)) else null
    }

    /** [color] 为 null 恢复默认。 */
    fun set(courseName: String, color: Color?) {
        val key = courseName.trim()
        val s = store()
        if (color == null) s.remove(key) else s.putInt(key, color.toArgb())
        version++
    }

    fun toHex(color: Color): String {
        val rgb = color.toArgb() and 0xFFFFFF
        return "#" + rgb.toString(16).uppercase().padStart(6, '0')
    }

    /** 认 `#RRGGBB`、`RRGGBB`、`#RGB`，其余返回 null。 */
    fun parseHex(text: String): Color? {
        val s = text.trim().removePrefix("#")
        // toLongOrNull(16) 会放过 "+abcde"、"-12345"，先确认全是十六进制字符
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
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
