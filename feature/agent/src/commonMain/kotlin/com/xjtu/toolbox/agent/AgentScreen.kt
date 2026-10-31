@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 屁岱主界面。
 *
 * 作为底栏的 0 级 tab 嵌在 MainScreen 的 Scaffold 里，自己**不**渲染 Scaffold / TopAppBar /
 * 返回箭头——顶栏归宿主，标题、副标题（当前会话）和「历史 / 新对话 / 设置」按钮通过
 * [onTitleChange] / [onSubtitleChange] / [onActionsChange] / [onNavIconChange] 反向送上去。
 * 侧栏和设置页是全屏层：宿主给了 [overlays] 就画在宿主最外层（盖住顶栏和底栏），否则盖在本页内容区。
 */
@Composable
fun AgentScreen(
    host: AgentHost,
    /** 驱动这一屏的那一份会话状态。 */
    session: AgentChatSession? = null,
    /** 系统相册挑图的适配器（`:app` 用 `rememberLauncherForActivityResult` 实现，见那个模块的 `rememberAndroidAgentImagePicker`）。 */
    imagePicker: AgentImagePicker = AgentImagePicker.None,
    onNavigate: (AppRoute) -> Unit = {},
    /** tab 模式下需要额外空出的底部高度（悬浮胶囊底栏不占宿主 contentPadding，见调用处）。 */
    extraBottomPadding: Dp = 0.dp,
    /** 宿主 Scaffold 的 contentPadding 底部值。输入栏算自己的让位时要把它减掉，见 AgentComposer。 */
    hostBottomPadding: Dp = 0.dp,
    scrollBehavior: ScrollBehavior? = null,
    onTitleChange: (String) -> Unit = {},
    onSubtitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable RowScope.() -> Unit)?) -> Unit = {},
    onNavIconChange: ((@Composable () -> Unit)?) -> Unit = {},
    overlays: AgentOverlays? = null,
    /** 首屏那个「屁岱形象 + 问候」：`:app` 交 `AndroidPidaiHero`（形象、瞟眼、戳一下换一句话）；没有形象的端用默认实现（纯文字问候，见 [agentWelcomePlainHero]）。 */
    hero: @Composable (title: String, facts: WelcomeFacts, now: kotlinx.datetime.LocalDateTime) -> Unit =
        { title, f, n -> agentWelcomePlainHero(title, f, n) },
    /** 「戳一下底栏屁岱」的回应气泡：`:app` 交 `AndroidPokeWhisper`；没有形象的端默认不画。 */
    pokeWhisper: @Composable (Modifier) -> Unit = {},
    /** 「形象选择」那一整块：`:app` 交 `PidaiAppearancePanel`；没有形象设置的端传 null。 */
    appearanceSection: (@Composable () -> Unit)? = null,
    /** 配置面板里那两格要交给宿主的（见 [ConfigPanel] 的 `apiKeySection` / `memorySection`）：浏览器那一端拿不到 key（它在 serve 上）、模型用的记忆也在 serve ⇒ 这两块得由它自己画。 */
    apiKeySection: (@Composable () -> Unit)? = null,
    memorySection: (@Composable () -> Unit)? = null,
    /**
     * tab 模式下宿主玻璃顶栏的高度。非 0 时宿主不再整体下移这一页：对话列表铺到
     * 顶栏下面、把这段留白放进列表里；不滚动的宽屏会话栏自己让出这段高度。
     */
    contentTopPadding: Dp = 0.dp,
) {
    var config by remember { mutableStateOf(host.loadConfig()) }
    // 不要 rememberSaveable：冷启动第一帧可能读到空 key，之后会把设置页钉死。
    var showConfig by remember { mutableStateOf(false) }
    // 宿主侧 remember，key 固定即可（切 tab 不丢）。
    val vm: AgentChatSession = session ?: viewModel(key = "agent") { AgentViewModel(host) }

    vm.externalToolRequest?.let { request ->
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = true, title = "允许调用远程工具？", summary = "确认后，以下参数将发送至此服务。服务返回的数据也会交给当前 AI 模型。",
            onDismissRequest = { vm.answerExternalTool(false) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.heightIn(max = 280.dp).then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))) {
                    Text("服务：${request.endpoint}\n工具：${request.tool}\n参数：${request.arguments}")
                }
                val leaks = remember(request.arguments) { personalDataHints(request.arguments) }
                if (leaks.isNotEmpty()) {
                    Text(
                        "参数里像是有${leaks.joinToString("、")}，确认是你要发出去的再允许",
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "拒绝", onClick = { vm.answerExternalTool(false) }, modifier = Modifier.weight(1f))
                    TextButton(text = "允许这一次", onClick = { vm.answerExternalTool(true) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    // 多会话持久化：加载会话列表并恢复最近会话，换账号时重新加载
    LaunchedEffect(host.accountId) {
        vm.bind(host.sessions())
        config = host.loadConfig()
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
        if (pending.draftOnly) {
            vm.draft = pending.text
            return@LaunchedEffect
        }
        vm.sendMessage(pending.text, config, llmAnnex = pending.snapshot, images = pending.images)
    }

    // 点屁岱推送的通知进来：切到「推送记录」，定位并闪一下那一条（#194）
    // 正开着这个会话时后台追加了推送也重读；生成中不读，结束写盘时会并回推送并重读
    LaunchedEffect(host.accountId, AgentPushFocus.generation, AgentPushFocus.appended) {
        val at = AgentPushFocus.pending
        if (at == null && (vm.currentSessionId != AgentPushFocus.SESSION_ID || vm.isLoading)) return@LaunchedEffect
        // 兜底：生成卡住时也别一直挂着，超时照样重读
        withTimeoutOrNull(60_000L) { snapshotFlow { vm.isLoading }.first { !it } }
        if (!vm.reloadSession(AgentPushFocus.SESSION_ID) || at == null) return@LaunchedEffect
        AgentPushFocus.consume()
        showConfig = false
        AgentPushFocus.highlight = at
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
    // 长按会话 → 操作菜单 → 改名 / 删除确认
    var actionTarget by remember { mutableStateOf<AgentSession?>(null) }
    var renameTarget by remember { mutableStateOf<AgentSession?>(null) }
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

    // 当前对话是空的就不再开一个，免得连点出一串空会话。
    fun startNewChat() {
        if (vm.messages.isNotEmpty()) vm.newSession()
        drawerOpen = false
    }

    // 顶栏：左边历史对话，右边新对话与设置。两种宿主形态共用同一份，tab 模式下交给 MainScreen 的 TopAppBar。
    // 这些 lambda 捕获的是 showConfig / drawerOpen 的委托属性，状态变化时读它们的宿主会自己重组。
    // 设置页打开时宿主顶栏被盖住（没有 [overlays] 的端盖不住，所以回调里也要拦）。
    // 宽屏的历史对话常驻左栏，不送开关按钮。
    val headerNavIcon: (@Composable () -> Unit)? = if (isWide) null else {
        {
            IconButton(onClick = { if (!showConfig) drawerOpen = !drawerOpen }) {
                Icon(Icons.Default.History, contentDescription = if (drawerOpen) "收起历史对话" else "历史对话")
            }
        }
    }
    val headerActions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { if (!showConfig) startNewChat() }) {
            Icon(Icons.Default.Add, contentDescription = "新对话")
        }
        IconButton(onClick = {
            drawerOpen = false
            showConfig = true
        }) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }

    // 标题是助手名（用户可改名），副标题是当前对话。
    val hostTitle = config.effectiveName
    LaunchedEffect(hostTitle) { onTitleChange(hostTitle) }
    val hostSubtitle = vm.sessions.firstOrNull { it.id == vm.currentSessionId }?.title.orEmpty()
    LaunchedEffect(hostSubtitle) { onSubtitleChange(hostSubtitle) }
    DisposableEffect(Unit) {
        onActionsChange(headerActions)
        onNavIconChange(headerNavIcon)
        // 切走时把按钮撤掉，否则别的 tab 顶栏上会残留屁岱的图标。
        onDispose {
            onActionsChange(null)
            onNavIconChange(null)
        }
    }

    // 全屏层：窄屏的历史对话侧栏 + 设置二级页。
    val overlayContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            if (!isWide) SessionDrawer(
                open = drawerOpen,
                sessions = vm.sessions,
                currentId = vm.currentSessionId,
                onClose = { drawerOpen = false },
                onNew = ::startNewChat,
                onSelect = { vm.switchSession(it); drawerOpen = false },
                // 对话框在 Scaffold 的弹窗宿主里，侧栏盖在它上面，先收起来
                onLongPress = { actionTarget = it; drawerOpen = false },
            )
            AnimatedVisibility(
                visible = showConfig,
                enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(180)),
            ) {
                AgentSettingsPage(
                    config = config,
                    host = host,
                    appearanceSection = appearanceSection,
                    apiKeySection = apiKeySection,
                    memorySection = memorySection,
                    onBack = { showConfig = false },
                    onSave = { newConfig ->
                        // 即改即存：每次配置变动立即写入，不必等到用户点「保存」
                        host.saveConfig(newConfig)
                        // 「这一端配没配 Key」是宿主提供的一格（浏览器上 Key 在 serve 侧，本端拿不到）：
                        // 面板自己拼的那份不带它 ⇒ 保存后按宿主那份补回来，否则页面会当「没配」显示
                        config = newConfig.copy(
                            keyConfigured = newConfig.keyConfigured || host.loadConfig().keyConfigured,
                        )
                    },
                )
            }
        }
    }
    if (overlays != null) {
        SideEffect { overlays.content = overlayContent }
        DisposableEffect(overlays) { onDispose { overlays.content = null } }
    }

    // 主体（对话面板 + 各个对话框）。两种宿主形态完全一致，
    // 差别只在谁提供 padding：tab 模式下宿主 Scaffold 已经把底栏高度扣掉了。
    val body: @Composable (PaddingValues) -> Unit = { padding ->
            ChatPanel(
                vm = vm,
                config = config,
                host = host,
                imagePicker = imagePicker,
                gapNote = host.gapNote,
                hero = hero,
                pokeWhisper = pokeWhisper,
                padding = padding,
                listTopPadding = contentTopPadding,
                scrollBehavior = effectiveScrollBehavior,
                onNavigate = onNavigate,
                onOpenConfig = { showConfig = true },
                bottomReserve = extraBottomPadding,
            )

            // OverlayDialog 必须在 Scaffold content 里：靠 Scaffold 提供的弹窗宿主渲染。
            // 写在 Scaffold 外面会注册进空列表，点了没反应。
            actionTarget?.let { target ->
                OverlayDialog(
                    show = true,
                    title = target.title,
                    onDismissRequest = { actionTarget = null },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "重命名",
                            onClick = { renameTarget = target; actionTarget = null },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            text = "删除",
                            onClick = { deleteTarget = target; actionTarget = null },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            text = "取消",
                            onClick = { actionTarget = null },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            renameTarget?.let { target ->
                var name by remember(target.id) { mutableStateOf(target.title) }
                OverlayDialog(
                    show = true,
                    title = "重命名对话",
                    onDismissRequest = { renameTarget = null },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = "对话标题",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(
                                text = "取消",
                                onClick = { renameTarget = null },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "确定",
                                onClick = {
                                    vm.renameSession(target.id, name)
                                    renameTarget = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }

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
                    summary = AgentChatSession.CONTEXT_EXHAUSTED_MESSAGE + "\n\n继续累积可能导致 AI 回复变慢或回答失准。",
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
                    onNew = ::startNewChat,
                    onSelect = { vm.switchSession(it) },
                    onLongPress = { actionTarget = it },
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

        // 宿主没给全屏层的槽位（桌面、浏览器）就盖在本页内容区上。
        if (overlays == null) overlayContent()
    }
}

/** 宿主的全屏层槽位：[AgentScreen] 把侧栏和设置页放进来，宿主画在最外层，盖住顶栏和底栏。 */
class AgentOverlays {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        internal set

    @Composable
    fun Content() {
        content?.invoke()
    }
}

/** 远程工具参数里疑似的个人信息，只用来在确认框里提醒，不拦截。 */
internal fun personalDataHints(arguments: String): List<String> = listOfNotNull(
    "手机号".takeIf { Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""").containsMatchIn(arguments) },
    "身份证号".takeIf { Regex("""(?<!\d)\d{17}[\dXx](?!\d)""").containsMatchIn(arguments) },
    // 10 位、以年级开头；秒级时间戳以 17 开头，不会误报
    "学号".takeIf { Regex("""(?<!\d)[2-4]\d{9}(?!\d)""").containsMatchIn(arguments) },
)
