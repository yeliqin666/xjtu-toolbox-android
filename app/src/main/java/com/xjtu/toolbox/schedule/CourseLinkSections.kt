package com.xjtu.toolbox.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.LocalDate

/** 一次具体的上课：哪一天、第几周。 */
data class Occurrence(val date: LocalDate, val week: Int)

/**
 * 课程详情的下钻区：教材 → 全文、这一次课的回放、这一次课的考勤。
 * 两套日程布局共用，布局开关只换摆法不换能力。
 *
 * [occurrence] 决定给多少东西：有具体日期时只给那一天的，
 * 没有日期时（学期总览）不给回放和考勤——那两样离开某一次就没有意义。
 * 每一项各自异步、各自失败。
 */
@Composable
fun CourseLinkSections(
    course: CourseItem,
    textbooks: List<TextbookItem>,
    /** 当前选中的教务学期码，如 `2025-2026-2`。 */
    termCode: String,
    /** 这一次课是哪一天、第几周；学期总览给不出，传 null。 */
    occurrence: Occurrence?,
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
    var fulltext by remember(course.courseName) {
        mutableStateOf<Pair<TextbookItem, Jiaocai1Book>?>(null)
    }
    // key 用 ISBN 串而不是 mine：list 每次重组都是新实例，会让协程不停被取消重启。
    val isbnKey = remember(mine) { mine.joinToString(",") { it.isbn } }
    LaunchedEffect(isbnKey) {
        for (t in mine) {
            val book = CourseLinks.fulltextByIsbn(manager, t.isbn) ?: continue
            fulltext = t to book
            break
        }
    }

    // ── 这一次课的回放 ──
    var replay by remember(course.courseCode) {
        mutableStateOf<com.xjtu.toolbox.classreplay.Course?>(null)
    }
    var sessions by remember(course.courseCode, occurrence?.date) {
        mutableStateOf<List<com.xjtu.toolbox.classreplay.LiveActivity>>(emptyList())
    }
    LaunchedEffect(course.courseCode, occurrence?.date, termCode) {
        val date = occurrence?.date ?: return@LaunchedEffect
        CourseLinks.replaySessionsOn(manager, course, termCode, date)?.let { (c, list) ->
            replay = c
            sessions = list
        }
    }

    // 考勤跟角标共用开关：要单独登录一次考勤站点，没开的人不该为点开一门课付这个代价。
    val store = remember { CredentialStore(context) }
    val attendanceEnabled = remember { store.scheduleAttendanceBadge }
    var record by remember(course.courseCode, occurrence?.week) {
        mutableStateOf<com.xjtu.toolbox.attendance.AttendanceWaterRecord?>(null)
    }
    LaunchedEffect(course.courseCode, occurrence?.week, attendanceEnabled, termCode) {
        val week = occurrence?.week ?: return@LaunchedEffect
        if (!attendanceEnabled) return@LaunchedEffect
        // 用户点开了详情正在等，豁免站点级失败冷却。
        record = CourseLinks.attendanceIndex(
            manager, loginState.accountType, termCode, userInitiated = true,
        )?.recordOn(course, week)
    }

    val hasBook = mine.isNotEmpty()
    val hasReplay = replay != null && occurrence != null
    if (!hasBook && !hasReplay && record == null) return

    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine, thickness = 0.5.dp)
    Spacer(Modifier.height(8.dp))

    // 本次考勤：一行一句话。
    record?.let { r ->
        LinkRow(
            icon = Icons.Default.FactCheck,
            tint = when (r.status) {
                WaterType.ABSENCE -> Color(0xFFE5484D)
                WaterType.LATE -> Color(0xFFF5A524)
                WaterType.LEAVE -> Color(0xFF9BA1A6)
                WaterType.NORMAL -> MiuixTheme.colorScheme.primary
            },
            title = "本次考勤：${r.status.displayName}",
            subtitle = null,
            onClick = null,
        )
    }

    // ── 教材 ──
    if (hasBook) {
        val first = mine.first()
        val ft = fulltext
        LinkRow(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tint = if (ft != null) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            title = first.textbookName + if (mine.size > 1) "  等 ${mine.size} 本" else "",
            subtitle = if (ft != null) "可在线阅读全文" else first.author.takeIf { it.isNotBlank() },
            onClick = ft?.let {
                { onNavigate(Routes.jiaocai1Reader(it.second.ssno, it.second.title)) }
            },
        )
    }

    // 本次回放。连堂两节常各录一段，所以可能不止一条。
    if (hasReplay) {
        val c = replay!!
        LinkRow(
            icon = Icons.Default.OndemandVideo,
            tint = MiuixTheme.colorScheme.primary,
            title = "课程回放",
            subtitle = if (sessions.isEmpty()) "本次没有录播，点进去看全部" else "本次 ${sessions.size} 段",
            onClick = { onNavigate(Routes.classReplay(c.courseCode)) },
        )
        sessions.forEach { a ->
            SubRow(
                label = CourseLinks.prettyLocalTime(a.startTime),
                onClick = { onNavigate(Routes.videoPlayer(a.id)) },
            )
        }
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    /** null = 这一行只是陈述事实，点不动（比如「本次考勤：正常」）。 */
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(5.dp))
}

/** 挂在 [LinkRow] 下面的子项，缩进一级表示从属关系。 */
@Composable
private fun SubRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, bottom = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PlayCircleOutline, null, Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface)
    }
}
