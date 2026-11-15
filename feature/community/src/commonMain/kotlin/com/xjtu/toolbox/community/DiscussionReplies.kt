package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionReplies.kt。
// 改成论坛的「楼中楼」：默认露出前两条，点「查看全部」再拉完整列表；回复框统一走详情页的编辑页。

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope

/** 一楼的楼中楼：默认露出预览那几条，展开后换成完整列表。归 ViewModel 所有，见 [DiscussionDetailViewModel.replies]。 */
class FloorReplies(
    scope: CoroutineScope,
    preview: List<GithubDiscussionComment>,
    load: suspend (String?) -> Result<GithubDiscussionComments>,
) {
    val loader = DiscussionCommentsLoader(scope, load)
    var full by mutableStateOf(false)
    var preview by mutableStateOf(preview); private set
    var deleted by mutableIntStateOf(0); private set

    fun expand() {
        full = true
        loader.fetch(true)
    }

    fun edited(comment: GithubDiscussionComment) {
        loader.edited(comment)
        preview = preview.map { if (it.id == comment.id) comment else it }
    }

    fun deleted(id: String) {
        if (loader.state.value.items.none { it.id == id } && preview.none { it.id == id }) return
        loader.deleted(id)
        preview = preview.filterNot { it.id == id }
        deleted++
    }
}

@Composable
fun DiscussionReplies(
    comment: GithubDiscussionComment,
    op: String?,
    state: FloorReplies,
    canReply: Boolean,
    onReact: (GithubDiscussionComment, String, (GithubDiscussionComment) -> Unit) -> Unit,
    onReply: (GithubDiscussionComment) -> Unit,
    menu: (GithubDiscussionComment) -> List<MenuAction>,
) {
    val loader = state.loader
    val connection by loader.state.collectAsStateWithLifecycle()
    val full = state.full
    val preview = state.preview

    // 完整列表拉回来之前先留着预览：中途清空会让这一楼突然变矮，列表跟着跳
    val shown = if (full && connection.loaded) connection.items else preview
    val total = (comment.replyCount - state.deleted).coerceAtLeast(shown.size)
    if (shown.isEmpty() && !full && total == 0) return

    val colors = MiuixTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(top = 4.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { reply ->
            key(reply.id) {
                var expanded by remember { mutableStateOf(false) }
                val apply = state::edited
                AuthorLine(reply, op, avatar = 22.dp)
                Column(Modifier.padding(start = 32.dp)) {
                    val reason = reply.minimizedReason
                    if (reason != null && !expanded) {
                        MinimizedNotice(reason) { expanded = true }
                    } else {
                        MarkdownText(reply.body)
                        ReactionChips(reply.reactions, reply.canReact) { onReact(reply, it, apply) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReactionActions(reply.reactions, reply.canReact, likeEmpty = false) { onReact(reply, it, apply) }
                        if (canReply) CommunityAction(Icons.AutoMirrored.Outlined.Reply, "回复", onClick = { onReply(reply) })
                        Spacer(Modifier.weight(1f))
                        MoreMenu(menu(reply))
                    }
                }
            }
        }
        when {
            !full && total > shown.size -> ReplyExpansionAction(
                text = "展开 ${total - shown.size} 条回复",
                onClick = state::expand,
            )
            full && connection.loading -> ReplyExpansionAction("正在加载", loading = true, onClick = {})
            full && connection.failed -> ReplyExpansionAction("加载失败，点此重试", retry = true, onClick = { loader.fetch(!connection.loaded) })
            full && connection.cursor != null -> ReplyExpansionAction("继续展开", onClick = { loader.fetch(false) })
            full && total > preview.size -> ReplyExpansionAction("收起", collapse = true, onClick = { state.full = false })
        }
    }
}

/** 楼中楼底部的「展开 / 收起」：引导短线 + 文字 + 箭头，箭头随展开状态翻转。 */
@Composable
private fun ReplyExpansionAction(
    text: String,
    loading: Boolean = false,
    retry: Boolean = false,
    collapse: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val tint = if (retry) colors.error else colors.primary
    val flip by animateFloatAsState(if (collapse) 180f else 0f, label = "replyChevron")
    Row(
        Modifier.padding(start = 32.dp, top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                enabled = !loading,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).height(1.dp).background(colors.onSurfaceVariantSummary.copy(alpha = 0.35f)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = tint)
        Spacer(Modifier.width(2.dp))
        when {
            loading -> CircularProgressIndicator(size = 12.dp, strokeWidth = 1.5.dp)
            retry -> Icon(Icons.Default.Refresh, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            else -> Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = flip },
            )
        }
    }
}
