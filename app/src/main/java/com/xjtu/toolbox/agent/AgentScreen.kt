@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import com.xjtu.toolbox.nav.AppRoute
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStoreOwner
import com.xjtu.toolbox.auth.LocalAppLoginState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 屁岱主界面。
 *
 * 作为底栏的 0 级 tab 嵌在 MainScreen 的 Scaffold 里，自己**不**渲染 Scaffold / TopAppBar /
 * 返回箭头——顶栏归宿主，标题和「会话列表 / 设置」两个按钮通过 [onTitleChange] /
 * [onActionsChange] 反向送上去。
 */
@Composable
fun AgentScreen(
    onNavigate: (AppRoute) -> Unit = {},
    /** tab 模式下需要额外空出的底部高度（悬浮胶囊底栏不占宿主 contentPadding，见调用处）。 */
    extraBottomPadding: Dp = 0.dp,
    /** 宿主 Scaffold 的 contentPadding 底部值。输入栏算自己的让位时要把它减掉，见 AgentComposer。 */
    hostBottomPadding: Dp = 0.dp,
    scrollBehavior: ScrollBehavior? = null,
    onTitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable RowScope.() -> Unit)?) -> Unit = {},
    onNavIconChange: ((@Composable () -> Unit)?) -> Unit = {},
    /**
     * tab 模式下宿主玻璃顶栏的高度。非 0 时宿主不再整体下移这一页：对话、配置两个列表铺到
     * 顶栏下面、把这段留白放进列表里；不滚动的会话栏、抽屉自己让出这段高度。
     */
    contentTopPadding: Dp = 0.dp,
) {
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
        vm.sendMessage(pending.text, config, loginState, context, llmAnnex = pending.snapshot)
    }

    // 上下文耗尽主动弹窗：避免"输入框禁用 + label 文字里藏一句"的隐晦提示
    var showContextExhaustedDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.contextExhaustedJustTriggered) {
        if (vm.consumeContextExhaustedTrigger()) {
            showContextExhaustedDialog = true
        }
    }
    // 宽屏下会话列表是常驻左栏，根本没有「抽屉开没开」这回事。
    val isWide = com.xjtu.toolbox.ui.isWideLayout()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AgentSession?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) keyboard?.hide()
    }

    // 宿主给了就用宿主的（顶栏在宿主那儿，折叠状态必须共用同一份），没给才自己建。
    val ownScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val effectiveScrollBehavior = scrollBehavior ?: ownScrollBehavior
    // 宽屏不拦抽屉：没有抽屉可关，再拦就是吃掉一次返回。
    BackHandler(enabled = showConfig || (drawerOpen && !isWide) || showContextExhaustedDialog) {
        when {
            showContextExhaustedDialog -> showContextExhaustedDialog = false
            drawerOpen && !isWide -> drawerOpen = false
            else -> showConfig = false
        }
    }

    // 顶栏只留两个按钮：会话列表（含新建/改名/删除）与设置。
    // 两种宿主形态共用同一份，tab 模式下把它整个交给 MainScreen 的 TopAppBar。
    //
    // 这个 lambda 闭包捕获的是 showConfig / drawerOpen 的**委托属性**而不是取到的值，
    // 所以状态变化时读它的宿主会自己重组，不需要在 key 上列出来。
    // 会话列表放最左边：抽屉从左边滑出，触发它的按钮就该在左边。
    // 宽屏下会话列表已经常驻在左栏，顶栏这颗开关按钮没意义，不送给宿主。
    val headerNavIcon: (@Composable () -> Unit)? = if (isWide) null else {
        {
        AnimatedVisibility(
            // 配置面板打开时才收起这颗按钮；抽屉开着时必须留着，用户要靠它收回去。
            visible = !showConfig,
            enter = fadeIn(animationSpec = tween(160)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(animationSpec = tween(120)) + scaleOut(targetScale = 0.8f),
        ) {
            Box(Modifier.padding(start = 12.dp)) {
                AgentHeaderButton(
                    // 开关，不是单向开。再点一下收回去，别逼用户去点遮罩或按返回。
                    icon = Icons.AutoMirrored.Filled.List,
                    contentDescription = if (drawerOpen) "收起会话列表" else "会话列表",
                    active = drawerOpen,
                    onClick = { drawerOpen = !drawerOpen },
                )
            }
        }
        }
    }

    // 右侧只剩设置。active 态表示配置面板正开着。
    val headerActions: @Composable RowScope.() -> Unit = {
        AgentHeaderButton(
            icon = Icons.Default.Settings,
            contentDescription = if (showConfig) "关闭配置" else "配置",
            active = showConfig,
            onClick = {
                // 配置面板会盖住整个内容区，抽屉再留着就是压在它下面的一层死物。
                drawerOpen = false
                showConfig = !showConfig
            },
        )
        Spacer(Modifier.width(4.dp))
    }

    // 标题跟着助手名字走（用户可改名，皮肤可覆盖），进配置面板时换成「配置」。
    val hostTitle = if (showConfig) "配置" else PidaiAppearanceHost.effectiveAssistantName(config.effectiveName)
    LaunchedEffect(hostTitle) { onTitleChange(hostTitle) }
    DisposableEffect(Unit) {
        onActionsChange(headerActions)
        onNavIconChange(headerNavIcon)
        // 切走时把按钮撤掉，否则别的 tab 顶栏上会残留屁岱的图标。
        onDispose {
            onActionsChange(null)
            onNavIconChange(null)
        }
    }

    // 主体（配置面板 / 对话面板 + 两个确认框）。两种宿主形态完全一致，
    // 差别只在谁提供 padding：tab 模式下宿主 Scaffold 已经把底栏高度扣掉了。
    val body: @Composable (PaddingValues) -> Unit = { padding ->
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
                        scrollBehavior = effectiveScrollBehavior,
                        onSave = { newConfig ->
                            // 即改即存：每次配置变动立即写入，不必等到用户点「保存」
                            configStore.save(newConfig)
                            config = newConfig
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        listTopPadding = contentTopPadding,
                        // 悬浮胶囊底栏盖在内容上，不占宿主 padding，列表底部要自己让出来
                        listBottomPadding = extraBottomPadding,
                    )
                } else {
                    ChatPanel(
                        vm = vm,
                        config = config,
                        loginState = loginState,
                        padding = padding,
                        listTopPadding = contentTopPadding,
                        scrollBehavior = effectiveScrollBehavior,
                        onNavigate = onNavigate,
                        onOpenConfig = { showConfig = true },
                        bottomReserve = extraBottomPadding,
                    )
                }
            }

            // OverlayDialog 必须在 Scaffold content 里：靠 Scaffold 提供的弹窗宿主渲染。
            // 写在 Scaffold 外面会注册进空列表，点了没反应。侧栏盖在 Scaffold 之上，
            // 所以弹出前先关抽屉，否则确认框会被挡住。
            deleteTarget?.let { target ->
                OverlayDialog(
                    show = true,
                    title = "删除对话？",
                    summary = "「${target.title}」将被彻底清除，无法恢复。",
                    onDismissRequest = { deleteTarget = null },
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "取消",
                            onClick = { deleteTarget = null },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "删除",
                            onClick = {
                                vm.deleteSession(target.id)
                                deleteTarget = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }

            if (showContextExhaustedDialog) {
                OverlayDialog(
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

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
        if (isWide) {
            // 常驻会话列表。宽屏上让人点一下按钮才能看到历史对话，
            // 是把手机上的妥协搬到了放得下的屏幕上。
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    // 列表底部自带 navigationBarsPadding（抽屉需要）。宿主 Scaffold 已经让过一次
                    // 底部，不声明消费就会再让一条导航条的高度；顶部让出玻璃顶栏
                    .consumeWindowInsets(PaddingValues(bottom = hostBottomPadding))
                    .padding(top = contentTopPadding),
                // 和顶栏、聊天区同一个底色，两栏之间只靠那条竖分隔线分开。原来是 surfaceContainer（白），
                // 从顶栏下沿才开始，灰色顶栏和白色面板之间就出现一道横向断口
                color = MiuixTheme.colorScheme.surface,
            ) {
                SessionListPane(
                    sessions = vm.sessions,
                    currentId = vm.currentSessionId,
                    onNew = { vm.newSession() },
                    onSelect = { vm.switchSession(it) },
                    onRequestDelete = { deleteTarget = it },
                )
            }
            VerticalDivider()
        }
        // 把"宿主已经让出底部 hostBottomPadding"这件事声明出来。
        // 宿主 Scaffold 只 padding(padding)、没 consumeWindowInsets(padding)，
        // 消费链是断的；在这里补一次，子树里的 windowInsetsPadding（输入栏那处）
        // 才能正确扣除已让的部分，不至于把 ime 再整段加一遍。
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .consumeWindowInsets(PaddingValues(bottom = hostBottomPadding))
        ) {
            body(PaddingValues())
        }
        }

        // 窄屏的覆盖式抽屉。宽屏已经有常驻左栏，不再挂它。
        // 抽屉从顶栏下沿开始：顶栏画在 tab 内容上面，从屏幕顶开始的话上半截会被玻璃盖住。
        if (!isWide) Box(Modifier.fillMaxSize().padding(top = contentTopPadding)) { SessionDrawer(
            bottomReserve = extraBottomPadding,
            open = drawerOpen,
            sessions = vm.sessions,
            currentId = vm.currentSessionId,
            onClose = { drawerOpen = false },
            onNew = { vm.newSession(); drawerOpen = false },
            onSelect = { vm.switchSession(it); drawerOpen = false },
            onRequestDelete = {
                deleteTarget = it
                drawerOpen = false
            },
        ) }
    }
}
/** 顶栏右上角的圆形小按钮。[active] 用于表达「这个面板正开着」。 */
@Composable
private fun AgentHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val bg by animateColorAsState(
        targetValue = if (active) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        animationSpec = tween(180),
        label = "headerBtnBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) MiuixTheme.colorScheme.onPrimary
        else MiuixTheme.colorScheme.onSurface,
        animationSpec = tween(180),
        label = "headerBtnFg",
    )
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
    }
}
