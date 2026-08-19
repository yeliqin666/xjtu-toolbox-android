@file:OptIn(ExperimentalLayoutApi::class)

package com.xjtu.toolbox.agent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.ui.draw.clipToBounds
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
    // 不要 rememberSaveable：冷启动第一帧可能读到空 key，之后会把设置页钉死。
    var showConfig by remember { mutableStateOf(false) }
    val vm: AgentViewModel = viewModel(viewModelStoreOwner = context as ViewModelStoreOwner)

    // 多会话持久化：绑定一次，加载会话列表并恢复最近会话
    val sessionStore = remember { AgentSessionStore(context) }
    LaunchedEffect(Unit) { vm.bind(sessionStore) }

    LaunchedEffect(loginState.accountId) {
        config = configStore.load()
        // 刻意不再「没配 key 就自动跳设置页」：那样每次进来都是一屏表单，
        // 既没解释为什么，也让人以为点错了。改为正常进对话界面，
        // 由输入栏上方的提示条说明情况并提供入口（见 ChatPanel 的 needsSetup）。
    }

    // 从主动提醒气泡进来时，**开一个新会话**并把提醒作为真实的第一条用户消息发出去。
    // 不新建会话的话会接在上一次的对话尾巴上，用户看到的就是"老对话"，
    // 跟刚才气泡说的事毫无关系。
    LaunchedEffect(config.isConfigured, AgentPendingPrompt.generation) {
        if (!config.isConfigured) return@LaunchedEffect
        val pending = AgentPendingPrompt.consume() ?: return@LaunchedEffect
        showConfig = false
        vm.newSession()
        vm.sendMessage(pending, config, loginState, context)
    }

    // 上下文耗尽主动弹窗：避免"输入框禁用 + label 文字里藏一句"的隐晦提示
    var showContextExhaustedDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.contextExhaustedJustTriggered) {
        if (vm.consumeContextExhaustedTrigger()) {
            showContextExhaustedDialog = true
        }
    }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) keyboard?.hide()
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BackHandler(enabled = showConfig || drawerOpen || showContextExhaustedDialog) {
        when {
            showContextExhaustedDialog -> showContextExhaustedDialog = false
            drawerOpen -> drawerOpen = false
            else -> showConfig = false
        }
    }

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
                            if (showConfig) showConfig = false else onBack()
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
            AnimatedContent(
                targetState = showConfig,
                transitionSpec = {
                    val direction = if (targetState) 1 else -1
                    (fadeIn(animationSpec = tween(180)) +
                        slideInHorizontally(animationSpec = tween(220)) { direction * it / 5 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(120)) +
                                slideOutHorizontally(animationSpec = tween(180)) { -direction * it / 6 }
                        )
                },
                label = "agentContent",
            ) { configVisible ->
                if (configVisible) {
                    ConfigPanel(
                        config = config,
                        scrollBehavior = scrollBehavior,
                        onSave = { newConfig ->
                            // 即改即存：每次配置变动立即写入，不必等到用户点「保存」
                            configStore.save(newConfig)
                            config = newConfig
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
                        onNavigate = onNavigate,
                        onOpenConfig = { showConfig = true }
                    )
                }
            }
        }

        SessionDrawer(
            open = drawerOpen,
            sessions = vm.sessions,
            currentId = vm.currentSessionId,
            onClose = { drawerOpen = false },
            onNew = { vm.newSession(); drawerOpen = false },
            onSelect = { vm.switchSession(it); drawerOpen = false },
            onDelete = { vm.deleteSession(it) }
        )

        // 上下文耗尽弹窗
        if (showContextExhaustedDialog) {
            top.yukonga.miuix.kmp.overlay.OverlayDialog(
                show = true,
                title = "对话上下文已达上限",
                summary = AgentViewModel.CONTEXT_EXHAUSTED_MESSAGE + "\n\n继续累积可能导致 AI 回复变慢或回答失准。",
                onDismissRequest = { showContextExhaustedDialog = false }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "稍后",
                        onClick = { showContextExhaustedDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "新建对话",
                        onClick = {
                            showContextExhaustedDialog = false
                            vm.newSession()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
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
    onDelete: (String) -> Unit
) {
    var deleteTarget by remember { mutableStateOf<AgentSession?>(null) }

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
            modifier = Modifier.fillMaxHeight().width(320.dp),
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
                    if (sessions.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "还没有任何对话",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "点右上的 + 开始第一个",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    items(sessions, key = { it.id }) { s ->
                        val isCurrent = s.id == currentId
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
                                CompactSessionAction(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "删除",
                                    onClick = { deleteTarget = s },
                                    tint = MiuixTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗：避免一不小心把整段对话清空
    deleteTarget?.let { target ->
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(3f)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { deleteTarget = null },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MiuixTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* 拦截点击，避免冒泡关闭 */ },
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "删除对话？",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "「${target.title}」将被彻底清除，无法恢复。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        DrawerTextAction(
                            text = "取消",
                            primary = false,
                            onClick = { deleteTarget = null },
                        )
                        DrawerTextAction(
                            text = "删除",
                            primary = true,
                            onClick = {
                                onDelete(target.id)
                                deleteTarget = null
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatSessionTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))

@Composable
private fun DrawerTextAction(
    text: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MiuixTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = if (primary) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

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
    onOpenConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    fun send() {
        val text = input.trim()
        if (text.isBlank() || vm.contextExhausted) return
        // 没有 key 时点发送，直接把人送到配置页，而不是让消息石沉大海
        if (!config.isConfigured) { onOpenConfig(); return }
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
            .clipToBounds()
    ) {
        Box(Modifier.weight(1f).clipToBounds()) {
            key(vm.currentSessionId) {
                val listState = rememberLazyListState()
                LaunchedEffect(Unit) {
                    if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
                }
                LaunchedEffect(vm.messages.size) {
                    if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
            if (vm.messages.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "你好！可以问我课表、空教室、考勤、考试安排等校园信息。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // 推荐问题：点击直接发到输入框或直接发送，帮初次用户快速上手
                        Spacer(Modifier.height(18.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                "本周有什么考试？",
                                "明天哪里有空教室？",
                                "我最近的成绩怎么样？",
                                "校园卡余额多少？",
                                "教务处电话？",
                            ).forEach { q ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        input = q
                                    },
                                ) {
                                    Text(
                                        q,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            items(vm.messages, key = { "${it.timestamp}-${it.role}-${it.content.hashCode()}" }) { msg ->
                MessageBubble(msg, config.showReasoning, onNavigate)
            }
            if (vm.isLoading && (vm.messages.isEmpty() || vm.messages.last().role != "tool_event")) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                        ThinkingDots()
                    }
                }
            }
                }
            }
        }

        // 未配置模型时的提示条。放在输入栏正上方而不是弹窗或整页表单，
        // 是因为它要解释「为什么现在发不出去」，紧挨着发送动作才说得通。
        if (!config.isConfigured) {
            Surface(
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenConfig() },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "还没有配置模型",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "填入 API Key 后即可开始对话",
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
                    label = if (vm.contextExhausted) AgentViewModel.CONTEXT_EXHAUSTED_MESSAGE else "输入消息…",
                    enabled = !vm.contextExhausted,
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
                        enabled = input.isNotBlank() && !vm.contextExhausted
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (input.isNotBlank() && !vm.contextExhausted) MiuixTheme.colorScheme.primary
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
    onNavigate: (String) -> Unit,
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current,
) {
    // [LocalClipboard] 取代已废弃的 [LocalClipboardManager]：suspend setClip，跨进程兼容。
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val timestamp = remember(msg.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(msg.timestamp))
    }
    when (msg.role) {
        "tool_event" -> {
            val hasError = msg.toolError != null
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasError) MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        else MiuixTheme.colorScheme.surfaceVariant,
                    modifier = if (hasError) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = top.yukonga.miuix.kmp.utils.SinkFeedback(),
                        onClick = {
                            // 失败气泡可点击：把错误细节复制到剪贴板，方便用户反馈 bug
                            val full = "${msg.content} · ${msg.toolError}"
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    androidx.compose.ui.platform.ClipEntry(
                                        android.content.ClipData.newPlainText("agent error", full)
                                    )
                                )
                            }
                        },
                    ) else Modifier,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when {
                            hasError -> Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = "工具调用失败",
                                modifier = Modifier.size(13.dp),
                                tint = MiuixTheme.colorScheme.error,
                            )
                            msg.isToolCall -> CircularProgressIndicator(size = 13.dp, strokeWidth = 2.dp)
                            else -> Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                        }
                        Text(
                            if (hasError) "${msg.content} · ${msg.toolError}" else msg.content,
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (hasError) MiuixTheme.colorScheme.error
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        "user" -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
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
                Text(
                    timestamp,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(end = 4.dp, top = 2.dp),
                )
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
                    MarkdownText(
                        text = msg.content,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        onLink = { url ->
                            onNavigate("browser?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                        }
                    )
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
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                // 操作栏：时间 + 复制（最后一行）
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        timestamp,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (msg.content.isNotBlank()) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .height(20.dp)
                                .clickable {
                                    coroutineScope.launch {
                                        clipboard.setClipEntry(
                                            androidx.compose.ui.platform.ClipEntry(
                                                android.content.ClipData.newPlainText("message", msg.content)
                                            )
                                        )
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 0.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Text(
                                "复制",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        // 分享按钮：复用 ShareUtils 写 txt → ACTION_SEND chooser
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    com.xjtu.toolbox.util.ShareUtils.shareText(
                                        context = context,
                                        title = "屁岱回复",
                                        text = msg.content,
                                        fileBaseName = "pidai_${msg.timestamp}.txt",
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 0.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Text(
                                "分享",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
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
    scrollBehavior: ScrollBehavior,
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
    var responseStyle by remember { mutableStateOf(config.responseStyle) }
    var thinkingEnabled by remember { mutableStateOf(config.thinkingEnabled) }
    var reasoningEffort by remember { mutableStateOf(config.reasoningEffort) }
    var showReasoning by remember { mutableStateOf(config.showReasoning) }

    // 辅助函数：立即保存当前所有配置
    fun saveNow() {
        onSave(AgentConfig(
            provider = provider,
            apiKey = apiKey.trim(),
            model = model.trim(),
            baseUrl = baseUrl.trim(),
            maxToolCalls = maxToolCalls,
            assistantName = sanitizeAgentTitle(assistantName),
            disabledCaps = disabledCaps,
            searchEngine = searchEngine,
            responseStyle = responseStyle,
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = reasoningEffort,
            showReasoning = showReasoning
        ))
    }

    /**
     * 防抖保存：每次调用都把挂起的保存任务延后 500ms，
     * 文本框按键连续触发时只触发最后一次，避免每键一次 SharedPreferences 写。
     * 切页 / 退出 Composable 时通过 `DisposableEffect` flush。
     */
    var pendingSaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 模型一键拉取
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * 防抖保存：每次调用都把挂起的保存任务延后 500ms，
     * 文本框按键连续触发时只触发最后一次，避免每键一次 SharedPreferences 写。
     * 切页 / 退出 Composable 时通过 `DisposableEffect` flush。
     */
    fun scheduleSave() {
        pendingSaveJob?.cancel()
        pendingSaveJob = scope.launch {
            kotlinx.coroutines.delay(500)
            saveNow()
            pendingSaveJob = null
        }
    }

    val providerItems = AgentConfig.PROVIDERS.map {
        top.yukonga.miuix.kmp.basic.DropdownItem(text = AgentConfig.providerLabel(it))
    }
    val providerIndex = AgentConfig.PROVIDERS.indexOf(provider).coerceAtLeast(0)
    val searchEngineItems = AgentConfig.SEARCH_ENGINES.map { DropdownItem(text = AgentConfig.searchEngineLabel(it)) }
    val searchEngineIndex = AgentConfig.SEARCH_ENGINES.indexOf(searchEngine).coerceAtLeast(0)
    val responseStyleItems = AgentConfig.RESPONSE_STYLES.map { DropdownItem(text = AgentConfig.responseStyleLabel(it)) }
    val responseStyleIndex = AgentConfig.RESPONSE_STYLES.indexOf(responseStyle).coerceAtLeast(0)

    LazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
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
                    onSelectedIndexChange = { 
                        provider = AgentConfig.PROVIDERS[it]
                        saveNow()
                    }
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
                    onSelectedIndexChange = { 
                        searchEngine = AgentConfig.SEARCH_ENGINES[it]
                        saveNow()
                    }
                )
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                OverlaySpinnerPreference(
                    title = "回复风格",
                    summary = AgentConfig.responseStyleLabel(responseStyle),
                    items = responseStyleItems,
                    selectedIndex = responseStyleIndex,
                    onSelectedIndexChange = { 
                        responseStyle = AgentConfig.RESPONSE_STYLES[it]
                        saveNow()
                    }
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
                "device_write" to "系统闹钟与日历",
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
                                    saveNow()
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
                        onValueChange = {
                            assistantName = sanitizeAgentTitle(it, "")
                            scheduleSave()
                        },
                        label = "助手名字（默认 屁岱）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveNow() })
                    )
                    TextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            scheduleSave()
                        },
                        label = "API Key *",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { saveNow() })
                    )
                    TextField(
                        value = model,
                        onValueChange = {
                            model = it
                            scheduleSave()
                        },
                        label = "模型（留空使用默认）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveNow() })
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
                            onSelectedIndexChange = {
                                model = availableModels[it]
                                saveNow()
                            }
                        )
                    }
                    if (provider == AgentConfig.PROVIDER_CUSTOM) {
                        TextField(
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                scheduleSave()
                            },
                            label = "Base URL（含 /v1）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { saveNow() })
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
                                onCheckedChange = { 
                                    thinkingEnabled = it
                                    saveNow()
                                }
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
                                onSelectedIndexChange = {
                                    reasoningEffort = efforts[it]
                                    saveNow()
                                }
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
                                    onCheckedChange = { 
                                        showReasoning = it
                                        saveNow()
                                    }
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
                        onSelectedIndexChange = {
                            maxToolCalls = options[it]
                            saveNow()
                        }
                    )
                }
            }
        }
        // 所有配置已改为即时保存，无需底部按钮
    }
}
