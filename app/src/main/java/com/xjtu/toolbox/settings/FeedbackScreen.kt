package com.xjtu.toolbox.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackStore
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FEEDBACK_GITHUB_ISSUE_URL =
    "https://github.com/yeliqin666/xjtu-toolbox-android/issues/new"

private val CATEGORIES = listOf("功能坏了", "有点难用", "想要新功能", "其他")

/**
 * 反馈页。主路径是页内直接提交（走飞书多维表格），GitHub 降级为备选——
 * 大部分用户不用 GitHub，把提 Issue 当唯一入口约等于没有反馈渠道。
 *
 * 凭据没配时（见 FeedbackApi.isConfigured）表单整块隐藏，只剩外链，别人 clone
 * 下来照样能编译出可用的包。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val dateFmt = remember { SimpleDateFormat("M月d日", Locale.CHINA) }

    var category by remember { mutableStateOf(CATEGORIES.first()) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintIsError by remember { mutableStateOf(false) }

    var tickets by remember { mutableStateOf(FeedbackStore.tickets(context)) }
    var replies by remember {
        mutableStateOf(
            FeedbackStore.cachedReplies(context)
                .mapValues { (_, v) -> FeedbackApi.Reply(v.first, v.second) }
        )
    }
    var publicQa by remember { mutableStateOf(FeedbackStore.cachedPublicQa(context)) }
    var expandedQa by remember { mutableStateOf(setOf<Int>()) }

    // 还在等回复的工单，进页面就拉一次：节流会把「刚写上的回复」挡在门外。
    // 全已回复才走配额节流。
    LaunchedEffect(tickets.size) {
        if (!FeedbackApi.isConfigured || tickets.isEmpty()) return@LaunchedEffect
        if (replies.isNotEmpty()) FeedbackStore.markRead(context, replies.keys)
        val hasPending = tickets.any { it.id !in replies }
        if (!hasPending && !FeedbackStore.shouldFetchReplies(context, hasPending = false)) {
            return@LaunchedEffect
        }
        val got = runCatching { FeedbackApi.replies(tickets.map { it.id }) }.getOrNull()
            ?: return@LaunchedEffect
        FeedbackStore.markRepliesFetched(context)
        if (got.isEmpty()) return@LaunchedEffect
        val merged = replies + got
        FeedbackStore.cacheReplies(context, merged.mapValues { (_, r) -> r.text to r.time })
        replies = merged
        FeedbackStore.markRead(context, got.keys)
    }

    LaunchedEffect(Unit) {
        if (!FeedbackApi.isConfigured) return@LaunchedEffect
        if (!FeedbackStore.shouldFetchPublicQa(context)) return@LaunchedEffect
        val got = runCatching { FeedbackApi.publicQa() }.getOrNull() ?: return@LaunchedEffect
        FeedbackStore.cachePublicQa(context, got)
        publicQa = got
    }

    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun submit() {
        val text = content.trim()
        if (text.length < 5) {
            hint = "再多写一点吧，太短了没法定位问题"
            hintIsError = true
            return
        }
        submitting = true
        hint = null
        scope.launch {
            val ticket = FeedbackStore.newTicket()
            val result = runCatching {
                FeedbackApi.submit(
                    ticket = ticket,
                    category = category,
                    content = text,
                    contact = contact.trim(),
                    anonId = FeedbackStore.anonId(context),
                )
            }
            submitting = false
            result.onSuccess {
                FeedbackStore.addTicket(
                    context,
                    FeedbackStore.Ticket(ticket, category, text, System.currentTimeMillis())
                )
                tickets = FeedbackStore.tickets(context)
                content = ""
                contact = ""
                hint = "收到了，工单号 $ticket。有回复会显示在下面。"
                hintIsError = false
            }.onFailure {
                hint = "发送失败，检查下网络再试；也可以去 GitHub 提 Issue"
                hintIsError = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "反馈与建议",
                largeTitle = "反馈与建议",
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (FeedbackApi.isConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "直接在这里说就行",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "不用注册，版本和机型会自动带上。虽然这个界面很漂亮，还是希望你去 GitHub 提 Issue，更方便项目维护~",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                if (publicQa.isNotEmpty()) {
                    SmallTitle("大家在问")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        colors = CardDefaults.defaultColors(color = AppCardColor)
                    ) {
                        publicQa.forEachIndexed { index, qa ->
                            val open = index in expandedQa
                            if (index > 0) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 14.dp),
                                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                                )
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedQa = if (open) expandedQa - index else expandedQa + index
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        qa.question,
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (qa.category.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        qa.category,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                                    )
                                }
                                if (open) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(qa.answer, style = MiuixTheme.textStyles.body2)
                                } else {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "点开看回复",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                SmallTitle("这是什么问题")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CATEGORIES.forEach { c ->
                            AppFilterChip(
                                selected = c == category,
                                onClick = { category = c },
                                label = c,
                            )
                        }
                    }
                }

                SmallTitle("具体说说")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            value = content,
                            onValueChange = { content = it; hint = null },
                            label = "什么情况下出的问题？期望是什么样？",
                            singleLine = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 132.dp),
                        )
                        TextField(
                            value = contact,
                            onValueChange = { contact = it },
                            label = "联系方式（选填，方便追问）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                hint?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.body2,
                        color = if (hintIsError) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } ?: Spacer(Modifier.height(8.dp))

                TextButton(
                    text = if (submitting) "发送中…" else "发送",
                    onClick = { if (!submitting) submit() },
                    enabled = !submitting,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (tickets.isNotEmpty()) {
                    SmallTitle("我提过的")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        colors = CardDefaults.defaultColors(color = AppCardColor)
                    ) {
                        tickets.forEachIndexed { index, t ->
                            val reply = replies[t.id]
                            if (index > 0) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 14.dp),
                                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                                )
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t.category,
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        if (reply != null) "已回复" else "处理中",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = if (reply != null) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    t.content,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                if (t.createdAt > 0L) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        dateFmt.format(Date(t.createdAt)),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                                    )
                                }
                                if (reply != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 12.dp,
                                        colors = CardDefaults.defaultColors(
                                            color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                                        )
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(
                                                "开发者回复",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(reply.text, style = MiuixTheme.textStyles.body2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SmallTitle("也可以")
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "这个安装包没有应用内反馈",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "构建时没带上反馈后端，没法在这里直接发送。去 GitHub 提 Issue 就行，效果一样。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                SmallTitle("怎么说")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = AppCardColor)
            ) {
                ArrowPreference(
                    title = "去 GitHub 提 Issue",
                    summary = "yeliqin666/xjtu-toolbox-android",
                    onClick = { open(FEEDBACK_GITHUB_ISSUE_URL) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
