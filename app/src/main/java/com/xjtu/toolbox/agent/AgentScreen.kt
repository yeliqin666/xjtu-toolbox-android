package com.xjtu.toolbox.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStoreOwner
import com.xjtu.toolbox.LocalAppLoginState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun AgentScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val loginState = LocalAppLoginState.current
    val configStore = remember { AgentConfigStore(context) }
    var config by remember { mutableStateOf(configStore.load()) }
    var showConfig by rememberSaveable { mutableStateOf(!config.isConfigured) }
    val vm: AgentViewModel = viewModel(viewModelStoreOwner = context as ViewModelStoreOwner)

    // 多会话持久化：绑定一次，加载会话列表并恢复最近会话
    val sessionStore = remember { AgentSessionStore(context) }
    LaunchedEffect(Unit) { vm.bind(sessionStore) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = config.effectiveName,
                    largeTitle = if (showConfig) "配置" else config.effectiveName,
                    color = MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showConfig && config.isConfigured) showConfig = false else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 顶栏只留两个按钮：会话列表（含新建/改名/删除）与设置
                        AnimatedVisibility(
                            visible = !showConfig,
                            enter = fadeIn(animationSpec = tween(160)),
                            exit = fadeOut(animationSpec = tween(120))
                        ) {
                            IconButton(onClick = { drawerOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "会话列表")
                            }
                        }
                        IconButton(onClick = { showConfig = !showConfig }) {
                            Icon(Icons.Default.Settings, contentDescription = "配置")
                        }
                    }
                )
            }
        ) { padding ->
            if (showConfig) {
                ConfigPanel(
                    config = config,
                    onCapabilitiesChange = { newConfig ->
                        configStore.save(newConfig)
                        config = newConfig
                    },
                    onSave = { newConfig ->
                        configStore.save(newConfig)
                        config = newConfig
                        showConfig = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            } else {
                ChatPanel(
                    vm = vm,
                    config = config,
                    loginState = loginState,
                    padding = padding,
                    scrollBehavior = scrollBehavior,
                    onNavigate = onNavigate
                )
            }
        }

        SessionDrawer(
            open = drawerOpen,
            sessions = vm.sessions,
            currentId = vm.currentSessionId,
            onClose = { drawerOpen = false },
            onNew = { vm.newSession(); drawerOpen = false },
            onSelect = { vm.switchSession(it); drawerOpen = false },
            onRename = { id, title -> vm.renameSession(id, title) },
            onDelete = { vm.deleteSession(it) }
        )
    }
}

@Composable
private fun SessionDrawer(
    open: Boolean,
    sessions: List<AgentSession>,
    currentId: String?,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var renameTarget by remember { mutableStateOf<AgentSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    // 半透明遮罩，点击关闭
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(140)),
        modifier = Modifier.zIndex(1f)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() }
        )
    }
    // 左侧抽屉面板
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(animationSpec = tween(260)) { -it },
        exit = slideOutHorizontally(animationSpec = tween(220)) { -it },
        modifier = Modifier.zIndex(2f)
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(300.dp),
            color = MiuixTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("对话", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = "新建对话",
                            tint = MiuixTheme.colorScheme.primary)
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sessions, key = { it.id }) { s ->
                        val isCurrent = s.id == currentId
                        val isRenaming = renameTarget?.id == s.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MiuixTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isRenaming) {
                                    TextField(
                                        value = renameText,
                                        onValueChange = { renameText = it },
                                        label = "会话标题",
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable { onSelect(s.id) }
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Text(s.title, style = MiuixTheme.textStyles.body2,
                                            fontWeight = FontWeight.Medium, maxLines = 1,
                                            color = if (isCurrent) MiuixTheme.colorScheme.primary
                                                    else MiuixTheme.colorScheme.onSurface)
                                        Text(formatSessionTime(s.updatedAt),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                                if (isRenaming) {
                                    TextButton(
                                        text = "保存",
                                        onClick = {
                                            onRename(s.id, renameText)
                                            renameTarget = null
                                        }
                                    )
                                    TextButton(text = "取消", onClick = { renameTarget = null })
                                } else {
                                    CompactSessionAction(
                                        icon = Icons.Default.Edit,
                                        contentDescription = "重命名",
                                        onClick = { renameTarget = s; renameText = s.title }
                                    )
                                    CompactSessionAction(
                                        icon = Icons.Default.Delete,
                                        contentDescription = "删除",
                                        onClick = { onDelete(s.id) },
                                        tint = MiuixTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

private fun formatSessionTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))

@Composable
private fun CompactSessionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = Modifier
            .padding(start = 4.dp)
            .size(34.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(17.dp), tint = tint)
        }
    }
}

@Composable
private fun ChatPanel(
    vm: AgentViewModel,
    config: AgentConfig,
    loginState: com.xjtu.toolbox.AppLoginState,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // 切换会话：直接定位到底部（不要从顶部滑下来）
    LaunchedEffect(vm.currentSessionId) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
    }
    // 同一会话内新消息到达：直接贴到底部
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        input = ""
        keyboard?.hide()
        vm.sendMessage(text, config, loginState, context)
    }

    Column(
        modifier
            .fillMaxSize()
            // 只吃顶部（TopAppBar 高度）；底部由输入栏自己的 navigationBarsPadding + imePadding 处理，
            // 否则会和输入栏的 inset 双重叠加，键盘弹出时把输入框顶飞。
            .padding(top = padding.calculateTopPadding())
            .background(MiuixTheme.colorScheme.surface)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (vm.messages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "你好！可以问我课表、空教室、考勤、考试安排等校园信息。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 48.dp, start = 24.dp, end = 24.dp)
                        )
                    }
                }
            }
            items(vm.messages) { msg ->
                Box(Modifier.fillMaxWidth().animateItem()) {
                    MessageBubble(msg, config.showReasoning, onNavigate)
                }
            }
            if (vm.isLoading && (vm.messages.isEmpty() || vm.messages.last().role != "tool_event")) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                        ThinkingDots()
                    }
                }
            }
        }

        // 输入栏
        Surface(color = MiuixTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "输入消息…",
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() })
                )
                // 生成中显示"停止"，否则"发送"
                if (vm.isLoading) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "停止生成",
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                } else {
                    IconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (input.isNotBlank()) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
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
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 180),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
                        RoundedCornerShape(50)
                    )
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    showReasoning: Boolean,
    onNavigate: (String) -> Unit
) {
    when (msg.role) {
        "tool_event" -> {
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MiuixTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (msg.isToolCall) {
                            CircularProgressIndicator(size = 13.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                        }
                        Text(
                            msg.content,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
        "user" -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(max = 280.dp)
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
        else -> {
            var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                if (showReasoning && msg.reasoningContent.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MiuixTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .clickable { reasoningExpanded = !reasoningExpanded }
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    "Thinking",
                                    style = MiuixTheme.textStyles.footnote1,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary
                                )
                                if (!reasoningExpanded) {
                                    Text(
                                        msg.reasoningContent
                                            .replace(Regex("\\s+"), " ")
                                            .trim(),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                Icon(
                                    if (reasoningExpanded) Icons.Default.KeyboardArrowUp
                                    else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (reasoningExpanded) "收起思考过程" else "展开思考过程",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            if (reasoningExpanded) {
                                Spacer(Modifier.height(6.dp))
                                MarkdownText(
                                    text = msg.reasoningContent,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    onLink = { url ->
                                        onNavigate("browser?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (msg.content.isNotBlank()) {
                    Card(
                        modifier = Modifier.widthIn(max = 300.dp),
                        cornerRadius = 4.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                    ) {
                        MarkdownText(
                            text = msg.content,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            onLink = { url ->
                                onNavigate("browser?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                            }
                        )
                    }
                }
                // 富控件（课表卡 / 成绩卡 / 空教室卡…），由工具结果直接渲染
                msg.widgets.forEach { widget ->
                    AgentWidgetView(
                        widget,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .widthIn(max = 340.dp)
                    )
                }
                // 跳转建议按钮（本轮涉及哪些功能页就显示对应入口）
                if (msg.navSuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        msg.navSuggestions.forEach { (label, route) ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                                modifier = Modifier.clickable { onNavigate(route) }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        label,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MiuixTheme.colorScheme.primary
                                    )
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
private fun ConfigPanel(
    config: AgentConfig,
    onCapabilitiesChange: (AgentConfig) -> Unit,
    onSave: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var provider by remember { mutableStateOf(config.provider) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var maxToolCalls by remember { mutableIntStateOf(config.maxToolCalls) }
    var assistantName by remember { mutableStateOf(config.assistantName) }
    var disabledCaps by remember { mutableStateOf(config.disabledCaps) }
    var searchEngine by remember { mutableStateOf(config.searchEngine) }
    var thinkingEnabled by remember { mutableStateOf(config.thinkingEnabled) }
    var reasoningEffort by remember { mutableStateOf(config.reasoningEffort) }
    var showReasoning by remember { mutableStateOf(config.showReasoning) }

    // 模型一键拉取
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val providerItems = AgentConfig.PROVIDERS.map {
        top.yukonga.miuix.kmp.basic.DropdownItem(text = AgentConfig.providerLabel(it))
    }
    val providerIndex = AgentConfig.PROVIDERS.indexOf(provider).coerceAtLeast(0)
    val searchEngineItems = AgentConfig.SEARCH_ENGINES.map { DropdownItem(text = AgentConfig.searchEngineLabel(it)) }
    val searchEngineIndex = AgentConfig.SEARCH_ENGINES.indexOf(searchEngine).coerceAtLeast(0)

    LazyColumn(
        modifier = modifier.overScrollVertical(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("首次使用前请确认", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                    Text(
                        "推荐使用 DeepSeek 官方 API。请妥善保管 API Key，只选择可信、可靠的 API 来源；第三方中转可能接触你的提问内容、校园查询结果和工具返回数据，存在隐私泄露风险。本应用不会替你背书任何上游服务，由此产生的密钥泄露、资费损失或隐私风险需自行承担。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                OverlaySpinnerPreference(
                    title = "服务商",
                    summary = AgentConfig.providerLabel(provider),
                    items = providerItems,
                    selectedIndex = providerIndex,
                    onSelectedIndexChange = { provider = AgentConfig.PROVIDERS[it] }
                )
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                OverlaySpinnerPreference(
                    title = "联网搜索引擎",
                    summary = AgentConfig.searchEngineLabel(searchEngine),
                    items = searchEngineItems,
                    selectedIndex = searchEngineIndex,
                    onSelectedIndexChange = { searchEngine = AgentConfig.SEARCH_ENGINES[it] }
                )
            }
        }
        item {
            val capabilities = listOf(
                "schedule" to "课表、校历、全校课程与空教室",
                "grades" to "成绩",
                "attendance" to "考勤",
                "card" to "校园卡",
                "notifications" to "通知公告",
                "yellow_page" to "校园黄页",
                "library" to "图书馆",
                "lms" to "思源学堂",
                "fitness" to "体测查询",
                "textbook" to "教材",
                "coupon" to "加餐券",
                "web" to "联网搜索与网页阅读",
                "settings_write" to "修改 App 设置"
            )
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("能力开关", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                    Text(
                        "关闭后，模型不会看到对应工具。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    capabilities.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                            Switch(
                                checked = key !in disabledCaps,
                                onCheckedChange = { enabled ->
                                    disabledCaps = if (enabled) disabledCaps - key else disabledCaps + key
                                    onCapabilitiesChange(config.copy(disabledCaps = disabledCaps))
                                }
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = assistantName,
                        onValueChange = { assistantName = it },
                        label = "助手名字（默认 屁岱）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key *",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    TextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "模型（留空使用默认）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 一键拉取模型列表，选择填入
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = if (fetchingModels) "拉取中…" else "拉取模型列表",
                            enabled = apiKey.isNotBlank() && !fetchingModels,
                            onClick = {
                                fetchError = null
                                fetchingModels = true
                                val probe = AgentConfig(provider = provider, apiKey = apiKey.trim(),
                                    model = model.trim(), baseUrl = baseUrl.trim())
                                scope.launch {
                                    try {
                                        availableModels = AgentModelFetcher.fetch(probe)
                                    } catch (e: Exception) {
                                        fetchError = e.message ?: "拉取失败"
                                        availableModels = emptyList()
                                    } finally {
                                        fetchingModels = false
                                    }
                                }
                            }
                        )
                        fetchError?.let {
                            Text(it, style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    if (availableModels.isNotEmpty()) {
                        val selIdx = availableModels.indexOf(model).coerceAtLeast(0)
                        OverlaySpinnerPreference(
                            title = "选择模型",
                            summary = model.ifBlank { "点击从 ${availableModels.size} 个模型中选择" },
                            items = availableModels.map { DropdownItem(text = it) },
                            selectedIndex = selIdx,
                            onSelectedIndexChange = { model = availableModels[it] }
                        )
                    }
                    if (provider == AgentConfig.PROVIDER_CUSTOM) {
                        TextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = "Base URL（含 /v1）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "填写 OpenAI 兼容中转地址；Claude 可通过 OpenRouter 等兼容服务接入。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
        if (provider == AgentConfig.PROVIDER_DEEPSEEK) {
            item {
                Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("思考模式", style = MiuixTheme.textStyles.body1)
                                Text(
                                    "提升复杂查询和多步工具调用的准确性",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            Switch(
                                checked = thinkingEnabled,
                                onCheckedChange = { thinkingEnabled = it }
                            )
                        }
                        if (thinkingEnabled) {
                            val efforts = AgentConfig.REASONING_EFFORTS
                            OverlaySpinnerPreference(
                                title = "思考强度",
                                summary = when (reasoningEffort) {
                                    AgentConfig.REASONING_HIGH -> "高"
                                    AgentConfig.REASONING_MAX -> "最大"
                                    else -> "自动（Agent 请求通常使用最大）"
                                },
                                items = listOf("自动", "高", "最大").map { DropdownItem(text = it) },
                                selectedIndex = efforts.indexOf(reasoningEffort).coerceAtLeast(0),
                                onSelectedIndexChange = { reasoningEffort = efforts[it] }
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("显示思考过程", style = MiuixTheme.textStyles.body1)
                                    Text(
                                        "在回答上方以折叠栏展示",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                Switch(
                                    checked = showReasoning,
                                    onCheckedChange = { showReasoning = it }
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 0 = 不限制；1..12 为具体上限（每次提问独立计数）
                    val options = listOf(0) + (1..12).toList()
                    Text(
                        "每次提问最多工具调用：" + if (maxToolCalls <= 0) "不限制" else "$maxToolCalls 次",
                        style = MiuixTheme.textStyles.body1
                    )
                    val sliderItems = options.map { DropdownItem(text = if (it == 0) "不限制" else "$it 次") }
                    OverlaySpinnerPreference(
                        title = "上限",
                        summary = if (maxToolCalls <= 0) "不限制" else "$maxToolCalls 次",
                        items = sliderItems,
                        selectedIndex = options.indexOf(maxToolCalls).coerceAtLeast(0),
                        onSelectedIndexChange = { maxToolCalls = options[it] }
                    )
                }
            }
        }
        item {
            Button(
                onClick = {
                    onSave(AgentConfig(
                        provider = provider,
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        baseUrl = baseUrl.trim(),
                        maxToolCalls = maxToolCalls,
                        assistantName = assistantName.trim(),
                        disabledCaps = disabledCaps,
                        searchEngine = searchEngine,
                        thinkingEnabled = thinkingEnabled,
                        reasoningEffort = reasoningEffort,
                        showReasoning = showReasoning
                    ))
                },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
        }
    }
}
