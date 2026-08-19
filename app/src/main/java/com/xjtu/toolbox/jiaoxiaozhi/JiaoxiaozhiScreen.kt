package com.xjtu.toolbox.jiaoxiaozhi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.agent.MarkdownText
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val JiaozhiBlue = Color(0xFF315FD4)
private val JiaozhiPurple = Color(0xFF6750A4)

@Composable
fun JiaoxiaozhiScreen(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val loginState = LocalAppLoginState.current
    val sessionManager = checkNotNull(loginState.sessionManager)
    val vm: JiaoxiaozhiViewModel = viewModel()
    val store = remember { JiaoxiaozhiSessionStore(context) }
    LaunchedEffect(Unit) { vm.bind(store) }

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var networkEnabled by rememberSaveable { mutableStateOf(true) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerOpen, showModels) {
        if (drawerOpen || showModels) keyboard?.hide()
    }
    val currentModel = JiaoxiaozhiModels.byId(
        vm.currentSession?.modelId ?: JiaoxiaozhiModels.DEFAULT_ID
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "交晓智",
                    largeTitle = "交晓智",
                    scrollBehavior = scrollBehavior,
                    color = MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "会话列表")
                        }
                    }
                )
            }
        ) { padding ->
            JiaoxiaozhiChatPanel(
                vm = vm,
                padding = padding,
                nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                model = currentModel,
                networkEnabled = networkEnabled,
                onOpenModels = { showModels = true },
                onNetworkEnabledChange = { networkEnabled = it },
                onSend = { vm.sendMessage(it, sessionManager, networkEnabled) },
                onRetry = {
                    val lastUser = vm.messages.lastOrNull { it.role == "user" }?.content
                    if (!lastUser.isNullOrBlank()) {
                        vm.trimTrailingEmptyAssistant()
                        vm.sendMessage(lastUser, sessionManager, networkEnabled)
                    }
                },
                onOpenLink = onOpenLink,
            )
            // OverlayBottomSheet 必须写在 Scaffold 内容里，否则拿不到宿主，点了没反应。
            OverlayBottomSheet(
                show = showModels,
                title = "选择模型",
                onDismissRequest = { showModels = false },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "按会话记住。换模型不会清掉这边已有的对话。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Card(
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                    ) {
                        JiaoxiaozhiModels.all.forEachIndexed { index, model ->
                            if (index > 0) {
                                top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            val selected = model.id == currentModel.id
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.selectModel(model.id)
                                        showModels = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    model.label,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) JiaozhiPurple else MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (selected) "${model.description} · 当前使用" else model.description,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }

        JiaoxiaozhiDrawer(
            open = drawerOpen,
            sessions = vm.sessions,
            currentId = vm.currentSessionId,
            onClose = { drawerOpen = false },
            onNew = { vm.newSession(); drawerOpen = false },
            onSelect = { vm.switchSession(it); drawerOpen = false },
            onDelete = vm::deleteSession,
        )
    }
}

@Composable
private fun JiaoxiaozhiChatPanel(
    vm: JiaoxiaozhiViewModel,
    padding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    model: JiaoxiaozhiModel,
    networkEnabled: Boolean,
    onOpenModels: () -> Unit,
    onNetworkEnabledChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var input by rememberSaveable { mutableStateOf("") }

    fun send() {
        val value = input.trim()
        if (value.isBlank()) return
        input = ""
        keyboard?.hide()
        onSend(value)
    }

    Column(
        Modifier
            .fillMaxSize()
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
                        .nestedScroll(nestedScrollConnection),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            if (vm.messages.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = JiaozhiPurple,
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "可以问我校园政策、办事流程和通用问题",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "对话由学校交晓智服务处理，可能被上游记录。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(20.dp))
                        val suggestions = remember {
                            listOf(
                                "研究生复试成绩如何复议？",
                                "如何办理休学？",
                                "校历本学期怎么安排的？",
                                "教务处联系电话？",
                            )
                        }
                        suggestions.forEach { q ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = JiaozhiPurple.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        android.os.Handler(android.os.Looper.getMainLooper())
                                            .post { onSend(q) }
                                    },
                            ) {
                                Text(
                                    q,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = JiaozhiPurple,
                                )
                            }
                        }
                    }
                }
            }
            items(vm.messages, key = { "${it.createdAt}-${it.role}-${it.content.hashCode()}" }) { message ->
                val isLastAssistant = message.role == "assistant" &&
                    !vm.isLoading &&
                    message === vm.messages.lastOrNull()
                JiaoxiaozhiBubble(
                    message = message,
                    onOpenLink = onOpenLink,
                    onRetry = if (isLastAssistant && message.content.isNotBlank()) onRetry else null,
                )
            }
            if (vm.isLoading && vm.messages.lastOrNull()?.content.isNullOrBlank()) {
                item {
                    ThinkingIndicator()
                }
            }
            vm.errorMessage?.let { error ->
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MiuixTheme.colorScheme.error.copy(alpha = 0.10f),
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(12.dp),
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
                }
            }
        }

        Surface(color = MiuixTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppFilterChip(
                    selected = true,
                    onClick = onOpenModels,
                    label = model.label,
                )
                AppFilterChip(
                    selected = networkEnabled,
                    onClick = { onNetworkEnabledChange(!networkEnabled) },
                    label = if (networkEnabled) "联网" else "校内",
                    leadingIcon = {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (networkEnabled) MiuixTheme.colorScheme.onTertiaryContainer
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                )
            }
        }

        Surface(color = MiuixTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "问问交晓智…",
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                IconButton(
                    onClick = if (vm.isLoading) vm::stop else ::send,
                    enabled = vm.isLoading || input.isNotBlank(),
                ) {
                    Icon(
                        if (vm.isLoading) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (vm.isLoading) "停止生成" else "发送",
                        tint = if (vm.isLoading || input.isNotBlank()) JiaozhiPurple
                        else MiuixTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition()
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 三个圆点交替呼吸：经典"打字机"暗示
        listOf(0, 1, 2).forEach { idx ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(900, delayMillis = idx * 180),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot-$idx",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        color = JiaozhiPurple.copy(alpha = alpha),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            )
        }
        Text(
            "正在连接交晓智…",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun JiaoxiaozhiBubble(
    message: JiaoxiaozhiMessage,
    onOpenLink: (String) -> Unit,
    onCopy: (String) -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    // [LocalClipboard] 取代已废弃的 [LocalClipboardManager]：suspend setClip。
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val timestamp = remember(message.createdAt) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.createdAt))
    }
    if (message.role == "user") {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
                color = JiaozhiBlue,
                modifier = Modifier.widthIn(max = 286.dp),
            ) {
                Text(
                    message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color.White,
                    style = MiuixTheme.textStyles.body1,
                )
            }
            Text(
                timestamp,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(end = 4.dp, top = 2.dp),
            )
        }
    } else if (message.content.isNotBlank()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            MarkdownText(
                text = message.content,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                onLink = onOpenLink,
            )
            // 操作栏：复制 + 时间 + 可选重试
            Row(
                Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    timestamp,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .height(20.dp)
                        .clickable {
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    androidx.compose.ui.platform.ClipEntry(
                                        android.content.ClipData.newPlainText("message", message.content)
                                    )
                                )
                                onCopy("已复制")
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
                if (onRetry != null) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .height(20.dp)
                            .clickable { onRetry() }
                            .padding(horizontal = 6.dp, vertical = 0.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            "重试",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JiaoxiaozhiDrawer(
    open: Boolean,
    sessions: List<JiaoxiaozhiSession>,
    currentId: String?,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(1f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClose() }
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
        modifier = Modifier.zIndex(2f),
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(320.dp),
            color = MiuixTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("交晓智对话", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                        Text(
                            "本地记录，服务端按会话续聊",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = "新建对话", tint = JiaozhiPurple)
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val selected = session.id == currentId
                        Surface(
                            shape = RoundedCornerShape(13.dp),
                            color = if (selected) JiaozhiPurple.copy(alpha = 0.13f)
                            else MiuixTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp, end = 3.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { onSelect(session.id) }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        session.title,
                                        maxLines = 1,
                                        fontWeight = FontWeight.Medium,
                                        color = if (selected) JiaozhiPurple else MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "${JiaoxiaozhiModels.byId(session.modelId).label} · ${
                                            java.text.SimpleDateFormat(
                                                "MM-dd HH:mm",
                                                java.util.Locale.CHINA
                                            ).format(java.util.Date(session.updatedAt))
                                        }",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                CompactJiaoxiaozhiAction(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "删除",
                                    onClick = { onDelete(session.id) },
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactJiaoxiaozhiAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = Modifier
            .padding(start = 4.dp)
            .size(34.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(17.dp), tint = tint)
        }
    }
}
