package com.xjtu.toolbox.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** GitHub 支持的 8 种表情回应，顺序同网页。 */
internal val REACTION_EMOJI = linkedMapOf(
    "THUMBS_UP" to "👍", "THUMBS_DOWN" to "👎", "LAUGH" to "😄", "HOORAY" to "🎉",
    "CONFUSED" to "😕", "HEART" to "❤️", "ROCKET" to "🚀", "EYES" to "👀",
)

/** 点赞就是 👍 回应。 */
internal const val LIKE = "THUMBS_UP"

internal fun List<GithubReaction>.count(content: String) = firstOrNull { it.content == content }?.count ?: 0
internal fun List<GithubReaction>.mine(content: String) = any { it.content == content && it.mine }

/** 本地先切换一次，失败时调用方再换回原列表。 */
internal fun List<GithubReaction>.toggled(content: String): List<GithubReaction> {
    val old = firstOrNull { it.content == content }
    val next = if (old?.mine == true) old.copy(count = old.count - 1, mine = false)
    else GithubReaction(content, (old?.count ?: 0) + 1, mine = true)
    val order = REACTION_EMOJI.keys.toList()
    return (filterNot { it.content == content } + next)
        .filter { it.count > 0 || it.mine }
        .sortedBy { order.indexOf(it.content) }
}

/** 👍 以外已有的表情回应，点一下加 / 撤自己那一份。 */
@Composable
internal fun ReactionChips(reactions: List<GithubReaction>, enabled: Boolean, onToggle: (String) -> Unit) {
    val shown = reactions.filter { it.content != LIKE && it.count > 0 && it.content in REACTION_EMOJI }
    if (shown.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    FlowRow(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { r ->
            Text(
                "${REACTION_EMOJI.getValue(r.content)} ${r.count}",
                style = MiuixTheme.textStyles.footnote1,
                color = if (r.mine) colors.primary else colors.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (r.mine) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerHigh)
                    .clickable(enabled = enabled || r.mine) { onToggle(r.content) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 动作栏里的「加表情」按钮，点开一排表情。 */
@Composable
internal fun ReactionButton(reactions: List<GithubReaction>, enabled: Boolean, onToggle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CommunityAction(Icons.Outlined.AddReaction, "", enabled = enabled, onClick = { open = true })
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                REACTION_EMOJI.filterKeys { it != LIKE }.forEach { (content, emoji) ->
                    val mine = reactions.mine(content)
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (mine) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { open = false; onToggle(content) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 20.sp) }
                }
            }
        }
    }
}
