package com.xjtu.toolbox.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.PollResult
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ChoiceList
import com.xjtu.toolbox.ui.components.rememberChoiceState
import com.xjtu.toolbox.ui.components.SmallTitle
import com.xjtu.toolbox.util.campusDateTimeOf
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import com.xjtu.toolbox.ui.markdown.GithubMarkdown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 反馈页的投票区：进行中的一张张卡片（没投的直接投，投过的看结果、可改投），截止的收进「往期投票」。
 * 数据跟首页公告是同一份缓存；[refreshKey] 大于 0（下拉过）才重新拉，进页面不额外请求。
 */
@Composable
internal fun PollSection(polls: FeedbackPolls, refreshKey: Int) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(polls.cached()) }
    var votes by remember { mutableStateOf(items.associate { it.id to polls.myVote(it.id) }) }
    LaunchedEffect(refreshKey) {
        if (refreshKey == 0) return@LaunchedEffect
        polls.refresh()?.let { got ->
            items = got
            votes = got.associate { it.id to polls.myVote(it.id) }
        }
    }
    val now = Clock.System.now()
    val (closed, open) = items.partition { it.isClosedAt(now) }
    if (open.isNotEmpty()) {
        SmallTitle("投票")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            open.forEach { poll ->
                OpenPollCard(poll, votes[poll.id], now) { selected, onDone ->
                    scope.launch {
                        val ok = runCatching { polls.vote(poll, selected) }.isSuccess
                        if (ok) votes = votes + (poll.id to selected)
                        onDone(ok)
                    }
                }
            }
        }
    }
    if (closed.isNotEmpty()) {
        SmallTitle("往期投票")
        PastPolls(closed, votes)
    }
}

// ---------------------------------------------------------------- 进行中

@Composable
private fun OpenPollCard(
    poll: Bulletin,
    myVote: List<String>?,
    now: Instant,
    onVote: (selected: List<String>, onDone: (Boolean) -> Unit) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var changing by remember(myVote) { mutableStateOf(false) }
    val voting = myVote == null || changing
    PollCardShell {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (myVote == null) "投票中" else "已投票", colors.primary)
            Spacer(Modifier.weight(1f))
            poll.remainingText(now)?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            }
        }
        Text(poll.title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        if (poll.body.isNotBlank()) {
            GithubMarkdown(poll.body, color = colors.onSurfaceVariantSummary, textStyle = MiuixTheme.textStyles.body2)
        }
        if (voting) {
            PollChoices(poll, myVote.orEmpty(), onCancel = if (changing) ({ changing = false }) else null) { selected, onDone ->
                onVote(selected) { ok -> if (ok) changing = false; onDone(ok) }
            }
        } else {
            PollResults(poll, myVote.orEmpty())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    footer(poll, "结果每 4 天统计一次"),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "改投",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { changing = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PollChoices(
    poll: Bulletin,
    previous: List<String>,
    onCancel: (() -> Unit)?,
    onVote: (List<String>, (Boolean) -> Unit) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val choice = rememberChoiceState(poll.id, poll.options, poll.allowMultiple, previous)
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Text(
        if (poll.allowMultiple) "可多选" else "单选",
        style = MiuixTheme.textStyles.footnote2,
        color = colors.onSurfaceVariantSummary,
    )
    ChoiceList(choice, poll.options, poll.allowOther)
    val selected = choice.answers
    if (failed) {
        Text("没投上，检查下网络再试", style = MiuixTheme.textStyles.footnote1, color = colors.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onCancel != null) {
            TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
        }
        TextButton(
            text = when {
                sending -> "提交中…"
                choice.otherMissing -> "填一下「其他」再投"
                selected.isEmpty() -> "选一项再投"
                else -> "投票"
            },
            onClick = {
                sending = true
                failed = false
                onVote(selected) { ok -> sending = false; failed = !ok }
            },
            enabled = selected.isNotEmpty() && !choice.otherMissing && !sending,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------- 结果

/** 结果条：底色是选项轨道，票数比例从左往右铺主色，自己投的那项更深并打勾。还没统计过就只列选项。 */
@Composable
private fun PollResults(poll: Bulletin, mine: List<String>) {
    val colors = MiuixTheme.colorScheme
    if (poll.results.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            poll.options.forEach { ResultBar(it, null, 0f, it in mine, leading = false) }
            mine.filter { it !in poll.options }.forEach { ResultBar("其他：${it.removePrefix("其他：")}", null, 0f, mine = true, leading = false) }
        }
        Text("首轮统计后显示票数", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
        return
    }
    val total = poll.results.sumOf { it.votes }.coerceAtLeast(1)
    val top = poll.results.maxOf { it.votes }
    val mineOther = mine.any { it !in poll.options }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        poll.results.forEach { r ->
            val isMine = r.option in mine || (r.option == "其他" && mineOther && r.option !in poll.options)
            ResultBar(r.option, r, r.votes.toFloat() / total, isMine, leading = top > 0 && r.votes == top)
        }
    }
}

@Composable
private fun ResultBar(option: String, result: PollResult?, share: Float, mine: Boolean, leading: Boolean) {
    val colors = MiuixTheme.colorScheme
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(share) { target = share }
    val fill by animateFloatAsState(target, tween(durationMillis = 700))
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainer),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(colors.primary.copy(alpha = if (mine) 0.30f else if (leading) 0.20f else 0.12f)),
        )
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mine) {
                Icon(Icons.Filled.Check, contentDescription = "我投的", tint = colors.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                option,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (mine || leading) FontWeight.SemiBold else FontWeight.Normal,
                color = if (mine) colors.primary else colors.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (result != null) {
                Text(
                    "${result.votes} 票",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(share * 100).toInt()}%",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = if (mine || leading) colors.primary else colors.onSurface,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 往期

/** 截止的投票收在一张卡里：每行一句话结论（谁最多、多少人），点开看完整结果。 */
@Composable
private fun PastPolls(polls: List<Bulletin>, votes: Map<String, List<String>?>) {
    var open by remember { mutableStateOf<String?>(null) }
    PollCardShell(padded = false) {
        polls.forEachIndexed { i, poll ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            PastPollRow(poll, votes[poll.id].orEmpty(), expanded = open == poll.id) {
                open = if (open == poll.id) null else poll.id
            }
        }
    }
}

@Composable
private fun PastPollRow(poll: Bulletin, mine: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val arrow by animateFloatAsState(if (expanded) 180f else 0f)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                poll.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).rotate(arrow),
            )
        }
        Text(verdict(poll), style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (poll.body.isNotBlank()) {
                    GithubMarkdown(
                        poll.body,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textStyle = MiuixTheme.textStyles.body2,
                    )
                }
                PollResults(poll, mine)
                Text(
                    footer(poll, if (mine.isEmpty()) "你没有参与" else "你投了 ${mine.joinToString("、")}"),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 小件

@Composable
private fun PollCardShell(padded: Boolean = true, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        if (padded) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        } else {
            Column { content() }
        }
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 往期一句话：「X」最多 · 63% · 128 人参与；还没出最终统计就说一声。 */
private fun verdict(poll: Bulletin): String {
    val ended = poll.endsAt?.let { "${dateText(it)}截止" }
    val total = poll.results.sumOf { it.votes }
    val top = poll.results.maxByOrNull { it.votes }
    if (top == null || total == 0) return listOfNotNull(ended, "等待最终统计").joinToString(" · ")
    val tie = poll.results.count { it.votes == top.votes } > 1
    return listOfNotNull(
        if (tie) "多项并列最多" else "「${top.option}」最多 ${top.votes * 100 / total}%",
        "${poll.voters} 人参与",
        ended,
    ).joinToString(" · ")
}

private fun footer(poll: Bulletin, tail: String): String =
    listOfNotNull(poll.voters.takeIf { it > 0 }?.let { "$it 人参与" }, tail).joinToString(" · ")

private fun dateText(t: Instant): String {
    val d = campusDateTimeOf(t.toEpochMilliseconds())
    return "${d.date.monthNumber}月${d.date.dayOfMonth}日"
}
