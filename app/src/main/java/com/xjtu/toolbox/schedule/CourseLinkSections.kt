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
import androidx.compose.material.icons.filled.School
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
 * 课程详情的下钻区：教材 → 全文、思源学堂、这一次课的考勤。
 * 两套日程布局共用，布局开关只换摆法不换能力。
 *
 * [occurrence] 决定给多少东西：有具体日期时只给那一天的，
 * 没有日期时（学期总览）不给考勤——离开某一次就没有意义。
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
    // 自定义日程没有课程号、也不在教务的教材/考勤里，整块跳过。
    if (course.courseType == "日程") return

    val loginState = LocalAppLoginState.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = loginState.sessionManager

    LaunchedEffect(course.courseName) {
        if (textbooks.isEmpty()) onRequestTextbooks()
    }

    val mine = remember(course.courseName, textbooks) {
        CourseLinks.textbooksFor(course.courseName, textbooks, course.courseCode)
    }

    // ── 教材全文：只按 ISBN 精确查，查不到就没有这个入口 ──
    var fulltext by remember(course.courseName) {
        mutableStateOf<Pair<TextbookItem, Jiaocai1Book>?>(null)
    }
    // key 用 ISBN 串而不是 mine：list 每次重组都是新实例，会让协程不停被取消重启。
    val isbnKey = remember(mine) { mine.joinToString(",") { it.isbn } }
    LaunchedEffect(isbnKey) {
        for (t in mine) {
            val book = CourseLinks.fulltextByIsbn(manager, t.isbn, byTitle = t.textbookName, byAuthor = t.author) ?: continue
            fulltext = t to book
            break
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

    // ── 思源学堂：这门课的活动、作业、课件都在那边 ──
    // 按课程号配，配不上就不给入口——给错课比不给更糟。
    var lmsCourse by remember(course.courseCode, course.courseName) {
        mutableStateOf<com.xjtu.toolbox.lms.LmsCourseSummary?>(null)
    }
    LaunchedEffect(course.courseCode, course.courseName) {
        lmsCourse = CourseLinks.lmsCourseFor(manager, course)
    }

    val hasBook = mine.isNotEmpty()
    if (!hasBook && record == null && lmsCourse == null) return

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
                WaterType.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            title = "本次考勤：${r.status.displayName}",
            subtitle = null,
            onClick = null,
        )
    }

    // ── 教材 ──
    //
    // 每本都列，且带上作者、出版社、版次、ISBN、定价。
    // 之前这里只显示第一本的书名加一句"等 N 本"，而完整的教材信息只在经典布局的
    // 教材页里有——换到分级布局的用户等于看不到出版社和 ISBN，买书时正需要这两样。
    if (hasBook) {
        val ft = fulltext
        mine.forEach { book ->
            // 按 ISBN 认，不要用引用相等。教材列表刷新后 mine 里是新的 TextbookItem 实例，
            // 而 fulltext 里存的还是上一批的对象，=== 永远为假——表现就是
            // 全文明明查到了，那一行却点不动。
            val readable = ft != null && sameIsbn(ft.first.isbn, book.isbn)
            LinkRow(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                tint = if (readable) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                title = book.textbookName.ifBlank { "未命名教材" },
                subtitle = textbookDetail(book, readable),
                onClick = if (readable && ft != null) {
                    { onNavigate(Routes.jiaocai1Reader(ft.second.ssno, ft.second.title)) }
                } else null,
            )
        }
    }

    // 思源学堂：活动、作业、课件，以及思源自己的回放，都从这里进。
    lmsCourse?.let { lc ->
        LinkRow(
            icon = Icons.Default.School,
            tint = MiuixTheme.colorScheme.primary,
            title = "思源学堂",
            subtitle = listOfNotNull(
                lc.name.takeIf { it.isNotBlank() && it != course.courseName },
                lc.instructors.firstOrNull()?.name?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ").ifBlank { "活动、作业与课件" },
            onClick = { onNavigate(Routes.lmsCourse(lc.id)) },
        )
    }
}

/**
 * 教材副行：作者 · 出版社 · 版次 · ¥定价，换行再给 ISBN。
 *
 * 占位数据要滤掉——报表里"无教材"的行会带一串 978000000000 的假 ISBN，
 * 原样显示只会让人以为真有这本书。判据与经典布局的 TextbookCard 保持一致。
 */
/** 同一本书的判据：ISBN 去掉连字符空格后相同，且不是空串。 */
private fun sameIsbn(a: String, b: String): Boolean {
    fun norm(x: String) = x.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
    val na = norm(a)
    return na.length >= 10 && na == norm(b)
}

private fun textbookDetail(book: TextbookItem, readable: Boolean): String? {
    val head = listOfNotNull(
        book.author.trim().takeIf { it.length >= 2 },
        book.publisher.trim().takeIf { it.isNotBlank() },
        book.edition.trim().takeIf { it.isNotBlank() },
        book.price.trim().takeIf { it.isNotBlank() }?.let { "¥$it" },
    ).joinToString("  ·  ")
    val isbn = book.isbn.trim().takeIf { it.isNotBlank() && !it.startsWith("978000000000") }
    return listOfNotNull(
        head.takeIf { it.isNotBlank() },
        isbn?.let { "ISBN $it" },
        if (readable) "可在线阅读全文" else null,
    ).joinToString("\n").takeIf { it.isNotBlank() }
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
                    // 教材那一行要放下「作者·出版社·版次·定价」和 ISBN 两行，
                    // 钉死单行会把 ISBN 直接截掉——正是买书时要抄的那串。
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(5.dp))
}
