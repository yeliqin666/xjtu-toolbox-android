package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.ui.components.AppPullToRefresh
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val FEEDBACK_GITHUB_ISSUE_URL =
    "https://github.com/yeliqin666/xjtu-toolbox-android/issues/new"

private val CATEGORIES = listOf("功能坏了", "有点难用", "想要新功能", "其他")

/**
 * 反馈页。主路径是页内直接提交（走飞书多维表格，见 [FeedbackSource] 的实现方），
 * GitHub 降级为备选——大部分用户不用 GitHub，把提 Issue 当唯一入口约等于没有反馈渠道。
 *
 * 凭据没配时（[FeedbackSource.isConfigured]）表单整块隐藏，只剩外链，别人 clone
 * 下来照样能编译出可用的包。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    source: FeedbackSource,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    var category by remember { mutableStateOf(CATEGORIES.first()) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintIsError by remember { mutableStateOf(false) }

    var tickets by remember { mutableStateOf(FeedbackStore.tickets()) }
    var replies by remember {
        mutableStateOf(
            FeedbackStore.cachedReplies()
                .mapValues { (_, v) -> FeedbackReply(v.first, v.second) }
        )
    }
    var publicQa by remember { mutableStateOf(FeedbackStore.cachedPublicQa()) }
    var expandedQa by remember { mutableStateOf(setOf<Int>()) }

    var refreshing by remember { mutableStateOf(false) }

    /**
     * 拉一次回复和公共问答。
     *
     * @param force 跳过配额节流。进页面和下拉都算用户主动要求，两处都传 true——
     *   节流是用来挡后台轮询的，不该把「我现在就想看有没有回复」也挡掉。
     */
    suspend fun reload(force: Boolean) {
        if (!source.isConfigured) return
        // 回复
        if (tickets.isNotEmpty()) {
            if (replies.isNotEmpty()) FeedbackStore.markRead(replies.keys)
            val hasPending = tickets.any { it.id !in replies }
            if (force || hasPending || FeedbackStore.shouldFetchReplies(hasPending = false)) {
                val got = runCatching { source.replies(tickets.map { it.id }) }.getOrNull()
                if (got != null) {
                    FeedbackStore.markRepliesFetched()
                    if (got.isNotEmpty()) {
                        val merged = replies + got
                        FeedbackStore.cacheReplies(
                            merged.mapValues { (_, r) -> r.text to r.time },
                        )
                        replies = merged
                        FeedbackStore.markRead(got.keys)
                    }
                }
            }
        }
        // 公共问答
        if (force || FeedbackStore.shouldFetchPublicQa()) {
            runCatching { source.publicQa() }.getOrNull()?.let { got ->
                FeedbackStore.cachePublicQa(got)
                publicQa = got
            }
        }
    }

    // 每次进入页面都刷新一遍。这一页的全部价值就是「我的反馈有没有人回」，
    // 打开了却给一份可能几小时前的缓存，等于白开。
    LaunchedEffect(Unit) { reload(force = true) }

    fun open(url: String) = onOpenUrl(url)

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
                source.submit(
                    ticket = ticket,
                    category = category,
                    content = text,
                    contact = contact.trim(),
                    anonId = FeedbackStore.anonId(),
                )
            }
            submitting = false
            result.onSuccess {
                FeedbackStore.addTicket(
                    FeedbackStore.Ticket(ticket, category, text, nowMillis())
                )
                tickets = FeedbackStore.tickets()
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

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "反馈与建议",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        AppPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    reload(force = true)
                    refreshing = false
                }
            },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Spacer(Modifier.height(glassTop))
            // 三档，各不相同（不能混成一句）：
            //  ① 后端配全了、但**本端不能写**（serve 契约 §5.7 不端 `submit` 那个写路径）⇒ 只读说明；
            //  ② 后端配全了、也能写（Android / 桌面）⇒ 原样的表单；
            //  ③ 后端凭据没配（`isConfigured = false`）⇒ 「这个安装包没有应用内反馈」。
            if (source.isConfigured && !source.canSubmit) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "这一端只能看，不能提",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "下面的「大家在问」与「我提过的」都能读（serve 契约 §5.7 端了那两个只读端点）；但提交" +
                                "没有端点（写路径不端）⇒ 这里不画表单。要提就去 GitHub 提 Issue，效果一样。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                SmallTitle("怎么说")
            } else if (source.isConfigured) {
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
                        val linkColor = MiuixTheme.colorScheme.primary
                        Text(
                            buildAnnotatedString {
                                append("会附带机型与版本号，如果你有更多细节需要提交，请前往 ")
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = FEEDBACK_GITHUB_ISSUE_URL,
                                        styles = TextLinkStyles(
                                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                        ),
                                        linkInteractionListener = { open(FEEDBACK_GITHUB_ISSUE_URL) },
                                    )
                                ) { append("GitHub Issues") }
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
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
                                        monthDayCn(t.createdAt),
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
        }   // Column
        }   // PullToRefresh
    }
}

private fun monthDayCn(epochMs: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.monthNumber}月${date.dayOfMonth}日"
}

/** `System.currentTimeMillis()` 在 commonMain 不存在（`Clock.System` 是同一时刻、同一 epoch 毫秒）。 */
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
