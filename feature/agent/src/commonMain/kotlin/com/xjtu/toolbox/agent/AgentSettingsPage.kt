package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.ui.glass.glassBarColor
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.glassTopBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SETTINGS_TITLE = "屁岱设置"

/** 屁岱设置二级页：自带大标题顶栏和返回箭头，盖在聊天上面（聊天状态不丢）。 */
@Composable
internal fun AgentSettingsPage(
    config: AgentConfig,
    host: AgentHost,
    onSave: (AgentConfig) -> Unit,
    onBack: () -> Unit,
    appearanceSection: (@Composable () -> Unit)? = null,
    apiKeySection: (@Composable () -> Unit)? = null,
    memorySection: (@Composable () -> Unit)? = null,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()
    Scaffold(
        // 吃掉点击，免得穿到下面的聊天
        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {},
        topBar = {
            TopAppBar(
                title = SETTINGS_TITLE,
                largeTitle = SETTINGS_TITLE,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        ConfigPanel(
            config = config,
            host = host,
            appearanceSection = appearanceSection,
            apiKeySection = apiKeySection,
            memorySection = memorySection,
            scrollBehavior = scrollBehavior,
            onSave = onSave,
            modifier = Modifier
                .fillMaxSize()
                .glassSource(glass)
                .background(MiuixTheme.colorScheme.surface)
                .padding(padding.withoutTop(glass)),
            listTopPadding = padding.glassTop(glass),
        )
    }
}
