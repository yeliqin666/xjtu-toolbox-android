package com.xjtu.toolbox.jiaoxiaozhi

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.agent.MarkdownText
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "交晓智",
                    largeTitle = "交晓智",
                    color = MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showModels = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "选择模型", tint = JiaozhiPurple)
                        }
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
                networkEnabled = networkEnabled,
                onNetworkEnabledChange = { networkEnabled = it },
                onSend = { vm.sendMessage(it, sessionManager, networkEnabled) },
                onOpenLink = onOpenLink,
            )
        }

        JiaoxiaozhiDrawer(
            open = drawerOpen,
            sessions = vm.sessions,
            currentId = vm.currentSessionId,
            onClose = { drawerOpen = false },
            onNew = { vm.newSession(); drawerOpen = false },
            onSelect = { vm.switchSession(it); drawerOpen = false },
            onRename = vm::renameSession,
            onDelete = vm::deleteSession,
        )
    }

    if (showModels) {
        ModelDialog(
            selectedId = vm.currentSession?.modelId ?: JiaoxiaozhiModels.DEFAULT_ID,
            onSelect = {
                vm.selectModel(it)
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }
}

@Composable
private fun JiaoxiaozhiChatPanel(
    vm: JiaoxiaozhiViewModel,
    padding: PaddingValues,
    networkEnabled: Boolean,
    onNetworkEnabledChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

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
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OfficialServiceBanner(
                    model = JiaoxiaozhiModels.byId(
                        vm.currentSession?.modelId ?: JiaoxiaozhiModels.DEFAULT_ID
                    ),
                    networkEnabled = networkEnabled,
                    onNetworkEnabledChange = onNetworkEnabledChange,
                )
            }
            if (vm.messages.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 30.dp),
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
                    }
                }
            }
            items(vm.messages, key = { "${it.createdAt}-${it.role}" }) { message ->
                JiaoxiaozhiBubble(message, onOpenLink)
            }
            if (vm.isLoading && vm.messages.lastOrNull()?.content.isNullOrBlank()) {
                item {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        Text(
                            "正在连接交晓智…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
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
private fun OfficialServiceBanner(
    model: JiaoxiaozhiModel,
    networkEnabled: Boolean,
    onNetworkEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = JiaozhiBlue.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(JiaozhiBlue, JiaozhiPurple)
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "学校交晓智服务",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${model.label} · ${model.description}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (networkEnabled) JiaozhiBlue else MiuixTheme.colorScheme.outline,
                )
                Switch(
                    checked = networkEnabled,
                    onCheckedChange = onNetworkEnabledChange,
                )
            }
        }
    }
}

@Composable
private fun JiaoxiaozhiBubble(
    message: JiaoxiaozhiMessage,
    onOpenLink: (String) -> Unit,
) {
    if (message.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
        }
    } else if (message.content.isNotBlank()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                color = JiaozhiPurple.copy(alpha = 0.10f),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                MarkdownText(
                    text = message.content,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    onLink = onOpenLink,
                )
            }
        }
    }
}

@Composable
private fun ModelDialog(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "选择本会话模型",
        summary = "模型选择按会话保存；切换不会清空该会话的上游上下文。",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JiaoxiaozhiModels.all.forEach { model ->
                val selected = model.id == selectedId
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) JiaozhiPurple.copy(alpha = 0.14f)
                    else MiuixTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(model.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            model.label,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) JiaozhiPurple else MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            model.description,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<JiaoxiaozhiSession?>(null) }
    var deleteTarget by remember { mutableStateOf<JiaoxiaozhiSession?>(null) }

    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
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
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(306.dp),
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
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(session.id) },
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp, end = 3.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
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
                                IconButton(onClick = { renameTarget = session }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "重命名",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(onClick = { deleteTarget = session }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        OverlayDialog(
            show = true,
            title = "重命名对话",
            onDismissRequest = { renameTarget = null },
        ) {
            Column {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "标题",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { renameTarget = null },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            onRename(target.id, title)
                            renameTarget = null
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        OverlayDialog(
            show = true,
            title = "删除对话",
            summary = "确定删除「${target.title}」吗？本地记录将不可恢复。",
            onDismissRequest = { deleteTarget = null },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { deleteTarget = null },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onDelete(target.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("删除") }
            }
        }
    }
}
