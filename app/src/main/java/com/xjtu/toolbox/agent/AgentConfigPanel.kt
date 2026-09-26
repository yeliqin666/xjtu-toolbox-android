@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun ConfigPanel(
    config: AgentConfig,
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
            assistantName = sanitizeAgentTitle(assistantName),
            searchEngine = searchEngine,
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

    LazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + listTopPadding, bottom = 12.dp + listBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f).compositeOver(com.xjtu.toolbox.ui.components.AppCardColor))) {
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
                    // 当前皮肤设了自己的名字时，这里填的名字不生效——说明白，
                    // 免得用户改名字没反应还以为是 bug。
                    PidaiAppearanceHost.activeSkin?.persona?.displayName?.takeIf { it.isNotBlank() }?.let { skinName ->
                        Text(
                            "当前皮肤把助手改叫「${sanitizeAgentTitle(skinName)}」，这里填的名字暂时不生效；换一个没设名字的皮肤或取消皮肤即可用回这里的名字。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
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
                                    enabled = apiKey.isNotBlank(),
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
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "拉取模型列表",
                                        tint = if (apiKey.isNotBlank()) MiuixTheme.colorScheme.primary
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
        // 形象选择：形状 + 颜色。即时生效、设备级持久化，不参与 AgentConfig 的存取（见 PidaiAppearanceHost）。
        item { PidaiAppearancePanel() }
        item {
            // 记住的偏好必须**可见可删**：模型往本机写了东西，用户有权知道写了什么。
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var memories by remember { mutableStateOf(AgentMemory.all(ctx)) }
            if (memories.isNotEmpty()) {
                Card(colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("记住的偏好", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                        Text(
                            "只存在这台设备上，不上传。最多 ${AgentMemory.MAX_ITEMS} 条。增删从下一个新对话起生效。",
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
                                        AgentMemory.forget(ctx, k)
                                        memories = AgentMemory.all(ctx)
                                    },
                                )
                            }
                        }
                        TextButton(
                            text = "全部清空",
                            onClick = {
                                AgentMemory.clear(ctx)
                                memories = AgentMemory.all(ctx)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        // 所有配置已改为即时保存，无需底部按钮
    }
}
