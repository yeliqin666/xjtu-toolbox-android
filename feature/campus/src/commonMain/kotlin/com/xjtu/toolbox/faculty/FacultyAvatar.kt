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

/** 教师头像的兜底画法：主题色的圆，中间放姓名首字。 */
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
