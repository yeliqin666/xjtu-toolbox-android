package com.xjtu.toolbox.lms

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Group
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.xjtu.toolbox.html.HtmlContent
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 公告主题强调色（暖橙金） */
val LmsBulletinColor = Color(0xFFD97706)

// ════════════════════════════════════════
//  公告卡片（复用 LmsActivityCard 视觉语言）
// ════════════════════════════════════════

@Composable
internal fun LmsBulletinCard(
    bulletin: LmsBulletin,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        colors = CardDefaults.defaultColors(
            color = com.xjtu.toolbox.ui.components.AppCardColor
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = LmsBulletinColor.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Campaign,
                        contentDescription = "公告",
                        tint = LmsBulletinColor,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = bulletin.title.ifBlank { "未命名公告" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "公告",
                        fontSize = 12.sp,
                        color = LmsBulletinColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (bulletin.createdBy.name.isNotBlank()) {
                        Text(
                            text = bulletin.createdBy.name,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (bulletin.createdAt.isNotBlank()) {
                        Text(
                            text = formatLmsTime(bulletin.createdAt),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    if (bulletin.uploads.isNotEmpty()) {
                        Text(
                            text = "📎 ${bulletin.uploads.size} 个附件",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  公告详情页
// ════════════════════════════════════════

@Composable
internal fun BulletinDetailPage(
    api: LmsApi,
    course: LmsCourseSummary,
    bulletin: LmsBulletin,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "公告详情",
                largeTitle = bulletin.title.ifBlank { "公告详情" },
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
                modifier = Modifier
                    .fillMaxSize()
                    .readableWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = glassTop + 12.dp,
                    bottom = 24.dp
                )
            ) {
                // 基本信息卡
                item(key = "info") {
                    BulletinInfoCard(course = course, bulletin = bulletin)
                }

                // 正文卡片
                item(key = "body") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("公告正文")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)
                    ) {
                        Box(Modifier.padding(16.dp)) {
                            if (bulletin.content.isNotBlank()) {
                                HtmlContent(
                                    html = bulletin.content,
                                    callFactory = api.client,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = "（无正文描述）",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }

                // 附件列表卡片
                if (bulletin.uploads.isNotEmpty()) {
                    item(key = "uploads_header") {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader("附件 (${bulletin.uploads.size})")
                    }
                    items(bulletin.uploads, key = { it.id }) { upload ->
                        var isDownloading by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = fileTypeIcon(upload.type),
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = upload.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = upload.readableSize,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (isDownloading) return@Button
                                        isDownloading = true
                                        scope.launch {
                                            try {
                                                val res = saveUploadToDownloads(context, upload, api)
                                                Toast.makeText(context, downloadToast(res), Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "下载出错：${e.message}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                isDownloading = false
                                            }
                                        }
                                    },
                                    enabled = !isDownloading
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (isDownloading) "保存中" else "下载")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BulletinInfoCard(course: LmsCourseSummary, bulletin: LmsBulletin) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = bulletin.title.ifBlank { "未命名公告" },
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            MetaRow(
                icon = Icons.Default.Person,
                label = "发布人",
                value = bulletin.createdBy.name.ifBlank { "任课教师" }
            )
            if (bulletin.createdAt.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                MetaRow(
                    icon = Icons.Default.Schedule,
                    label = "发布时间",
                    value = formatLmsTime(bulletin.createdAt)
                )
            }
            val targets = if (bulletin.targetsName.isNotEmpty()) bulletin.targetsName.joinToString("、") else "所有人"
            Spacer(Modifier.height(6.dp))
            MetaRow(
                icon = Icons.Default.Group,
                label = "接收范围",
                value = targets
            )
        }
    }
}

@Composable
private fun MetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$label：",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

/** 从 HTML 中提取全部 <img> 的 src 链接 */
internal fun extractImageUrls(html: String): List<String> = try {
    val doc = Jsoup.parse(html)
    doc.select("img").mapNotNull { img ->
        val src = img.attr("src").trim()
        src.takeIf { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
    }
} catch (_: Exception) {
    emptyList()
}
