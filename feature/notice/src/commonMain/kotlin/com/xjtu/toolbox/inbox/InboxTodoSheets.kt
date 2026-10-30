package com.xjtu.toolbox.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.schedule.CourseColorPickerDialog
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.AppDatePickerDialog
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.util.formatTwoDigits
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** 新待办默认几点截止：当天结束。用户只改日期时不用再动时间。 */
private const val DEFAULT_DUE_HOUR = 23
private const val DEFAULT_DUE_MINUTE = 59

/** 日期最多往后挑这么久（AppDatePickerDialog 要上下界）。 */
private const val MAX_DAYS_AHEAD = 365

/**
 * 新建一条自建待办。
 *
 * 形态照自建日程的编辑面板（`CustomCourseDialog`）：底部抽屉 + 分区标题 + 一组输入，
 * 而不是一个窄弹窗 —— 日期、时间、颜色挤在弹窗里既看不清也点不准；抽屉还能一路往下滑。
 *
 * 与日程那面的差别是有意为之：待办**不占时段**（没有结束时间、没有周次、没有冲突判定），
 * 只需要一个截止；提醒也不在这里问 —— 截止提醒走 [DueReminder] 那一套（和作业截止同一个窗口），
 * 用户不用选，选了也没用。
 */
@Composable
internal fun AddTodoSheet(onDismiss: () -> Unit) {
    val today = remember { todayOnCampus() }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var colorArgb by remember { mutableStateOf<Int?>(null) }
    var importance by remember { mutableIntStateOf(InboxRules.IMPORTANCE_NONE) }
    var day by remember { mutableStateOf(today) }
    var hour by remember { mutableIntStateOf(DEFAULT_DUE_HOUR) }
    var minute by remember { mutableIntStateOf(DEFAULT_DUE_MINUTE) }
    var noDue by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }
    WindowBottomSheet(
        show = true,
        title = "新建待办",
        onDismissRequest = onDismiss,
    ) {
        // 面板本身是限高的 Column：可滚动区必须用 weight(fill = false)，不然 verticalScroll
        // 会把剩余高度吃光，底部的「添加」被挤出面板（和日程面板同一个坑）。
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = "要做什么",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = "备注（可选）",
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                SheetSection("颜色", trailing = "只影响收纳里这一条的图标色")
                TodoColorField(current = colorArgb, onPick = { colorArgb = it })

                SheetSection(
                    title = "截止",
                    trailing = if (noDue) "不设截止" else "${dateText(day)} ${formatTwoDigits(hour)}:${formatTwoDigits(minute)}",
                )
                SheetCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // ── 日期：点一下开日历（Android 是系统的，桌面上是填 YYYY-MM-DD） ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !noDue) { picking = true }
                                .alpha(if (noDue) 0.45f else 1f)
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "日期",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                dateText(day),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        // ── 时间：时 / 分两个轮盘，默认 23:59 ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (noDue) 0.45f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "时间",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.width(52.dp),
                            )
                            WheelNumber(
                                value = hour,
                                range = 0..23,
                                onValue = { hour = it },
                                modifier = Modifier.weight(1f).alpha(if (noDue) 0f else 1f),
                            )
                            Text(
                                ":",
                                style = MiuixTheme.textStyles.title3,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                            WheelNumber(
                                value = minute,
                                range = 0..59,
                                onValue = { minute = it },
                                modifier = Modifier.weight(1f).alpha(if (noDue) 0f else 1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppFilterChip(selected = !noDue, onClick = { noDue = false }, label = "按时截止")
                            AppFilterChip(selected = noDue, onClick = { noDue = true }, label = "不设截止")
                        }
                    }
                }

                SheetSection("重要程度", trailing = "只作标记，不改变排序")
                SheetCard {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IMPORTANCE_CHOICES.forEach { (label, level) ->
                            AppFilterChip(
                                selected = importance == level,
                                onClick = { importance = level },
                                label = label,
                            )
                        }
                    }
                }
            }

            // ── 底部操作区（不随内容滚动）──
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "添加",
                    enabled = title.isNotBlank(),
                    onClick = {
                        val todo = SelfTodoStore.create(
                            title = title,
                            content = note,
                            dueDate = if (noDue) null else SelfTodoRules.dueAt(day, hour, minute),
                            colorArgb = colorArgb,
                        )
                        // 重要程度按「收纳里那条」存（学校推来的待办也能标），所以建完再贴
                        if (todo != null && importance != InboxRules.IMPORTANCE_NONE) {
                            InboxStore.setImportance(SelfTodoRules.inboxId(todo.id), importance)
                        }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    AppDatePickerDialog(
        show = picking,
        title = "截止日期",
        date = day,
        minDate = today,
        maxDate = today.plus(MAX_DAYS_AHEAD, DateTimeUnit.DAY),
        onDismiss = { picking = false },
        onConfirm = { picked ->
            day = picked
            picking = false
        },
    )
}

/** 重要程度的四档；文案与 [InboxRules.importanceLabel] 一致，这里多一个「普通」。 */
private val IMPORTANCE_CHOICES = listOf(
    "普通" to InboxRules.IMPORTANCE_NONE,
    "次要" to InboxRules.IMPORTANCE_LOW,
    "重要" to InboxRules.IMPORTANCE_MEDIUM,
    "紧急" to InboxRules.IMPORTANCE_HIGH,
)

/** 「10月30日 · 周五」。日期选择器回来的是 [LocalDate]，不引用 JVM 的日期格式化。 */
internal fun dateText(date: LocalDate): String =
    "${date.monthNumber}月${date.dayOfMonth}日 · 周${"一二三四五六日"[date.dayOfWeek.isoDayNumber - 1]}"

/**
 * 待办颜色：一块显示当前色，右上角「调色盘」进完整面板（色板 + Hex，和课程颜色同一个面板），
 * 下面那排快捷色片取自「课程颜色」那份设置（思源学堂里给课程挑的色也在里面）。
 *
 * 只负责「挑」，落盘交给 [onPick]：新建抽屉先记在本地状态里，长按抽屉直接写回那条待办。
 */
@Composable
internal fun TodoColorField(current: Int?, onPick: (Int?) -> Unit) {
    var showPalette by remember { mutableStateOf(false) }
    // revision 变了说明课程颜色改过，跟着重读一遍
    val quickColors = remember(CourseColors.revision) { CourseColors.palette() }
    if (showPalette) {
        CourseColorPickerDialog(
            title = "待办颜色",
            summary = "只改收纳里这一条的图标色",
            initial = current?.let { Color(it) } ?: Color(InboxCategories.argb(InboxCategories.SELF)),
            onDismiss = { showPalette = false },
            onConfirm = { picked ->
                onPick(picked?.toArgb())
                showPalette = false
            },
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("颜色", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        // 现在用的是哪个色：自选色可能不在下面那排色片里，这里始终能看到
        Spacer(Modifier.width(6.dp))
        Spacer(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(current?.let { Color(it) } ?: Color(InboxCategories.argb(InboxCategories.SELF))),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { showPalette = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Palette, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.primary)
            Text("调色盘", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
        }
    }
    if (quickColors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickColors.forEach { color ->
                val argb = color.toArgb()
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onPick(argb) }
                        .then(
                            if (argb == current) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, CircleShape)
                            else Modifier
                        )
                        // 色片没有字，读屏靠这句；选中的那个也标出来
                        .semantics {
                            contentDescription = "颜色 " + CourseColors.toHex(color)
                            selected = argb == current
                        },
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (quickColors.isEmpty()) "还没在思源学堂 / 课表里设过课程颜色；点右上角「调色盘」挑一个（可填 Hex）"
        else "点一个色片就用它；更细致的选择点右上角「调色盘」（可填 Hex）",
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 长按抽屉里的重要程度：四档单选。只影响角标与通知措辞，不参与排序。 */
@Composable
internal fun ImportanceRow(level: Int, onPick: (Int) -> Unit) {
    Text("重要程度", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IMPORTANCE_CHOICES.forEach { (label, value) ->
            AppFilterChip(selected = level == value, onClick = { onPick(value) }, label = label)
        }
    }
}

/** 列表里的重要程度角标：紧急红、重要橙、次要灰；普通不画。 */
@Composable
internal fun ImportanceBadge(level: Int) {
    val label = InboxRules.importanceLabel(level) ?: return
    val tint = when (level) {
        InboxRules.IMPORTANCE_HIGH -> Color(0xFFEF4444)
        InboxRules.IMPORTANCE_MEDIUM -> Color(0xFFF97316)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = MiuixTheme.textStyles.footnote2, color = tint, fontWeight = FontWeight.Medium)
    }
}

/** 分区标题：左边名称，右边一句实时摘要。和日程面板同一个样子。 */
@Composable
private fun SheetSection(title: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/**
 * 抽屉里的分组块。面板底色是 background；miuix 深色主题里 surface 与它同色，卡片等于隐形，
 * 所以用输入框同款的 secondaryContainer 半透明分层（与日程面板的 GroupCard 一致）。
 */
@Composable
private fun SheetCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(content = content)
    }
}

/** 时 / 分轮盘：和日程面板一样用 miuix 的 NumberPicker，转一圈到底。 */
@Composable
private fun WheelNumber(
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NumberPicker(
        value = value.coerceIn(range),
        onValueChange = onValue,
        modifier = modifier,
        range = range,
        label = { formatTwoDigits(it) },
        wrapAround = true,
        visibleItemCount = 3,
    )
}
