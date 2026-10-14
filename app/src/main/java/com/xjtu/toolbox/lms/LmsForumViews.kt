package com.xjtu.toolbox.lms

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private const val TAG = "LmsForumViews"

// ════════════════════════════════════════
//  讨论区标签与小部件
// ════════════════════════════════════════

@Composable
private fun ForumRoleTag(role: String) {
    val (bgColor, textColor) = when (role) {
        "教师" -> MiuixTheme.colorScheme.primaryContainer to MiuixTheme.colorScheme.onPrimaryContainer
        "助教" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        else -> MiuixTheme.colorScheme.surfaceVariant to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(role, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

@Composable
private fun ForumStatusBadge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun ForumUserAvatar(name: String, modifier: Modifier = Modifier) {
    val initial = name.trim().takeLast(1).ifEmpty { "匿" }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun CountLabel(icon: ImageVector, text: String, active: Boolean = false) {
    val tint = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

// ════════════════════════════════════════
//  帖子卡片 (分区详情页内展示)
// ════════════════════════════════════════

@Composable
internal fun LmsForumTopicCard(
    topic: LmsForumTopic,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 16.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            // 作者信息行
            Row(verticalAlignment = Alignment.CenterVertically) {
                ForumUserAvatar(topic.createdBy.name)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            topic.createdBy.name.ifEmpty { "匿名用户" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val role = topic.createdBy.roleLabel
                        if (role != null) {
                            Spacer(Modifier.width(6.dp))
                            ForumRoleTag(role)
                        }
                    }
                    Text(
                        formatLmsTime(topic.createdAt),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary
                    )
                }
                if (topic.isTopped) {
                    ForumStatusBadge("置顶", Color(0xFFBC4C00))
                    Spacer(Modifier.width(6.dp))
                }
                if (topic.isHotTopic) {
                    ForumStatusBadge("热门", Color(0xFFE53935))
                }
            }

            Spacer(Modifier.height(10.dp))

            // 标题
            Text(
                topic.title,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 摘要
            val plain = remember(topic.content) { htmlToPlainText(topic.content) }
            if (plain.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    plain,
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 附件提示
            if (topic.uploads.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${topic.uploads.size} 个附件",
                        fontSize = 12.sp,
                        color = colors.primary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 底部统计行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CountLabel(Icons.AutoMirrored.Filled.Comment, "${topic.replyCount}")
                CountLabel(
                    if (topic.currentUserLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    "${topic.likeCount}",
                    active = topic.currentUserLiked
                )
                CountLabel(Icons.Outlined.Visibility, "${topic.userVisitsNumber}")
                if (topic.unreadReplyCount > 0) {
                    Spacer(Modifier.weight(1f))
                    ForumStatusBadge("${topic.unreadReplyCount} 条未读", colors.primary)
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  回复卡片 (帖子详情页内展示)
// ════════════════════════════════════════

@Composable
internal fun LmsForumReplyCard(
    reply: LmsForumReply,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 14.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            // 作者行
            Row(verticalAlignment = Alignment.CenterVertically) {
                ForumUserAvatar(reply.createdBy.name, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            reply.createdBy.name.ifEmpty { "用户" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val role = reply.createdBy.roleLabel
                        if (role != null) {
                            Spacer(Modifier.width(6.dp))
                            ForumRoleTag(role)
                        }
                    }
                    Text(
                        formatLmsTime(reply.createdAt),
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariantSummary
                    )
                }
                if (reply.likeCount > 0) {
                    CountLabel(
                        if (reply.currentUserLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        "${reply.likeCount}",
                        active = reply.currentUserLiked
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 回复正文
            val contentText = remember(reply.content) { htmlToPlainText(reply.content) }
            Text(
                contentText,
                style = MiuixTheme.textStyles.body2,
                fontSize = 14.sp
            )

            // 楼中楼子评论
            if (reply.comments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    reply.comments.forEach { comment ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "${comment.createdBy.name}: ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.primary
                            )
                            Text(
                                htmlToPlainText(comment.content),
                                fontSize = 12.sp,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  帖子详情页
// ════════════════════════════════════════

@Composable
internal fun LmsForumTopicDetailPage(
    api: LmsApi,
    cache: LmsPageCache,
    course: LmsCourseSummary,
    activity: LmsActivity,
    topic: LmsForumTopic,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberRetainedLazyListState("lms_topic_${topic.id}")
    val replies = cache.topicReplies[topic.id]
    var isLoading by remember { mutableStateOf(cache.topicReplies[topic.id] == null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadReplies() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                val list = withContext(Dispatchers.IO) { api.getTopicReplies(topic.id) }
                cache.topicReplies[topic.id] = list
            } catch (e: Exception) {
                Log.e(TAG, "loadReplies error", e)
                errorMsg = com.xjtu.toolbox.error.FriendlyError.of(e, "加载回复失败")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(topic.id) {
        if (cache.topicReplies[topic.id] == null) loadReplies()
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "帖子详情",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().readableWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 12.dp, bottom = 24.dp)
            ) {
                // 主帖卡片
                item(key = "topic_main") {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), cornerRadius = 16.dp) {
                        Column(Modifier.padding(16.dp)) {
                            // 作者信息行
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ForumUserAvatar(topic.createdBy.name, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            topic.createdBy.name.ifEmpty { "匿名用户" },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        val role = topic.createdBy.roleLabel
                                        if (role != null) {
                                            Spacer(Modifier.width(6.dp))
                                            ForumRoleTag(role)
                                        }
                                    }
                                    Text(
                                        "${formatLmsTime(topic.createdAt)} · ${topic.userVisitsNumber} 次浏览",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                if (topic.isTopped) {
                                    ForumStatusBadge("置顶", Color(0xFFBC4C00))
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (topic.isHotTopic) {
                                    ForumStatusBadge("热门", Color(0xFFE53935))
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // 标题
                            Text(
                                topic.title,
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(12.dp))

                            // 正文
                            val bodyText = remember(topic.content) { htmlToPlainText(topic.content) }
                            if (bodyText.isNotBlank()) {
                                Text(
                                    bodyText,
                                    style = MiuixTheme.textStyles.body1,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }

                // 附件列表（图片可弹窗放大预览，文件可下载）
                if (topic.uploads.isNotEmpty()) {
                    item(key = "uploads_header") { SectionHeader("附件 (${topic.uploads.size})") }
                    items(topic.uploads, key = { "topic_upload_${it.id}" }) { upload ->
                        UploadCard(upload, context, api)
                    }
                }

                // 楼层回复部分
                item(key = "replies_header") {
                    val count = replies?.size ?: topic.replyCount
                    SectionHeader("回复 ($count)")
                }

                when {
                    isLoading && replies == null -> {
                        item(key = "replies_loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                LoadingIndicator("加载回复中…")
                            }
                        }
                    }
                    errorMsg != null && replies == null -> {
                        item(key = "replies_error") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                ErrorRetry(errorMsg!!) { loadReplies() }
                            }
                        }
                    }
                    replies.isNullOrEmpty() -> {
                        item(key = "replies_empty") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "暂无回复，快来抢沙发吧~",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    else -> {
                        items(replies, key = { "reply_${it.id}" }) { reply ->
                            LmsForumReplyCard(reply)
                        }
                    }
                }
            }
        }
    }
}
