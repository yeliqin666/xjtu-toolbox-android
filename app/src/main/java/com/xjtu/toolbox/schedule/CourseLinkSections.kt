package com.xjtu.toolbox.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.jiaocai1.Jiaocai1Book
import com.xjtu.toolbox.util.CredentialStore
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 课程详情里的「下钻」区：教材 → 全文、课程回放、本课考勤。
 *
 * 独立成一个 Composable 而不是写进某个页面，是因为**新旧两套日程布局共用它**。
 * 布局开关只换外面的摆法，这里的能力两边完全一致。
 *
 * 每一项都是各自异步、各自失败：教材全文库没登录不影响回放按钮出现，
 * 回放站点超时也不影响教材照常显示。全部拿不到时整个区不渲染，不留空壳。
 *
 * @param textbooks 已经加载好的整学期教材列表；空列表会触发 [onRequestTextbooks]。
 * @param onRequestTextbooks 请调用方去拉教材。教材原先只在「教材」tab 打开时才加载，
 *   而现在从课格点进来也要用，所以由这里按需触发一次。
 */
@Composable
fun CourseLinkSections(
    course: CourseItem,
    textbooks: List<TextbookItem>,
    onRequestTextbooks: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    // 自定义日程没有课程号、也不在教务的教材/回放/考勤里，整块跳过。
    if (course.courseType == "日程") return

    val loginState = LocalAppLoginState.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = loginState.sessionManager

    LaunchedEffect(course.courseName) {
        if (textbooks.isEmpty()) onRequestTextbooks()
    }

    val mine = remember(course.courseName, textbooks) {
        CourseLinks.textbooksFor(course.courseName, textbooks)
    }

    // ── 教材全文：只按 ISBN 精确查，查不到就没有这个入口 ──
    var fulltext by remember(course.courseName) { mutableStateOf<Pair<TextbookItem, Jiaocai1Book>?>(null) }
    LaunchedEffect(mine) {
        for (t in mine) {
            val book = CourseLinks.fulltextByIsbn(manager, t.isbn) ?: continue
            fulltext = t to book
            break
        }
    }

    // ── 课程回放 ──
    var replay by remember(course.courseCode) {
        mutableStateOf<com.xjtu.toolbox.classreplay.Course?>(null)
    }
    LaunchedEffect(course.courseCode) {
        replay = CourseLinks.replayFor(manager, course.courseCode)
    }

    // ── 考勤：跟角标共用同一个开关。它要单独登录一次考勤站点，
    //    没打开开关的人不该因为点开一门课就付这个代价。 ──
    val store = remember { CredentialStore(context) }
    val attendanceEnabled = remember { store.scheduleAttendanceBadge }
    var attendance by remember(course.courseName) {
        mutableStateOf<List<com.xjtu.toolbox.attendance.AttendanceWaterRecord>>(emptyList())
    }
    LaunchedEffect(course.courseName, attendanceEnabled) {
        if (!attendanceEnabled) return@LaunchedEffect
        attendance = CourseLinks.attendanceIndex(manager, loginState.accountType)
            ?.recordsOf(course.courseName).orEmpty()
    }

    if (mine.isEmpty() && replay == null && attendance.isEmpty()) return

    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine, thickness = 0.5.dp)
    Spacer(Modifier.height(4.dp))

    if (mine.isNotEmpty()) {
        val first = mine.first()
        val extra = if (mine.size > 1) "  等 ${mine.size} 本" else ""
        val ft = fulltext
        LinkRow(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = first.textbookName + extra,
            subtitle = listOfNotNull(
                first.author.takeIf { it.isNotBlank() },
                first.publisher.takeIf { it.isNotBlank() },
                if (ft != null) "可在线阅读全文" else null,
            ).joinToString(" · ").ifBlank { null },
            enabled = ft != null,
            onClick = {
                ft ?: return@LinkRow
                onNavigate(Routes.jiaocai1Reader(ft.second.ssno, ft.second.title))
            },
        )
    }

    replay?.let { c ->
        LinkRow(
            icon = Icons.Default.OndemandVideo,
            title = "课程回放",
            subtitle = c.displayName.takeIf { it.isNotBlank() && it != c.name },
            enabled = true,
            onClick = { onNavigate(Routes.classReplay(c.courseCode)) },
        )
    }

    if (attendance.isNotEmpty()) {
        val abnormal = attendance.count { it.status != WaterType.NORMAL }
        val summary = attendance.groupingBy { it.status }.eachCount()
            .entries.sortedBy { it.key.value }
            .joinToString(" · ") { "${it.key.displayName} ${it.value}" }
        LinkRow(
            icon = Icons.Default.FactCheck,
            title = if (abnormal == 0) "考勤全勤（${attendance.size} 次）" else "考勤有 $abnormal 次异常",
            subtitle = summary,
            enabled = true,
            onClick = {
                val route = if (loginState.accountType == com.xjtu.toolbox.auth.AccountType.POSTGRADUATE) {
                    Routes.POSTGRADUATE_ATTENDANCE
                } else {
                    Routes.ATTENDANCE
                }
                onNavigate(route)
            },
        )
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (enabled) 1f else 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null, Modifier.size(20.dp),
            tint = if (enabled) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}
