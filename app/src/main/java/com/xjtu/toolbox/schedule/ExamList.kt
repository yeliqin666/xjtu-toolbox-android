package com.xjtu.toolbox.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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

@Composable
private fun ExamEndedToggleRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "已结束 $count 场",
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
