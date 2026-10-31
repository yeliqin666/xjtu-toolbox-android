@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.keyValueStore
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun ConfigPanel(
    config: AgentConfig,
    /** 端装配（拉模型列表是 `:data` 的一次真取数，见 [AgentHost.fetchModels]）。 */
    host: AgentHost,
    /** 「形象选择」那一块（形状 + 颜色）：`:app` 交 `PidaiAppearancePanel`。 */
    appearanceSection: (@Composable () -> Unit)? = null,
    /** API Key 那一行。 */
    apiKeySection: (@Composable () -> Unit)? = null,
    /** 「记住的偏好」那一块。 */
    memorySection: (@Composable () -> Unit)? = null,
    scrollBehavior: ScrollBehavior,
    onSave: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier,
    /** 玻璃顶栏的高度，放进列表的顶部留白。 */
    listTopPadding: Dp = 0.dp,
    /** 悬浮底栏的高度，放进列表的底部留白。 */
    listBottomPadding: Dp = 0.dp,
) {
    var provider by remember { mutableStateOf(config.provider) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var assistantName by remember { mutableStateOf(config.assistantName) }
    var searchEngine by remember { mutableStateOf(config.searchEngine) }
    var searchApiKey by remember { mutableStateOf(config.searchApiKey) }
    var thinkingEnabled by remember { mutableStateOf(config.thinkingEnabled) }
    var reasoningEffort by remember { mutableStateOf(config.reasoningEffort) }
    var showReasoning by remember { mutableStateOf(config.showReasoning) }
    var mcpEnabled by remember { mutableStateOf(config.mcpEnabled) }
    var mcpEndpoint by remember { mutableStateOf(config.mcpEndpoint) }
    var mcpToken by remember { mutableStateOf(config.mcpToken) }
    var mcpTesting by remember { mutableStateOf(false) }
    var mcpStatus by remember { mutableStateOf<String?>(null) }
    val uiPrefs = remember { keyValueStore("agent_config_ui") }
    var relayWarningDismissed by remember { mutableStateOf(uiPrefs.getBoolean("relay_warning_dismissed", false)) }

    // 辅助函数：立即保存当前所有配置
    fun saveNow() {
        onSave(AgentConfig(
            provider = provider,
            apiKey = apiKey.trim(),
            model = model.trim(),
            baseUrl = baseUrl.trim(),
            assistantName = sanitizeAgentTitle(assistantName),
            searchEngine = searchEngine,
            searchApiKey = searchApiKey.trim(),
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = reasoningEffort,
            showReasoning = showReasoning,
            mcpEnabled = mcpEnabled,
            mcpEndpoint = mcpEndpoint.trim(),
            mcpToken = mcpToken.trim(),
        ))
    }

    var pendingSaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 刚粘完 API Key 就按返回：面板的协程跟着被取消，挂着的那次保存得在这里补上
    DisposableEffect(Unit) {
        onDispose {
            if (pendingSaveJob?.isActive == true) {
                pendingSaveJob?.cancel()
                saveNow()
            }
        }
    }

    // 模型一键拉取
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    /**
     * 防抖保存：每次调用都把挂起的保存任务延后 500ms，
     * 文本框按键连续触发时只触发最后一次，避免每键一次 SharedPreferences 写。
     * 退出时没赶上的由上面的 DisposableEffect 补存。
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

    LazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + listTopPadding, bottom = 12.dp + listBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 服务商 + 助手名字/API Key/模型 + 思考参数：都是"怎么接到哪个模型、这个模型
        // 怎么想问题"这一件事，原来拆成三张卡片，合并成一张放最前面，改起来不用来回滚动。
        item {
            Card(colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    TextField(
                        value = assistantName,
                        onValueChange = {
                            assistantName = sanitizeAgentTitle(it, "")
                            scheduleSave()
                        },
                        label = "助手名字（默认 屁岱，新对话生效）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveNow() })
                    )
                    // API Key 那一行：默认 = 下面这个可输入的框（Android / 桌面逐字不变）。
                    // 浏览器那一端拿不到 key（它存在运行 serve 的那台机器上）⇒ 宿主交一格
                    // 「只读徽标 + 一句指路」把整行换掉（`docs/agent-web-spec.md` §4.3 第 2 条）。
                    if (apiKeySection != null) apiKeySection() else TextField(
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
                    // 贴在 Key 框下面的一行小字，填了 Key 就收起：整宽按钮夹在两个输入框中间像第三个表单项
                    if (provider == AgentConfig.PROVIDER_DEEPSEEK && apiKey.isBlank() && apiKeySection == null) {
                        Row(
                            Modifier.padding(start = 12.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "还没有 Key？",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                "去 DeepSeek 开放平台创建",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable { uriHandler.openUri(AgentConfig.DEEPSEEK_KEYS_URL) },
                            )
                        }
                    }
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
                        keyboardActions = KeyboardActions(onDone = { saveNow() }),
                        // 输入框右端的「拉取模型列表」：拉到以后下面出现选择器
                        trailingIcon = {
                            if (fetchingModels) {
                                top.yukonga.miuix.kmp.basic.CircularProgressIndicator(
                                    size = 20.dp,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            } else {
                                IconButton(
                                    enabled = config.isConfigured,
                                    onClick = {
                                        fetchError = null
                                        fetchingModels = true
                                        val probe = AgentConfig(provider = provider, apiKey = apiKey.trim(),
                                            model = model.trim(), baseUrl = baseUrl.trim())
                                        scope.launch {
                                            try {
                                                availableModels = host.fetchModels(probe)
                                            } catch (e: Exception) {
                                                fetchError = e.message ?: "拉取失败"
                                                availableModels = emptyList()
                                            } finally {
                                                fetchingModels = false
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "拉取模型列表",
                                        tint = if (config.isConfigured) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        },
                    )
                    fetchError?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (availableModels.isNotEmpty()) {
                        // 选中项必须和输入框一致：以前框里空着（用默认）或填了列表外的模型时，
                        // 选中项被硬定成第 0 个——看着选中了第一个、框里却是空的，点它还没反应。
                        // 现在第一项是「默认」对应空框；手填的列表外模型单独列一项。
                        val trimmed = model.trim()
                        val defaultModel = AgentConfig(provider = provider).effectiveModel
                        val extra = trimmed.takeIf { it.isNotEmpty() && it !in availableModels }
                        val options: List<String> = listOf("") + listOfNotNull(extra) + availableModels
                        val selIdx = options.indexOf(trimmed).coerceAtLeast(0)
                        OverlaySpinnerPreference(
                            title = "选择模型",
                            summary = trimmed.ifBlank { "默认（$defaultModel）" },
                            items = options.map { DropdownItem(text = it.ifBlank { "默认（$defaultModel）" }) },
                            selectedIndex = selIdx,
                            onSelectedIndexChange = {
                                model = options[it]
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
                        if (!relayWarningDismissed) {
                            Text(
                                "建议使用官方 API；第三方中转能看到你的对话，风险自负。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error
                            )
                            Text(
                                "不再提示",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    relayWarningDismissed = true
                                    uiPrefs.putBoolean("relay_warning_dismissed", true)
                                }
                            )
                        }
                    }
                    if (provider == AgentConfig.PROVIDER_DEEPSEEK) {
                        Text("思考", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
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
                                    AgentConfig.REASONING_AUTO -> "自动（由服务端按模型默认档）"
                                    else -> AgentConfig.reasoningEffortLabel(reasoningEffort)
                                },
                                // 选项文案从 efforts 现推，别再手写一份平行列表——
                                // 两边靠下标对齐，档位一多就会错位成"选高得到最大"。
                                items = efforts.map { DropdownItem(text = AgentConfig.reasoningEffortLabel(it)) },
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
            Card(colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)) {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverlaySpinnerPreference(
                        title = "联网搜索",
                        summary = AgentConfig.searchEngineLabel(searchEngine),
                        items = searchEngineItems,
                        selectedIndex = searchEngineIndex,
                        onSelectedIndexChange = {
                            searchEngine = AgentConfig.SEARCH_ENGINES[it]
                            saveNow()
                        }
                    )
                    val needsOwnKey = searchEngine == AgentConfig.SEARCH_DEEPSEEK && provider != AgentConfig.PROVIDER_DEEPSEEK
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Key 不在这一端（浏览器）时只报状态，跟 API Key 那一行同理
                        if (needsOwnKey && apiKeySection == null) {
                            TextField(
                                value = searchApiKey,
                                onValueChange = {
                                    searchApiKey = it
                                    scheduleSave()
                                },
                                label = "DeepSeek API Key（联网搜索用）",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { saveNow() })
                            )
                        }
                        Text(
                            when {
                                searchEngine == AgentConfig.SEARCH_OFF ->
                                    "不联网搜索、不读网页。"
                                needsOwnKey && apiKeySection != null ->
                                    "搜索用的 DeepSeek Key${if (config.searchKeyConfigured) "已配置" else "未配置"}，在运行 serve 的机器上设置。"
                                needsOwnKey ->
                                    "聊天没走 DeepSeek，联网搜索要单独一个 DeepSeek Key；留空则不联网搜索。"
                                else ->
                                    "DeepSeek 官方搜索，和聊天共用 Key，每千次 0.75 美元。"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
        if (host.supportsMcp) {
            item {
                Card(colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("MCP 远程工具", style = MiuixTheme.textStyles.title3)
                        Text("支持 Streamable HTTP（可带 Bearer 令牌），每次调用前需你确认。",style = MiuixTheme.textStyles.footnote1)
                        top.yukonga.miuix.kmp.preference.SwitchPreference(
                            title = "启用 MCP", checked = mcpEnabled,
                            onCheckedChange = { mcpEnabled = it; saveNow() },
                        )
                        TextField(value = mcpEndpoint, onValueChange = { mcpEndpoint = it; mcpStatus = null; scheduleSave() },
                            label = "服务地址（https://…/mcp）", singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(value = mcpToken, onValueChange = { mcpToken = it; mcpStatus = null; scheduleSave() },
                            label = "Bearer 令牌（可选）", visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(text = if (mcpTesting) "连接中…" else "测试连接", enabled = !mcpTesting && mcpEndpoint.isNotBlank(), onClick = {
                            val snapshot = config.copy(mcpEnabled = true, mcpEndpoint = mcpEndpoint.trim(), mcpToken = mcpToken.trim())
                            mcpTesting = true
                            scope.launch {
                                try {
                                    mcpStatus = host.testMcp(snapshot)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: Exception) { mcpStatus = error.message ?: "连接失败" }
                                finally { mcpTesting = false }
                            }
                        })
                        mcpStatus?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
                    }
                }
            }
        }
        // 页面上的屁岱卡只有挂了屁岱的端才有（见 AgentInsightHub）
        if (AgentInsightHub.insights.available) item { PidaiCardsSection() }
        // 形象选择：形状 + 颜色。即时生效、设备级持久化，不参与 AgentConfig 的存取
        //（见 `PidaiAppearanceHost`）。**只有有形象的端才画**：`:app` 交 `PidaiAppearancePanel`，
        // 桌面没有形象设置那一档 ⇒ 这里什么都不出（见 [AgentScreen] 的 `appearanceSection`）。
        appearanceSection?.let { section -> item { section() } }
        // 记住的偏好必须**可见可删**：模型往本机写了东西，用户有权知道写了什么。
        // 内置那一份读的是**本端**的 [AgentMemory]；浏览器上模型用的记忆在 serve ⇒ 那一块由宿主整块交进来
        //（见 [memorySection]）。
        item {
            if (memorySection != null) memorySection() else AgentMemorySection()
        }
        // 所有配置已改为即时保存，无需底部按钮
    }
}

/** 各页面屁岱卡的开关，见 [PidaiCards]。 */
@Composable
private fun PidaiCardsSection() {
    val off by PidaiCards.off.collectAsState()
    Card(colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text("页面里的屁岱卡", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "关掉的卡不再出现，也不会调用模型。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            PidaiCard.entries.forEach { card ->
                top.yukonga.miuix.kmp.preference.SwitchPreference(
                    title = card.label,
                    checked = card !in off,
                    onCheckedChange = { PidaiCards.set(card, it) },
                )
            }
        }
    }
}

/** 「记住的偏好」那一块的内置实现：读本端的 [AgentMemory]（落盘在本端数据根目录里）。 */
@Composable
private fun AgentMemorySection() {
    val memory = remember { AgentMemory() }
    var memories by remember { mutableStateOf(memory.all()) }
    if (memories.isNotEmpty()) {
        Card(colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("记住的偏好", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Text(
                    "仅存本机，最多 ${AgentMemory.MAX_ITEMS} 条，新对话起生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                memories.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                            Text(
                                v,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        TextButton(
                            text = "删除",
                            onClick = {
                                memory.forget(k)
                                memories = memory.all()
                            },
                        )
                    }
                }
                TextButton(
                    text = "全部清空",
                    onClick = {
                        memory.clear()
                        memories = memory.all()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
