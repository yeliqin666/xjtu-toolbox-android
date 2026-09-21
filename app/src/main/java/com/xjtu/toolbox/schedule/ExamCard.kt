package com.xjtu.toolbox.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.schedule.ExamCountdown.ExamPhase
import com.xjtu.toolbox.ui.components.ExpiredStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 一张考试卡片，按 [ExamCountdown.ExamPhase] 决定底色、强调色、状态标签和座位号角标，
 * 见 plan2 §1.3 的对照表。
 *
 * 取代旧版的两份实现：学期栏的 `ExamRowCard`（一律红色、排序也没管已结束）和
 * 经典布局的 `ExamCard`（有状态判断但没人跟学期栏共用），统一成这一份，
 * 学期栏、考试列表弹窗都用它。
 */
@Composable
internal fun ExamCard(
    exam: ExamItem,
    now: LocalDateTime = LocalDateTime.now(),
    modifier: Modifier = Modifier,
) {
    val phase = ExamCountdown.phaseOf(exam, now)
    val scheme = MiuixTheme.colorScheme
    val ended = phase == ExamPhase.ENDED

    val cardColor = when (phase) {
        ExamPhase.TODAY -> scheme.error.copy(alpha = 0.16f)
        ExamPhase.SOON -> scheme.error.copy(alpha = 0.08f)
        ExamPhase.LATER, ExamPhase.ENDED, ExamPhase.UNDATED -> scheme.surfaceVariant
    }
    val accent = when (phase) {
        ExamPhase.TODAY, ExamPhase.SOON -> scheme.error
        ExamPhase.LATER -> scheme.primary
        ExamPhase.ENDED -> scheme.onSurfaceVariantSummary
        ExamPhase.UNDATED -> scheme.onSurfaceVariantSummary
    }
    val daysLeft = ExamCountdown.parseDate(exam.examDate)
        ?.let { ChronoUnit.DAYS.between(now.toLocalDate(), it).toInt() }
    val label = when (phase) {
        ExamPhase.TODAY -> ExamCountdown.startTimeOf(exam)
            ?.let { "今天 %02d:%02d".format(it.hour, it.minute) } ?: "今天"
        ExamPhase.SOON -> if (daysLeft == 1) "明天" else "还有 ${daysLeft ?: 0} 天"
        ExamPhase.LATER -> "${daysLeft ?: 0} 天后"
        ExamPhase.ENDED -> "已结束"
        ExamPhase.UNDATED -> "日期待定"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (ended) ExpiredStyle.CONTENT_ALPHA else 1f),
        cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = cardColor),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        exam.courseName.ifEmpty { "未知课程" },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accent.copy(alpha = 0.14f),
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
                val detail = listOfNotNull(
                    exam.examDate.takeIf { it.isNotBlank() },
                    exam.examTime.takeIf { it.isNotBlank() },
                    exam.location.takeIf { it.isNotBlank() },
                ).joinToString("  ")
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 已结束的场次不用再找座位了，隐藏角标。
            if (!ended && exam.seatNumber.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accent.copy(alpha = 0.16f),
                ) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("座位", style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                        Text(
                            exam.seatNumber,
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}
