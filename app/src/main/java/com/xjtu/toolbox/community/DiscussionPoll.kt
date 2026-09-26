package com.xjtu.toolbox.community

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 本地先记上这一票，失败时调用方换回原来的。 */
internal fun GithubPoll.votedFor(optionId: String) = copy(
    voted = true, canVote = false, total = total + 1,
    options = options.map { if (it.id == optionId) it.copy(votes = it.votes + 1, mine = true) else it },
)

/** 帖子里的投票：没投过时点选项投票，投过或不能投时显示结果。 */
@Composable
internal fun DiscussionPollCard(poll: GithubPoll, onVote: (GithubPollOption) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val results = poll.voted || !poll.canVote
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(poll.question, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
        poll.options.forEach { option ->
            val share = if (poll.total > 0) option.votes.toFloat() / poll.total else 0f
            val fill by animateFloatAsState(if (results) share else 0f, label = "pollShare")
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, if (option.mine) colors.primary else colors.dividerLine, RoundedCornerShape(10.dp))
                    .clickable(enabled = !results) { onVote(option) },
            ) {
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(colors.primary.copy(alpha = 0.14f)))
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option.text, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    if (option.mine) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (results) Text("${(share * 100).toInt()}%", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
                }
            }
        }
        Text(
            "${poll.total} 人投票" + when {
                poll.voted -> " · 你已投票"
                !poll.canVote -> " · 登录 GitHub 后可投票"
                else -> ""
            },
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
        )
    }
}
