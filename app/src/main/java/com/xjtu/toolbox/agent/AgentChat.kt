@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.nav.AppRoute
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import android.widget.Toast
import kotlinx.coroutines.launch
import com.xjtu.toolbox.ui.adaptive.readableWidth
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun ChatPanel(
    vm: AgentViewModel,
    config: AgentConfig,
    loginState: com.xjtu.toolbox.auth.AppLoginState,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onNavigate: (AppRoute) -> Unit,
    onOpenConfig: () -> Unit,
    bottomReserve: Dp = 0.dp,
    /** 玻璃顶栏的高度，放进对话列表的顶部留白（列表铺到顶栏下面）。 */
    listTopPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var editingLast by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    // ── 图片附件 ──
    //
    // 只在模型认图片时给入口（见 AgentVision.supportsVision）。换模型会实时生效：
    // 从视觉模型切到非视觉模型时，已经挑好的图要清掉，否则发出去必被服务端拒。
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
    val attachScope = rememberCoroutineScope()
    val visionEnabled = remember(config.provider, config.effectiveModel) {
        AgentVision.supportsVision(config)
    }
    LaunchedEffect(visionEnabled) {
        if (!visionEnabled && attachments.isNotEmpty()) attachments = emptyList()
    }

    val pickImages = rememberLauncherForActivityResult(
        // PickMultipleVisualMedia 走系统相册选择器，**不需要读存储权限**——
        // 用户在系统 UI 里挑哪张就授权哪张，比申请 READ_MEDIA_IMAGES 干净得多。
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(
            AgentVision.MAX_IMAGES_PER_MESSAGE
        )
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachScope.launch {
            val room = AgentVision.MAX_IMAGES_PER_MESSAGE - attachments.size
            val added = withContext(Dispatchers.IO) {
                uris.take(room).mapNotNull { AgentVision.attach(context, it) }
            }
            if (added.isEmpty()) {
                Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
            } else {
                attachments = attachments + added
            }
        }
    }

    fun send() {
        val text = input.trim()
        val images = attachments
        if ((text.isBlank() && images.isEmpty()) || vm.contextExhausted) return
        // 没有 key 时点发送，直接把人送到配置页，而不是让消息石沉大海
        if (!config.isConfigured) { onOpenConfig(); return }
        input = ""
        attachments = emptyList()
        keyboard?.hide()
        if (editingLast) {
            editingLast = false
            vm.replaceLastUserAndSend(text, config, loginState, context)
        } else {
            vm.sendMessage(text, config, loginState, context, images = images)
        }
    }

    /**
     * 卡片替用户发一句。
     *
     * 走的是和手打完全一样的路径（同一个 vm.sendMessage），所以历史、工具预算、限流
     * 都照常；这条消息也会像用户自己打的那样出现在对话里，不会凭空多出一段回复。
     */
    fun askFromWidget(text: String) {
        if (text.isBlank() || vm.isLoading || vm.contextExhausted) return
        if (!config.isConfigured) { onOpenConfig(); return }
        keyboard?.hide()
        vm.sendMessage(text, config, loginState, context)
    }

    Column(
        modifier
            // 宽屏下一行聊天横跨平板全宽让人找不到行首，整块（含输入栏）限宽居中。
            .readableWidth(760.dp)
            .fillMaxSize()
            // 只吃顶部（TopAppBar 高度）；底部由输入栏自己的 navigationBarsPadding + imePadding 处理，
            // 否则会和输入栏的 inset 双重叠加，键盘弹出时把输入框顶飞。
            .padding(top = padding.calculateTopPadding())
            .background(MiuixTheme.colorScheme.surface)
    ) {
        Box(Modifier.weight(1f).clipToBounds()) {
            key(vm.currentSessionId) {
                val listState = rememberLazyListState()
                val chatRows = groupAgentRows(vm.messages, config.showReasoning)
                LaunchedEffect(chatRows.size, vm.isLoading) {
                    if (chatRows.isNotEmpty()) listState.scrollToItem(chatRows.lastIndex)
                }
                val lastUser = vm.messages.lastOrNull { it.role == "user" }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp + listTopPadding, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
            if (vm.messages.isEmpty()) {
                item(key = "welcome") {
                    AgentWelcome(
                        greetingName = loginState.cachedNickname.orEmpty()
                            .ifBlank { loginState.ywtbUserInfo?.userName.orEmpty() },
                        configured = config.isConfigured,
                        onAsk = ::askFromWidget,
                        onFill = { input = it },
                        onOpenConfig = onOpenConfig,
                    )
                }
            }
            items(chatRows, key = { it.key }) { row ->
                when (row) {
                    is AgentRow.User -> MessageBubble(
                        msg = row.msg,
                        showReasoning = config.showReasoning,
                        onNavigate = onNavigate,
                        canEdit = !vm.isLoading && row.msg === lastUser,
                        onEdit = {
                            input = row.msg.content
                            editingLast = true
                        },
                    )
                    is AgentRow.Tools -> Box(Modifier.padding(horizontal = 8.dp)) {
                        ToolCallNote(row.events)
                    }
                    is AgentRow.Assistant -> MessageBubble(
                        msg = row.msg,
                        showReasoning = config.showReasoning,
                        onNavigate = onNavigate,
                        toolEvents = row.tools,
                        streaming = vm.isLoading && row === chatRows.last() && row.msg.content.isBlank() &&
                            vm.messages.lastOrNull()?.role?.let(::isToolRole) != true,
                        onAskFromWidget = ::askFromWidget,
                    )
                }
            }
            val last = vm.messages.lastOrNull()
            // 还没有任何在动的东西可看时给一条「思考中」占位：刚发出去，或者模型在想但思考不显示
            val showThinking = vm.isLoading && when {
                last == null || last.role == "user" -> true
                isToolRole(last.role) -> false
                last.content.isBlank() -> last.reasoningContent.isBlank() || !config.showReasoning
                else -> false
            }
            if (showThinking) {
                item {
                    ReasoningBar(text = "", streaming = true)
                }
            }
                }
            }
            // 聊天中戳底栏屁岱：首屏已经不在了，就在输入框正上方冒一句小话，几秒后自己收起。
            // 不走底栏气泡（会压住输入框），也不打断对话。
            if (vm.messages.isNotEmpty()) {
                PokeWhisper(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            }
        }

        // 未配置模型时的提示条。放在输入栏正上方而不是弹窗或整页表单，
        // 是因为它要解释「为什么现在发不出去」，紧挨着发送动作才说得通。
        if (!config.isConfigured && vm.messages.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clickable { onOpenConfig() },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "先填一下模型",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "API Key 配好就能聊",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (editingLast) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "改上一条",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.clickable {
                        editingLast = false
                        input = ""
                    },
                )
            }
        }
        val composerHint = remember { COMPOSER_HINTS.random() }
        AgentComposer(
            placeholder = composerHint,
            input = input,
            onInputChange = { input = it },
            onSend = { send() },
            onStop = { vm.stop() },
            isLoading = vm.isLoading,
            contextExhausted = vm.contextExhausted,
            bottomReserve = bottomReserve,
            attachments = attachments,
            visionEnabled = visionEnabled,
            onPickImage = {
                pickImages.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts
                            .PickVisualMedia.ImageOnly
                    )
                )
            },
            onRemoveAttachment = { path ->
                attachments = attachments - path
                // 还没发出去就撤了，压缩件留着没用。
                AgentVision.deleteAll(listOf(path))
            },
        )
    }
}
/** 三个依次明灭的小圆点，替代静态"思考中…"，让等待更有生气。 */
@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        repeat(3) { i ->
            // 拿 State 本身，只在绘制阶段读：三个点闪烁时不每帧重组
            val alpha = transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 180),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            val dotColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { this.alpha = alpha.value }
                    .background(dotColor, RoundedCornerShape(50))
            )
        }
    }
}
private fun isToolRole(role: String) = role == "tool_event"
private sealed class AgentRow {
    abstract val key: String
    data class User(val msg: ChatMessage, override val key: String) : AgentRow()
    data class Tools(val events: List<ChatMessage>, override val key: String) : AgentRow()
    data class Assistant(
        val msg: ChatMessage,
        override val key: String,
        val tools: List<ChatMessage> = emptyList(),
    ) : AgentRow()
}
/**
 * 把消息流排成聊天行：连续的思考合并成一条挂在下一段正文上（有正文才分段），
 * 不显示思考时纯思考消息不出行；工具记录、结果小卡、跳转按钮归到该轮最后一行。
 */
private fun groupAgentRows(messages: List<ChatMessage>, showReasoning: Boolean): List<AgentRow> {
    val out = mutableListOf<AgentRow>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (m.role == "user") {
            out += AgentRow.User(m, "u-${m.timestamp}-$i")
            i++
            continue
        }
        val start = i
        val tools = mutableListOf<ChatMessage>()
        val block = mutableListOf<ChatMessage>()
        while (i < messages.size && messages[i].role != "user") {
            if (isToolRole(messages[i].role)) tools += messages[i] else block += messages[i]
            i++
        }
        val allWidgets = block.flatMap { it.widgets }
        val allNav = block.flatMap { it.navSuggestions }.distinctBy { it.second }

        // key 取段内第一条消息：思考变长、正文出来时仍是同一行，不闪
        val rows = mutableListOf<AgentRow.Assistant>()
        val reasoning = mutableListOf<String>()
        var segmentStart: ChatMessage? = null
        fun mergedReasoning() = if (showReasoning) reasoning.joinToString("\n\n") else ""
        for (a in block) {
            val first = segmentStart ?: a.also { segmentStart = it }
            if (a.reasoningContent.isNotBlank()) reasoning += a.reasoningContent.trim()
            if (a.content.isNotBlank()) {
                rows += AgentRow.Assistant(
                    a.copy(reasoningContent = mergedReasoning()),
                    "a-${first.timestamp}-$start-${rows.size}",
                )
                reasoning.clear()
                segmentStart = null
            }
        }
        val tailStart = segmentStart
        if (tailStart != null && mergedReasoning().isNotBlank()) {
            rows += AgentRow.Assistant(
                block.last().copy(content = "", reasoningContent = mergedReasoning()),
                "a-${tailStart.timestamp}-$start-${rows.size}",
            )
        }
        if (rows.isEmpty() && (allWidgets.isNotEmpty() || allNav.isNotEmpty())) {
            val last = block.last()
            rows += AgentRow.Assistant(last.copy(content = "", reasoningContent = ""), "a-w-${last.timestamp}-$start")
        }
        if (rows.isNotEmpty()) {
            val last = rows.last()
            rows[rows.lastIndex] = last.copy(
                msg = last.msg.copy(widgets = allWidgets, navSuggestions = allNav),
                tools = tools,
            )
            out += rows
        } else if (tools.isNotEmpty()) {
            out += AgentRow.Tools(tools, "t-${tools.first().timestamp}-$start")
        }
    }
    return out
}
/** 思考、工具调用共用的过程小卡：收起时一行摘要，点开看全部。 */
@Composable
private fun AgentTraceCard(
    label: String,
    summary: String,
    accent: Color,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    details: @Composable () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .widthIn(max = 340.dp)
            .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                leading()
                Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, color = accent)
                if (!expanded && summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                trailing()
                if (expandable) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起$label" else "展开$label",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            AnimatedVisibility(visible = expanded && expandable) {
                Column(Modifier.padding(top = 6.dp)) { details() }
            }
        }
    }
}
@Composable
private fun ReasoningBar(
    text: String,
    streaming: Boolean = false,
    onNavigate: (AppRoute) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AgentTraceCard(
        label = "思考",
        summary = text.replace(Regex("\\s+"), " ").trim(),
        accent = MiuixTheme.colorScheme.primary,
        expandable = text.isNotBlank(),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        trailing = { if (streaming) ThinkingDots() },
        details = {
            MarkdownText(
                text = text,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                onLink = { url -> onNavigate(AppRoute.Browser(url)) },
            )
        },
    )
}
private fun toolLabel(msg: ChatMessage) = msg.content.removeSuffix("…").trim()
/** 一轮里调用过的工具，收成一张小卡；多个或出错时可展开看每一次。 */
@Composable
private fun ToolCallNote(events: List<ChatMessage>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    val running = events.any { it.isToolCall }
    val hasError = events.any { it.toolError != null }
    var expanded by remember(events.first().timestamp) { mutableStateOf(false) }
    val labels = events.map { toolLabel(it) }.filter { it.isNotBlank() }
    val summary = when {
        running -> labels.lastOrNull().orEmpty()
        hasError -> labels.joinToString(" · ").ifBlank { "调用失败" }
        else -> labels.joinToString(" · ")
    }
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val tint = if (hasError) MiuixTheme.colorScheme.error else muted
    AgentTraceCard(
        label = if (running) "调用中" else "工具",
        summary = summary,
        accent = tint,
        expandable = events.size > 1 || hasError,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        leading = {
            if (running) {
                CircularProgressIndicator(size = 11.dp, strokeWidth = 1.5.dp)
            } else {
                Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.size(13.dp), tint = tint)
            }
        },
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                events.forEach { event ->
                    val err = event.toolError
                    Text(
                        if (err != null) "${toolLabel(event)} · $err" else toolLabel(event),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (err != null) MiuixTheme.colorScheme.error else muted,
                    )
                }
            }
        },
    )
}
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    showReasoning: Boolean,
    onNavigate: (AppRoute) -> Unit,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    toolEvents: List<ChatMessage> = emptyList(),
    /** 这一行是正在生成、还没出正文的最后一行：思考卡上显示跳动的点。 */
    streaming: Boolean = false,
    /** 卡片替用户问一句（如资料卡上点"打开目录"）。 */
    onAskFromWidget: (String) -> Unit = {},
) {
    // [LocalClipboard] 取代已废弃的 [LocalClipboardManager]：suspend setClip，跨进程兼容。
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun copyText(text: String) {
        coroutineScope.launch {
            clipboard.setClipEntry(
                androidx.compose.ui.platform.ClipEntry(
                    android.content.ClipData.newPlainText("message", text)
                )
            )
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
    when (msg.role) {
        "user" -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (msg.images.isNotEmpty()) {
                    Row(
                        Modifier.padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        msg.images.forEach { AttachmentThumb(it, size = 76.dp) }
                    }
                }
                // 只发图不打字时不画空气泡——一个空的蓝色圆角块看着像渲染坏了。
                if (msg.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp),
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .then(
                                if (canEdit) Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = onEdit,
                                ) else Modifier
                            ),
                    ) {
                        Text(
                            msg.content,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
        else -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (showReasoning && msg.reasoningContent.isNotBlank()) {
                    ReasoningBar(text = msg.reasoningContent, streaming = streaming, onNavigate = onNavigate)
                    if (msg.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (msg.content.isNotBlank()) {
                    MarkdownText(
                        text = msg.content,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { copyText(msg.content) },
                            ),
                        onLink = { url ->
                            onNavigate(AppRoute.Browser(url))
                        }
                    )
                }
                msg.widgets.forEach { widget ->
                    AgentWidgetView(
                        widget,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (msg.content.isNotBlank()) 10.dp else 0.dp),
                        // 卡片上点"打开目录"等于替用户问一句，直接走正常发送路径，
                        // 历史、限流、工具预算全都照常。
                        onAsk = onAskFromWidget,
                        onNavigate = onNavigate,
                    )
                }
                if (msg.navSuggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 跳转按钮：主题色淡底 + 箭头，一看就是「点了去那一页」，和普通标签区分开
                        msg.navSuggestions.forEach { (label, route) ->
                            val accent = MiuixTheme.colorScheme.primary
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(accent.copy(alpha = 0.10f))
                                    .clickable { appRouteOf(route)?.let(onNavigate) }
                                    .padding(start = 12.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    fontWeight = FontWeight.Medium,
                                    color = accent,
                                )
                                Spacer(Modifier.width(3.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
                if (toolEvents.isNotEmpty()) {
                    ToolCallNote(toolEvents, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
/**
 * 聊天中戳屁岱的回应：输入框上方浮起一颗小气泡，左边是迷你屁岱，右边是那句闲话，
 * 弹一下出来，3.5 秒后淡出。每戳一次（[ProactiveBubbleHost.heroPokes] 变化）换一句。
 */
@Composable
private fun PokeWhisper(modifier: Modifier = Modifier) {
    val host = ProactiveBubbleHost
    val seen = remember { intArrayOf(host.heroPokes) }
    var text by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(host.heroPokes) {
        if (host.heroPokes == seen[0]) return@LaunchedEffect
        seen[0] = host.heroPokes
        text = host.heroLine ?: return@LaunchedEffect
        visible = true
        kotlinx.coroutines.delay(3500)
        visible = false
    }
    val look = pidaiNavAppearance()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible && text != null,
        enter = androidx.compose.animation.fadeIn(tween(160)) +
            androidx.compose.animation.scaleIn(
                androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f),
                initialScale = 0.7f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
            ),
        exit = androidx.compose.animation.fadeOut(tween(220)) +
            androidx.compose.animation.slideOutVertically(tween(220)) { it / 3 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.25f), spotColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .clickable { visible = false }
                .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BloubBotIcon(
                beat = PidaiBeat.IDLE,
                ink = look.ink,
                paper = MiuixTheme.colorScheme.surfaceVariant,
                shape = look.shape,
                skin = look.skin,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text.orEmpty(),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
    }
}
