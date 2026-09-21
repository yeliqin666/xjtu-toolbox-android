package com.xjtu.toolbox.schedule

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 排好序的考试列表：未结束的在前（正序，日期待定的排最后），已结束的单独收着，
 * 供调用方做成「已结束 N 场 ▾」的折叠行。见 plan2 §1.3④。
 */
data class ExamListData(val active: List<ExamItem>, val ended: List<ExamItem>) {
    val total: Int get() = active.size + ended.size
}

/**
 * 纯函数，方便单测。逻辑：
 * - 按 `课程名_日期_时间` 去重（教务偶尔会给同一场考试发两条一模一样的记录）；
 * - 未结束（TODAY/SOON/LATER）按日期正序，UNDATED 排在它们后面；
 * - 已结束单独放一边，按日期倒序——最近考完的排最上面。
 */
fun sortExamsForList(exams: List<ExamItem>, now: LocalDateTime = LocalDateTime.now()): ExamListData {
    val distinct = exams.distinctBy { "${it.courseName}_${it.examDate}_${it.examTime}" }
    val phased = distinct.map { it to ExamCountdown.phaseOf(it, now) }
    val ended = phased.filter { it.second == ExamCountdown.ExamPhase.ENDED }
        .map { it.first }
        .sortedByDescending { ExamCountdown.parseDate(it.examDate) ?: LocalDate.MIN }
    val dated = phased
        .filter { it.second != ExamCountdown.ExamPhase.ENDED && it.second != ExamCountdown.ExamPhase.UNDATED }
        .map { it.first }
        .sortedBy { ExamCountdown.parseDate(it.examDate) }
    val undated = phased.filter { it.second == ExamCountdown.ExamPhase.UNDATED }.map { it.first }
    return ExamListData(active = dated + undated, ended = ended)
}

/**
 * 插进调用方自己的 LazyColumn（学期栏、考试列表弹窗各有一个）。
 *
 * 展开状态由调用方用 `rememberSaveable` 持有并传进来——两处列表各记各的，
 * 弹窗关掉重开不必记住，学期栏切 tab 回来却该记住。
 */
fun LazyListScope.examListItems(
    data: ExamListData,
    endedExpanded: Boolean,
    onToggleEnded: () -> Unit,
    now: LocalDateTime = LocalDateTime.now(),
) {
    items(data.active, key = { "exam_active_${it.courseName}_${it.examDate}_${it.examTime}" }) { exam ->
        ExamCard(exam, now)
    }
    if (data.ended.isNotEmpty()) {
        item(key = "exam_ended_toggle") {
            ExamEndedToggleRow(count = data.ended.size, expanded = endedExpanded, onToggle = onToggleEnded)
        }
        if (endedExpanded) {
            items(data.ended, key = { "exam_ended_${it.courseName}_${it.examDate}_${it.examTime}" }) { exam ->
                ExamCard(exam, now)
            }
        }
    }
}

/**
 * 已考完的场次收起来的那一行。
 *
 * 以前是一行光秃秃的「已结束 5 场」：说不清是什么结束了，也看不出能点开，第一次看到的人都会愣一下。
 * 现在写明「已考完的考试」、给出能做的动作（查看 / 收起），并和考试卡片一样包成一张卡，
 * 放在列表里不再像一行漏排的小字。
 */
@Composable
private fun ExamEndedToggleRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = com.xjtu.toolbox.ui.components.AppCardColor,
        ),
        onClick = onToggle,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.TaskAlt,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "已考完的考试 · $count 场",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "查看",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
