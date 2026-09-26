package com.xjtu.toolbox.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.data.CredentialStore
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import com.xjtu.toolbox.nav.AppRoute

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
    onNavigate: (AppRoute) -> Unit,
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

    // 教材不再自动去全文库找对应的书：全文库把 ISBN 和索书号检索都下线了
    // （检索页源码原话「此处注释了普通搜索的索书号和ISBN号」），只剩书名可查，
    // 而同名多版本无从分辨——《固体物理学》库里黄昆 2009 和陆栋 2010 并存，
    // 认错版本就是给人翻开另一本书。要读全文请走首页的「教材全文」自行检索。

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

    // 自成一列、行距统一 6dp。以前这些行和「间隔 + 分割线 + 间隔」直接散在外层 Column 里，
    // 外层每项之间又有 10dp，叠出一条莫名的分割线和大段空白。
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            TextbookRow(book = book)
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
            onClick = { onNavigate(AppRoute.Lms(lc.id)) },
        )
    }
    }
}

/**
 * 详情里各块的底色。不用 surfaceVariant / surfaceContainerHigh：miuix 深色主题里它们和
 * 弹窗底色同为 #242424，块和块分不开；输入框同款的 secondaryContainer 半透明在深浅两套下都看得出。
 */
@Composable
internal fun courseDetailTileColor(): Color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)

/**
 * 一本书的身份：归一化后的 ISBN，没有 ISBN 的退回书名。
 *
 * 同一本书在两批 [TextbookItem] 里是不同实例，展开状态必须按内容认而不是按引用，
 * 否则教材列表一刷新，用户展开的那本就自己收起来了。
 */
private fun bookKey(book: TextbookItem): String =
    book.isbn.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
        .takeIf { it.length >= 10 } ?: book.textbookName.trim()

/**
 * 一本教材。
 *
 * **点开看详情这件事不依赖任何网络结果**：作者、出版社、版次、ISBN、定价
 * 全都来自教务的教材报表，已经在手里了，展开即看，不依赖任何网络结果。
 */
@Composable
private fun TextbookRow(book: TextbookItem) {
    var expanded by remember(bookKey(book)) { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "bookArrow")

    // 标题行和展开的详情同在一块里：展开的内容属于这本书，不该漂在块外面。
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(courseDetailTileColor()),
    ) {
        LinkRowContent(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            title = book.textbookName.ifBlank { "未命名教材" },
            subtitle = textbookSummary(book),
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = arrow },
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(180, delayMillis = 40)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 12.dp, bottom = 10.dp),
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
            }
        }
    }
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
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(courseDetailTileColor()),
    ) {
        LinkRowContent(
            icon = icon,
            tint = tint,
            title = title,
            subtitle = subtitle,
            onClick = onClick,
            trailing = if (onClick != null) {
                {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else null,
        )
    }
}

/** 一行的内容，不含底色：独立的行包一层底色，教材行把它和展开的详情放进同一块。 */
@Composable
private fun LinkRowContent(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
