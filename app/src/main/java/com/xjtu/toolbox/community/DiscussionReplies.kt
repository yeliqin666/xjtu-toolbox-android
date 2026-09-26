package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionReplies.kt。
// 改成论坛的「楼中楼」：默认露出前两条，点「查看全部」再拉完整列表；回复框统一走详情页的编辑页。

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DiscussionReplies(
    comment: GithubDiscussionComment,
    op: String?,
    refreshKey: Int,
    load: suspend (String?) -> Result<GithubDiscussionComments>,
    canReply: Boolean,
    onReact: (GithubDiscussionComment, String, (GithubDiscussionComment) -> Unit) -> Unit,
    onReply: (GithubDiscussionComment) -> Unit,
    menu: (GithubDiscussionComment) -> List<MenuAction>,
    patch: ReplyPatch?,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(comment.id) { DiscussionCommentsLoader(scope, load) }
    val connection by loader.state.collectAsStateWithLifecycle()
    var full by remember(comment.id) { mutableStateOf(false) }
    var preview by remember(comment.id) { mutableStateOf(comment.previewReplies) }
    var deleted by remember(comment.id) { mutableIntStateOf(0) }

    // 刚回复了这一楼：展开完整列表并重新拉，新回复立刻能看到
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) { full = true; loader.fetch(true) }
    }
    LaunchedEffect(patch) {
        val p = patch ?: return@LaunchedEffect
        p.edited?.let { edited ->
            if (connection.items.any { it.id == edited.id }) loader.edited(edited)
            preview = preview.map { if (it.id == edited.id) edited else it }
        }
        p.deletedId?.let { id ->
            val mine = connection.items.any { it.id == id } || preview.any { it.id == id }
            if (mine) {
                loader.deleted(id)
                preview = preview.filterNot { it.id == id }
                deleted++
            }
        }
    }

    val shown = if (full) connection.items else preview
    val total = (comment.replyCount - deleted).coerceAtLeast(shown.size)
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
                val apply = { updated: GithubDiscussionComment ->
                    if (connection.items.any { it.id == updated.id }) loader.edited(updated)
                    preview = preview.map { if (it.id == updated.id) updated else it }
                }
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
                        LikeAction(reply.reactions, reply.canReact, "") { onReact(reply, LIKE, apply) }
                        ReactionButton(reply.reactions, reply.canReact) { onReact(reply, it, apply) }
                        if (canReply) CommunityAction(Icons.AutoMirrored.Outlined.Reply, "回复", onClick = { onReply(reply) })
                        Spacer(Modifier.weight(1f))
                        MoreMenu(menu(reply))
                    }
                }
            }
        }
        when {
            !full && total > shown.size -> TextButton(
                text = "查看全部 $total 条回复",
                onClick = { full = true; loader.fetch(true) },
                minHeight = 32.dp,
            )
            full && connection.loading -> CircularProgressIndicator(size = 18.dp)
            full && connection.failed -> TextButton(text = "加载失败，点这里重试", onClick = { loader.fetch(!connection.loaded) }, minHeight = 32.dp)
            full && connection.cursor != null -> TextButton(text = "加载更多回复", onClick = { loader.fetch(false) }, minHeight = 32.dp)
        }
    }
}
