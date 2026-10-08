package com.xjtu.toolbox.faculty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 教师头像的**兜底画法**：主题色的圆，中间放姓名首字。
 *
 * 为什么需要它：`FacultyAvatar`（:app 的 `faculty/FacultyPhoto.kt`）要用
 * `android.graphics.BitmapFactory` + `LruCache` 把 `gr.xjtu.edu.cn` 上的头像下下来，
 * 那是 Android 专属；浏览器里那台站也不给 CORS 头。所以屏上的头像做成**平台槽位**
 * `avatar: @Composable (FacultyMember, Int) -> Unit`：
 * - `:app` 传原来的 `FacultyAvatar` ⇒ 行为逐字不变；
 * - 其它端用本函数 ⇒ 有名字、有身份色，不会是一片空白。
 */
@Composable
fun InitialsFacultyAvatar(member: FacultyMember, size: Int) {
    val accent = MiuixTheme.colorScheme.primary
    val initial = member.name.trim().firstOrNull()?.toString() ?: "师"
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.55f))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.4f).sp,
        )
    }
}
