package com.xjtu.toolbox.plugin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.CouiPreferenceItem
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.reportScrollToTopBar
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 「允许小岱调用」的二级页：上面一个总开关，下面每个工具一行（长按看 definition）。
 *
 * 总开关关掉时下面整块收起——单项设置不丢，重新打开还是原样。
 */
@Composable
internal fun PluginToolsScreen(
    pluginId: String,
    onBack: () -> Unit,
    demo: Boolean = PluginOverlay.listDemo,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    var preview by remember { mutableStateOf<PluginToolManifest?>(null) }

    val record = (if (demo) demoRecords() else manager.records).firstOrNull { it.id == pluginId }
    if (!demo) LaunchedEffect(Unit) { manager.refresh() }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = record?.manifest?.name ?: "工具",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        if (record == null) {
            EmptyState(
                title = "插件不在了",
                subtitle = "可能已经卸载了",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .reportScrollToTopBar(scrollState, scrollBehavior)
                .verticalScroll(scrollState)
                .overScrollVertical()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .padding(top = padding.glassTop(glass) + 6.dp, bottom = 24.dp),
        ) {
            CouiSettingsCard {
                SwitchPreference(
                    title = "允许小岱调用",
                    summary = "关掉后下面每个工具都不生效；单项设置会留着",
                    checked = record.toolsEnabled,
                    onCheckedChange = { on ->
                        if (!demo) scope.launch { manager.setToolsEnabled(record.id, on) }
                    },
                )
            }

            // 总开关关着 → 整块收起
            AnimatedVisibility(
                visible = record.toolsEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CouiSettingsCard(Modifier.padding(top = 8.dp)) {
                    record.manifest.tools.forEach { tool ->
                        ToolRow(
                            tool = tool,
                            checked = tool.name !in record.disabledTools,
                            onToggle = {
                                if (!demo) {
                                    scope.launch {
                                        manager.setToolEnabled(
                                            record.id,
                                            tool.name,
                                            tool.name in record.disabledTools,
                                        )
                                    }
                                }
                            },
                            onLongPress = { preview = tool },
                        )
                    }
                }
            }
        }

        // 挂在本页 Scaffold 里：Overlay 系列靠它的 popup host 渲染
        preview?.let { tool ->
            ToolDefinitionSheet(tool = tool, onDismiss = { preview = null })
        }
    }
}

/** 一行工具：名字 + 描述 + 右侧开关；长按看 definition。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(
    tool: PluginToolManifest,
    checked: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val description = tool.definition["function"]?.jsonObject
        ?.get("description")?.jsonPrimitive?.content.orEmpty()
    CouiPreferenceItem(
        title = tool.name,
        summary = description,
        // CouiPreferenceItem 的 onClick 不带长按，自己挂 combinedClickable，按压反馈照它用下沉
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = SinkFeedback(),
            onClick = onToggle,
            onLongClick = onLongPress,
        ),
        onClick = null,
        trailing = {
            Switch(checked = checked, onCheckedChange = { onToggle() })
        },
    )
}

/** 工具的 definition 原文：只读输入框，方便选中复制。 */
@Composable
private fun ToolDefinitionSheet(tool: PluginToolManifest, onDismiss: () -> Unit) {
    OverlayBottomSheet(
        show = true,
        title = tool.name,
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "长按文本可以选中复制",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = PrettyJson.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    tool.definition,
                ),
                onValueChange = {},
                readOnly = true,
                label = "definition",
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp),
            )
        }
    }
}

/** 只用来把 definition 排版好看一点。 */
private val PrettyJson = Json { prettyPrint = true }
