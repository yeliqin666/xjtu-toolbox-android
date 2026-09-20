package com.xjtu.toolbox.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.mutableStateMapOf
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
    /**
     * 教材没取到时的原因，null 表示没问题。
     *
     * 没有它的话，"这门课没有指定教材"和"教材这次没请求成功"在这里
     * 长得一模一样——都是不显示教材那一行，而后者是该说一声的。
     */
    textbooksProblem: String?,
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

    // ── 教材全文 ──
    //
    // 每本书各自一个状态，而不是"整门课只留第一本查到的"：一门课的三本教材
    // 各有各的结局，把它们并成一个，另外两本就永远显示成第一本的结论。
    val fulltext = remember(course.courseName) {
        mutableStateMapOf<String, CourseLinks.Fulltext>()
    }
    // key 用 ISBN 串而不是 mine：list 每次重组都是新实例，会让协程不停被取消重启。
    val isbnKey = remember(mine) { mine.joinToString(",") { it.isbn } }
    LaunchedEffect(isbnKey) {
        for (t in mine) {
            fulltext[fulltextKey(t)] = CourseLinks.fulltextByIsbn(
                manager, t.isbn, byTitle = t.textbookName, byAuthor = t.author,
            )
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
    // 教材没取到时要说一声，所以它也算"这一区有东西"。
    val bookProblem = textbooksProblem?.takeIf { !hasBook }
    if (!hasBook && bookProblem == null && record == null && lmsCourse == null) return

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
    bookProblem?.let {
        LinkRow(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            title = "教材没取到",
            subtitle = it,
            onClick = null,
        )
    }

    if (hasBook) {
        mine.forEach { book ->
            TextbookRow(
                book = book,
                // 状态还没写进来就是"正在查"。注意别用 ISBN 以外的东西认：
                // 教材列表刷新后 mine 里是新的 TextbookItem 实例，按引用认永远为假。
                fulltext = fulltext[fulltextKey(book)],
                onRead = { b -> onNavigate(Routes.jiaocai1Reader(b.ssno, b.title)) },
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
 * 教材缓存键：归一化后的 ISBN，没有 ISBN 的退回书名。
 *
 * 同一本书在两批 [TextbookItem] 里是不同实例，必须按内容认而不是按引用。
 */
private fun fulltextKey(book: TextbookItem): String =
    book.isbn.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
        .takeIf { it.length >= 10 } ?: book.textbookName.trim()

/**
 * 一本教材。
 *
 * **点开看详情这件事不依赖任何网络结果**：作者、出版社、版次、ISBN、定价
 * 全都来自教务的教材报表，已经在手里了。全文只是详情里的一个附加动作。
 *
 * 之前这一行是反过来的——只有全文库命中才给 `onClick`，于是"还在查"、
 * 「没 ISBN」、「教材站点没登上」、「库里没有」四种情况长得一模一样：
 * 一行点不动的字。用户报上来就是"教材具体信息打不开了"。
 */
@Composable
private fun TextbookRow(
    book: TextbookItem,
    /** null = 还在查。 */
    fulltext: CourseLinks.Fulltext?,
    onRead: (Jiaocai1Book) -> Unit,
) {
    var expanded by remember(fulltextKey(book)) { mutableStateOf(false) }

    LinkRow(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        tint = if (fulltext is CourseLinks.Fulltext.Found) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        title = book.textbookName.ifBlank { "未命名教材" },
        subtitle = textbookSummary(book),
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 4.dp, bottom = 6.dp),
        ) {
            // 详情逐项列，不再挤进一行副标题：买书时要抄的就是这几项。
            textbookFields(book).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    Text(
                        label,
                        Modifier.width(52.dp),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    SelectionContainer {
                        Text(
                            value,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // 全文这一项把话说清楚：在查 / 没 ISBN 没法查 / 这次没连上 / 库里没有 / 能读。
            when (fulltext) {
                is CourseLinks.Fulltext.Found -> Text(
                    "在线阅读全文 ›",
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onRead(fulltext.book) }
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )

                null -> FulltextNote("正在查全文库…")
                CourseLinks.Fulltext.NoKey -> FulltextNote("这本教材没有 ISBN，查不了全文")
                CourseLinks.Fulltext.NotFound -> FulltextNote("全文库里没有这本")
                CourseLinks.Fulltext.SiteUnavailable ->
                    FulltextNote("教材库这次没连上，重开一次课程详情再试")
            }
        }
    }
}

@Composable
private fun FulltextNote(text: String) {
    Text(
        text,
        Modifier.padding(vertical = 3.dp, horizontal = 2.dp),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 报表里"无教材"的行会带一串 978000000000 的假 ISBN，原样显示会让人以为真有这本书。 */
private fun realIsbn(book: TextbookItem): String? =
    book.isbn.trim().takeIf { it.isNotBlank() && !it.startsWith("978000000000") }

/** 收起时的副行：作者 · 出版社，其余留给展开后的详情。 */
private fun textbookSummary(book: TextbookItem): String? = listOfNotNull(
    book.author.trim().takeIf { it.length >= 2 },
    book.publisher.trim().takeIf { it.isNotBlank() },
).joinToString("  ·  ").takeIf { it.isNotBlank() }

/** 展开后逐项列出的教材信息，空字段不占行。 */
private fun textbookFields(book: TextbookItem): List<Pair<String, String>> = listOfNotNull(
    book.author.trim().takeIf { it.length >= 2 }?.let { "作者" to it },
    book.publisher.trim().takeIf { it.isNotBlank() }?.let { "出版社" to it },
    book.edition.trim().takeIf { it.isNotBlank() }?.let { "版次" to it },
    realIsbn(book)?.let { "ISBN" to it },
    book.price.trim().takeIf { it.isNotBlank() }?.let { "定价" to "¥$it" },
)

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
