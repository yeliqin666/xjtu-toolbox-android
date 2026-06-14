package com.xjtu.toolbox.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val vm: AgentViewModel = viewModel()

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = "屁岱",
                largeTitle = if (showConfig) "配置" else "屁岱",
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
                    if (!showConfig) {
                        IconButton(onClick = { vm.clearMessages() }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空对话")
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
}

@Composable
private fun ChatPanel(
    vm: AgentViewModel,
    config: AgentConfig,
    loginState: com.xjtu.toolbox.AppLoginState,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // 新消息到达时滚到底部
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        input = ""
        keyboard?.hide()
        vm.sendMessage(text, config, loginState, context)
    }

    Column(
        Modifier
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
                    MessageBubble(msg, onNavigate)
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
                IconButton(
                    onClick = { send() },
                    enabled = input.isNotBlank() && !vm.isLoading
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (input.isNotBlank() && !vm.isLoading)
                            MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.outline
                    )
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
private fun MessageBubble(msg: ChatMessage, onNavigate: (String) -> Unit) {
    when (msg.role) {
        "tool_event" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    msg.content,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
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
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Card(
                    modifier = Modifier.widthIn(max = 300.dp),
                    cornerRadius = 4.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        msg.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MiuixTheme.textStyles.body1
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
    onSave: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var provider by remember { mutableStateOf(config.provider) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var maxToolCalls by remember { mutableIntStateOf(config.maxToolCalls) }

    // 模型一键拉取
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val providerItems = AgentConfig.PROVIDERS.map {
        top.yukonga.miuix.kmp.basic.DropdownItem(text = AgentConfig.providerLabel(it))
    }
    val providerIndex = AgentConfig.PROVIDERS.indexOf(provider).coerceAtLeast(0)

    LazyColumn(
        modifier = modifier.overScrollVertical(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最多工具调用次数：$maxToolCalls", style = MiuixTheme.textStyles.body1)
                    val sliderItems = (1..8).map { DropdownItem(text = it.toString()) }
                    OverlaySpinnerPreference(
                        title = "上限",
                        summary = "$maxToolCalls 次",
                        items = sliderItems,
                        selectedIndex = maxToolCalls - 1,
                        onSelectedIndexChange = { maxToolCalls = it + 1 }
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
                        maxToolCalls = maxToolCalls
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
