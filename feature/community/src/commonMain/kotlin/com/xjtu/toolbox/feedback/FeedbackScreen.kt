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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import com.xjtu.toolbox.community.LocalCommunityDiagnostics
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.SmallTitle
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.time.Clock
import com.xjtu.toolbox.util.campusDateTimeOf

private const val FEEDBACK_GITHUB_ISSUE_URL =
    "https://github.com/yeliqin666/xjtu-toolbox-android/issues/new"
/** 与 `.github/scripts/feedback_digest.py` 的 DIAGNOSTICS_SEPARATOR 一致。 */
private const val DIAGNOSTICS_SEPARATOR = "———— 诊断信息 ————"
private const val FEEDBACK_DISCUSSIONS_URL =
    "https://github.com/yeliqin666/xjtu-toolbox-android/discussions"

/** 旧版用过「功能坏了 / 有点难用 / 想要新功能」，日报脚本会把旧名映射到这里。 */
private val CATEGORIES = listOf("有bug", "待改进", "新点子", "其他")

/**
 * 反馈页：页内提交走飞书多维表格（见 [FeedbackSource] 的实现方），开发者回复也显示在这里。
 * 凭据没配时（[FeedbackSource.isConfigured]）表单隐藏，只剩 GitHub 外链。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    source: FeedbackSource,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    /** 应用内社区；null 时退回讨论区网页。 */
    onOpenCommunity: (() -> Unit)? = null,
    /** 投票区的数据；null（桌面 / Web）就不显示投票区。 */
    polls: FeedbackPolls? = null,
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()

    var category by remember { mutableStateOf(CATEGORIES.first()) }
    val diagnostics = LocalCommunityDiagnostics.current
    // 报 bug 时默认附诊断信息，换了类别按新类别重置
    var attachDiagnostics by remember(category) { mutableStateOf(category == CATEGORIES.first()) }
    var content by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintIsError by remember { mutableStateOf(false) }
    var privacyNotice by remember { mutableStateOf<String?>(null) }
    /** 打码前的原文；用户点「恢复原文」后记为 [unguarded]，这段原样内容不再检查。 */
    var beforeScrub by remember { mutableStateOf("") }
    var unguarded by remember { mutableStateOf<String?>(null) }

    var tickets by remember { mutableStateOf(FeedbackStore.tickets()) }
    var replies by remember {
        mutableStateOf(
            FeedbackStore.cachedReplies()
        )
    }
    var publicQa by remember { mutableStateOf(FeedbackStore.cachedPublicQa()) }
    /** 展开了的行：问答用 "qa序号"，工单用工单号。 */
    var expanded by remember { mutableStateOf(setOf<String>()) }

    var refreshing by remember { mutableStateOf(false) }
    /** 下拉一次加一，投票区据此重新拉公告。 */
    var pollTick by remember { mutableStateOf(0) }

    /**
     * 拉一次回复和公共问答。回复按工单号查飞书（一次调用），进页面有待回的才每天一次，下拉立即，见 [FeedbackStore.shouldFetchReplies]；
     * 公共问答读 Gitee，隔 4 天或下拉才拉。
     *
     * @param manual 下拉刷新。
     */
    fun CoroutineScope.launchReloads(manual: Boolean) {
        if (tickets.isNotEmpty()) launch {
            if (replies.isNotEmpty()) FeedbackStore.markRead(replies.keys)
            val hasPending = tickets.any { it.id !in replies && FeedbackStore.isRecent(it) }
            if (FeedbackStore.shouldFetchReplies(hasPending, manual)) {
                val got = runCatching { source.replies(tickets.map { it.id }) }.getOrNull()
                if (got != null) {
                    FeedbackStore.markRepliesFetched()
                    if (got.isNotEmpty()) {
                        val merged = replies + got
                        FeedbackStore.cacheReplies(merged)
                        replies = merged
                        FeedbackStore.markRead(got.keys)
                    }
                }
            }
        }
        if (FeedbackStore.shouldFetchPublicQa(manual)) launch {
            runCatching { source.publicQa() }.getOrNull()?.let { got ->
                FeedbackStore.cachePublicQa(got)
                publicQa = got
            }
        }
    }

    suspend fun reload(manual: Boolean) {
        if (!source.isConfigured) return
        // 回复与公共问答互不依赖，并行拉
        coroutineScope { launchReloads(manual) }
    }

    LaunchedEffect(Unit) { reload(manual = false) }

    fun open(url: String) = onOpenUrl(url)

    fun submit() {
        val raw = content.trim()
        val scrubbed = if (raw == unguarded) PrivacyGuard.Result(raw, emptyList())
        else PrivacyGuard.scrub(raw, source.privateTerms())
        if (scrubbed.found.isNotEmpty()) {
            beforeScrub = raw
            content = scrubbed.text
            privacyNotice = PrivacyGuard.notice(scrubbed.found)
            return
        }
        val text = scrubbed.text
        if (text.length < 5) {
            hint = "再多写一点吧，太短了没法定位问题"
            hintIsError = true
            return
        }
        submitting = true
        hint = null
        // 诊断信息只进提交内容，不进本地「我提过的」；日报按这个分隔行把它单独折叠
        val payload = diagnostics?.takeIf { attachDiagnostics }
            ?.let { runCatching(it).getOrNull() }
            ?.let { "$text\n\n$DIAGNOSTICS_SEPARATOR\n$it" } ?: text
        scope.launch {
            val ticket = FeedbackStore.newTicket()
            val result = runCatching {
                source.submit(
                    ticket = ticket,
                    category = category,
                    content = payload,
                    contact = "",
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
                title = "反馈与投票",
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
                pollTick++
                scope.launch {
                    reload(manual = true)
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
                // 不铺背景：下拉指示器画在内容底下，铺了就被盖住；底色交给 Scaffold。
                // 顶栏的嵌套滚动由 AppPullToRefresh 代接，这里再挂一层会先把下拉吃掉
                .overScrollVertical()
                .verticalScroll(rememberReportedScrollState(scrollBehavior)).tabContentBottomPadding()
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
                            "暂不支持提交反馈",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已有反馈可以查看；新问题请到 GitHub 提交 Issue。",
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
                        val linkStyle = TextLinkStyles(
                            SpanStyle(color = MiuixTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                        )
                        Text(
                            buildAnnotatedString {
                                append("反馈将经过 AI 审核并公开，勿留个人信息。条件允许仍建议前往")
                                withLink(
                                    LinkAnnotation.Clickable("community", linkStyle) {
                                        onOpenCommunity?.invoke() ?: open(FEEDBACK_DISCUSSIONS_URL)
                                    }
                                ) { append("社区") }
                                append("或 ")
                                withLink(
                                    LinkAnnotation.Clickable(FEEDBACK_GITHUB_ISSUE_URL, linkStyle) {
                                        open(FEEDBACK_GITHUB_ISSUE_URL)
                                    }
                                ) { append("Issues") }
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                polls?.let { PollSection(it, pollTick) }

                // 类别、正文、诊断开关同在一张白卡里，和社区发帖页一致
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
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
                        TextField(
                            value = content,
                            onValueChange = { content = it; hint = null },
                            label = "什么情况下出的问题？期望是什么样？",
                            singleLine = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 132.dp),
                        )
                        if (diagnostics != null) {
                            Row(
                                Modifier.fillMaxWidth().clickable { attachDiagnostics = !attachDiagnostics },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("附上诊断信息", style = MiuixTheme.textStyles.body2)
                                    Text(
                                        "版本、机型和最近一次崩溃日志，不含账号",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                Switch(
                                    checked = attachDiagnostics,
                                    onCheckedChange = { attachDiagnostics = it },
                                )
                            }
                        }
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
                    FoldedList(publicQa.size) { index ->
                        val qa = publicQa[index]
                        val key = "qa$index"
                        FoldRow(
                            text = qa.question,
                            meta = qa.category,
                            status = if (key in expanded) "收起" else "看回复",
                            highlight = true,
                            reply = qa.answer,
                            replyBy = qa.by,
                            open = key in expanded,
                            onToggle = { expanded = if (key in expanded) expanded - key else expanded + key },
                        )
                    }
                }

                if (tickets.isNotEmpty()) {
                    SmallTitle("我提过的")
                    FoldedList(tickets.size) { index ->
                        val t = tickets[index]
                        val reply = replies[t.id]
                        FoldRow(
                            text = t.content,
                            meta = listOfNotNull(t.category, t.createdAt.takeIf { it > 0L }?.let(::monthDayCn))
                                .joinToString(" · "),
                            status = if (reply != null) "已回复" else "处理中",
                            highlight = reply != null,
                            reply = reply?.text,
                            replyBy = reply?.by.orEmpty(),
                            open = t.id in expanded,
                            onToggle = { expanded = if (t.id in expanded) expanded - t.id else expanded + t.id },
                        )
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
                            "此版本不能直接发送，请去 GitHub 提 Issue。",
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

        privacyNotice?.let { notice ->
            BackHandler { privacyNotice = null }
            OverlayDialog(
                show = true,
                title = "已打码",
                summary = notice,
                onDismissRequest = { privacyNotice = null },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "恢复原文",
                        onClick = {
                            content = beforeScrub
                            unguarded = beforeScrub
                            privacyNotice = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "好的",
                        onClick = { privacyNotice = null },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private const val FOLD_LIMIT = 3

/** 一张卡里的列表：超过 [FOLD_LIMIT] 条只显示前几条，底下一行「展开全部」。 */
@Composable
private fun FoldedList(count: Int, row: @Composable (Int) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val divider = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        val shown = if (all) count else minOf(count, FOLD_LIMIT)
        for (i in 0 until shown) {
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = divider)
            row(i)
        }
        if (count > FOLD_LIMIT) {
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = divider)
            Text(
                if (all) "收起" else "展开全部（$count）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { all = !all }.padding(vertical = 12.dp),
            )
        }
    }
}

/** 收起时正文两行、不显示回复；点开看全文和回复。 */
@Composable
private fun FoldRow(
    text: String,
    meta: String,
    status: String,
    highlight: Boolean,
    reply: String?,
    open: Boolean,
    onToggle: () -> Unit,
    /** 回复人（GitHub 用户名）；空 = 旧回复，显示「开发者回复」。 */
    replyBy: String = "",
) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                status,
                style = MiuixTheme.textStyles.footnote1,
                color = if (highlight) MiuixTheme.colorScheme.primary else summary,
            )
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(meta, style = MiuixTheme.textStyles.footnote2, color = summary.copy(alpha = 0.7f))
        }
        if (open && reply != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        if (replyBy.isBlank()) "开发者回复" else "开发者 @$replyBy 回复",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(reply, style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}

private fun monthDayCn(epochMs: Long): String {
    val date = campusDateTimeOf(epochMs).date
    return "${date.monthNumber}月${date.dayOfMonth}日"
}

/** `System.currentTimeMillis()` 在 commonMain 不存在（`Clock.System` 是同一时刻、同一 epoch 毫秒）。 */
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
