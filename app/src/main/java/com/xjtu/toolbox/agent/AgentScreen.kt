@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStoreOwner
import com.xjtu.toolbox.LocalAppLoginState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 屁岱主界面。
 *
 * 两种宿主形态：
 * - [asTab] = true：作为底栏第三个 0 级 tab 嵌在 MainScreen 的 Scaffold 里。
 *   自己**不**渲染 Scaffold / TopAppBar / 返回箭头——顶栏归宿主，标题和
 *   「会话列表 / 设置」两个按钮通过 [onTitleChange] / [onActionsChange] 反向送上去。
 * - [asTab] = false：独立整页（保留这条路是为了将来可能的独立入口），自带 Scaffold 与返回。
 *
 * 内容主体两种形态共用，靠一个 body lambda 复用，不复制两份。
 */
@Composable
fun AgentScreen(
    onBack: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    asTab: Boolean = false,
    /** tab 模式下需要额外空出的底部高度（悬浮胶囊底栏不占宿主 contentPadding，见调用处）。 */
    extraBottomPadding: Dp = 0.dp,
    /** 宿主 Scaffold 的 contentPadding 底部值。输入栏算自己的让位时要把它减掉，见 AgentComposer。 */
    hostBottomPadding: Dp = 0.dp,
    scrollBehavior: ScrollBehavior? = null,
    onTitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable RowScope.() -> Unit)?) -> Unit = {},
    onNavIconChange: ((@Composable () -> Unit)?) -> Unit = {},
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
    var deleteTarget by remember { mutableStateOf<AgentSession?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) keyboard?.hide()
    }

    // 宿主给了就用宿主的（顶栏在宿主那儿，折叠状态必须共用同一份），没给才自己建。
    val ownScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val effectiveScrollBehavior = scrollBehavior ?: ownScrollBehavior
    BackHandler(enabled = showConfig || drawerOpen || showContextExhaustedDialog) {
        when {
            showContextExhaustedDialog -> showContextExhaustedDialog = false
            drawerOpen -> drawerOpen = false
            else -> showConfig = false
        }
    }

    // 顶栏只留两个按钮：会话列表（含新建/改名/删除）与设置。
    // 两种宿主形态共用同一份，tab 模式下把它整个交给 MainScreen 的 TopAppBar。
    //
    // 这个 lambda 闭包捕获的是 showConfig / drawerOpen 的**委托属性**而不是取到的值，
    // 所以状态变化时读它的宿主会自己重组，不需要在 key 上列出来。
    // 会话列表放最左边：抽屉从左边滑出，触发它的按钮就该在左边。
    val headerNavIcon: @Composable () -> Unit = {
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

    if (asTab) {
        // 标题跟着助手名字走（用户可改名），进配置面板时换成「配置」。
        val hostTitle = if (showConfig) "配置" else config.effectiveName
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
                            .padding(padding)
                    )
                } else {
                    ChatPanel(
                        vm = vm,
                        config = config,
                        loginState = loginState,
                        padding = padding,
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
        if (asTab) {
            // 把"宿主已经让出底部 hostBottomPadding"这件事声明出来。
            // 宿主 Scaffold 只 padding(padding)、没 consumeWindowInsets(padding)，
            // 消费链是断的；在这里补一次，子树里的 windowInsetsPadding（输入栏那处）
            // 才能正确扣除已让的部分，不至于把 ime 再整段加一遍。
            // 只包住屁岱，其它 tab 的 inset 行为不受影响。
            Box(
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(PaddingValues(bottom = hostBottomPadding))
            ) {
                body(PaddingValues())
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = config.effectiveName,
                        largeTitle = if (showConfig) "配置" else config.effectiveName,
                        color = MiuixTheme.colorScheme.surface,
                        scrollBehavior = effectiveScrollBehavior,
                        navigationIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    if (showConfig) showConfig = false else onBack()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                                headerNavIcon()
                            }
                        },
                        actions = headerActions,
                    )
                }
            ) { padding -> body(padding) }
        }

        SessionDrawer(
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
        )
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

@Composable
private fun SessionDrawer(
    open: Boolean,
    sessions: List<AgentSession>,
    currentId: String?,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onRequestDelete: (AgentSession) -> Unit,
) {
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
    // 左侧抽屉面板。右侧收圆角（是盖在页面上的一层，不是把页面裁掉一条）；
    // 「新建对话」用整宽按钮而不是小加号；会话条目默认无底，只有当前会话有浅底 + 竖条。
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(animationSpec = tween(260)) { -it },
        exit = slideOutHorizontally(animationSpec = tween(220)) { -it },
        modifier = Modifier.zIndex(2f)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(304.dp),
            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            color = MiuixTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // 不加 statusBarsPadding：抽屉现在活在 Scaffold 的内容区里，
                    // 顶栏已经把状态栏那段让开了，再加一次就是在「对话」上面白白空出
                    // 一整条状态栏的高度——那就是之前看着头重脚轻的原因。
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "对话",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (sessions.isNotEmpty()) {
                        Text(
                            sessions.size.toString(),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable(onClick = onNew)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "新建对话",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (sessions.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "还没有任何对话",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点上面「新建对话」开始",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            isCurrent = session.id == currentId,
                            onSelect = { onSelect(session.id) },
                            onRequestDelete = { onRequestDelete(session) },
                        )
                    }
                }
            }
        }
    }

    // 删除确认改由外层 OverlayDialog 处理，保证走 MIUIX 弹窗宿主。
}

/** 抽屉里的一条会话。当前会话靠「左侧主色竖条 + 浅色底」区分，其余保持无底。 */
@Composable
private fun SessionRow(
    session: AgentSession,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (isCurrent) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        animationSpec = tween(200),
        label = "sessionRowBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (isCurrent) MiuixTheme.colorScheme.primary else Color.Transparent
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatSessionTime(session.updatedAt),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onRequestDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                // 删除是破坏性动作但不是主要动作：默认压得很淡，需要时找得到就行。
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp),
            )
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
    bottomReserve: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var editingLast by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun send() {
        val text = input.trim()
        if (text.isBlank() || vm.contextExhausted) return
        // 没有 key 时点发送，直接把人送到配置页，而不是让消息石沉大海
        if (!config.isConfigured) { onOpenConfig(); return }
        input = ""
        keyboard?.hide()
        if (editingLast) {
            editingLast = false
            vm.replaceLastUserAndSend(text, config, loginState, context)
        } else {
            vm.sendMessage(text, config, loginState, context)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            // 只吃顶部（TopAppBar 高度）；底部由输入栏自己的 navigationBarsPadding + imePadding 处理，
            // 否则会和输入栏的 inset 双重叠加，键盘弹出时把输入框顶飞。
            .padding(top = padding.calculateTopPadding())
            .background(MiuixTheme.colorScheme.surface)
    ) {
        Box(Modifier.weight(1f).clipToBounds()) {
            key(vm.currentSessionId) {
                val listState = rememberLazyListState()
                val chatRows = groupAgentRows(vm.messages)
                LaunchedEffect(chatRows.size, vm.isLoading) {
                    if (chatRows.isNotEmpty()) listState.scrollToItem(chatRows.lastIndex)
                }
                val lastUser = vm.messages.lastOrNull { it.role == "user" }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
            if (vm.messages.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    ) {
                        Text(
                            "你好",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "课表、空教室、成绩，直接问就行。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(22.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                // 「去哪自习」放第一个：它是这里唯一需要**综合**几个来源才能答的问题
                                // （空教室 + 图书馆座位 + 当前时段 + 你下节课在哪），
                                // 也最能说明这个助手和一个查询入口的区别。
                                "现在想找个地方自习，去哪合适？",
                                "这周考试",
                                "明天空教室",
                                "最近成绩",
                                "校园卡余额",
                            ).forEach { q ->
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MiuixTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        input = q
                                    },
                                ) {
                                    Text(
                                        q,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            items(chatRows, key = { it.key }) { row ->
                when (row) {
                    is AgentRow.User -> MessageBubble(
                        msg = row.msg,
                        showReasoning = config.showReasoning,
                        onNavigate = onNavigate,
                        canEdit = !vm.isLoading && row.msg === lastUser,
                        onEdit = {
                            input = row.msg.content
                            editingLast = true
                        },
                    )
                    is AgentRow.Tools -> Box(Modifier.padding(horizontal = 8.dp)) {
                        ToolCallNote(row.events)
                    }
                    is AgentRow.Assistant -> MessageBubble(
                        msg = row.msg,
                        showReasoning = config.showReasoning,
                        onNavigate = onNavigate,
                        toolEvents = row.tools,
                    )
                }
            }
            val last = vm.messages.lastOrNull()
            val showThinking = vm.isLoading && when {
                last == null || last.role == "user" -> true
                isToolRole(last.role) -> false
                last.content.isBlank() && last.reasoningContent.isBlank() -> true
                else -> false
            }
            if (showThinking) {
                item {
                    ReasoningBar(text = "", streaming = true)
                }
            }
                }
            }
        }

        // 未配置模型时的提示条。放在输入栏正上方而不是弹窗或整页表单，
        // 是因为它要解释「为什么现在发不出去」，紧挨着发送动作才说得通。
        if (!config.isConfigured) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clickable { onOpenConfig() },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "先填一下模型",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "API Key 配好就能聊",
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

        if (editingLast) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "改上一条",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.clickable {
                        editingLast = false
                        input = ""
                    },
                )
            }
        }
        AgentComposer(
            input = input,
            onInputChange = { input = it },
            onSend = { send() },
            onStop = { vm.stop() },
            isLoading = vm.isLoading,
            contextExhausted = vm.contextExhausted,
            bottomReserve = bottomReserve,
        )
    }
}

/**
 * 对话输入栏：输入区和发送键同处一个圆角胶囊，发送键是容器内的实心圆。
 * 用 BasicTextField 自己画占位符，不用 miuix TextField——那是带浮动 label 的表单控件。
 */
@Composable
private fun AgentComposer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    contextExhausted: Boolean,
    bottomReserve: Dp,
) {
    val canSend = input.isNotBlank() && !contextExhausted
    val actionEnabled = isLoading || canSend
    var focused by remember { mutableStateOf(false) }

    // 聚焦时描边亮起来。这是唯一的状态反馈，所以别做得太隐晦，也别做成整框变色。
    val borderColor by animateColorAsState(
        targetValue = when {
            contextExhausted -> MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
            focused -> MiuixTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
        },
        animationSpec = tween(180),
        label = "composerBorder",
    )
    val sendBg by animateColorAsState(
        targetValue = if (actionEnabled) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        animationSpec = tween(180),
        label = "composerSendBg",
    )

    // ── 底部让位 ──
    //
    // 手算 inset 是错的路，之前两版都栽在这儿：先是 navigationBarsPadding + imePadding +
    // reserve 三段相加把输入框顶到屏幕中间；改成手动取最大值后，又因为没扣掉宿主 Scaffold
    // 已经为底栏让出的那段而多空 90dp；手动减一下，数又对不上。
    //
    // 根因是 `WindowInsets.ime.getBottom()` 拿的是**相对整块屏幕**的原始值，
    // 完全不知道祖先已经让过多少。正确做法是走 `windowInsetsPadding`，它会自动扣除
    // 已被消费的部分——前提是有人把"我已经让过了"声明出来。宿主只做了 padding(padding)
    // 没做 consumeWindowInsets，所以这里在屁岱自己的子树里补一次声明（见 body 的调用处），
    // 消费链建立后下面这一行就成立了。作用域只在屁岱内，不影响其它 tab。
    //
    // union 取各边最大值、add 取和，于是：
    // - 键盘弹起：ime 远大于 nav+reserve，取 ime（reserve 此时被键盘盖住，本就不该再让）
    // - 键盘收起：取 nav + 悬浮胶囊底栏的预留高度
    // 全程由同一个表达式给出，没有分支，也就没有收键盘时"先掉到底再跳回来"的跳变。
    val bottomInsets = WindowInsets.ime.union(
        WindowInsets.navigationBars.add(WindowInsets(bottom = bottomReserve))
    )

    Box(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(bottomInsets)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    // 单行时和右侧 36dp 发送圆对齐；多行时自然往上长。
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !contextExhausted,
                    textStyle = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .onFocusChanged { focused = it.isFocused },
                )
                if (input.isEmpty()) {
                    Text(
                        if (contextExhausted) "这轮对话满了，新建一个吧" else "问一句…",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .clickable(enabled = actionEnabled) { if (isLoading) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isLoading) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isLoading) "停止" else "发送",
                    tint = if (actionEnabled) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
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

private fun isToolRole(role: String) = role == "tool_event"

private sealed class AgentRow {
    abstract val key: String
    data class User(val msg: ChatMessage, override val key: String) : AgentRow()
    data class Tools(val events: List<ChatMessage>, override val key: String) : AgentRow()
    data class Assistant(
        val msg: ChatMessage,
        override val key: String,
        val tools: List<ChatMessage> = emptyList(),
    ) : AgentRow()
}

/** 一轮里的调用记录挂到该回答末尾；结果小卡仍在正文里。 */
private fun groupAgentRows(messages: List<ChatMessage>): List<AgentRow> {
    val out = mutableListOf<AgentRow>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (m.role == "user") {
            out += AgentRow.User(m, "u-${m.timestamp}-$i")
            i++
            continue
        }
        val start = i
        val tools = mutableListOf<ChatMessage>()
        val block = mutableListOf<ChatMessage>()
        while (i < messages.size && messages[i].role != "user") {
            if (isToolRole(messages[i].role)) tools += messages[i]
            else block += messages[i]
            i++
        }
        val allWidgets = block.flatMap { it.widgets }
        val allNav = block.flatMap { it.navSuggestions }.distinctBy { it.second }
        val assistants = mutableListOf<AgentRow.Assistant>()
        block.forEachIndexed { idx, a ->
            val last = idx == block.lastIndex
            val widgets = if (last) allWidgets else emptyList()
            val nav = if (last) allNav else emptyList()
            val visible = a.content.isNotBlank() ||
                a.reasoningContent.isNotBlank() ||
                (last && (widgets.isNotEmpty() || nav.isNotEmpty()))
            if (visible) {
                assistants += AgentRow.Assistant(
                    a.copy(widgets = widgets, navSuggestions = nav),
                    "a-${a.timestamp}-$start-$idx",
                )
            }
        }
        if (assistants.isEmpty() && block.isNotEmpty() && (allWidgets.isNotEmpty() || allNav.isNotEmpty())) {
            val last = block.last()
            assistants += AgentRow.Assistant(
                last.copy(widgets = allWidgets, navSuggestions = allNav),
                "a-w-${last.timestamp}-$start",
            )
        }
        if (assistants.isNotEmpty()) {
            val last = assistants.last()
            assistants[assistants.lastIndex] = last.copy(tools = tools)
            out += assistants
        } else if (tools.isNotEmpty()) {
            out += AgentRow.Tools(tools, "t-${tools.first().timestamp}-$start")
        }
    }
    return out
}

@Composable
private fun ReasoningBar(
    text: String,
    streaming: Boolean = false,
    onNavigate: (String) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(max = 340.dp)
            .then(
                if (text.isNotBlank()) Modifier.clickable { expanded = !expanded }
                else Modifier
            ),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "思考",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                )
                when {
                    streaming && text.isBlank() -> ThinkingDots()
                    !expanded && text.isNotBlank() -> Text(
                        text.replace(Regex("\\s+"), " ").trim(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    else -> Spacer(Modifier.weight(1f))
                }
                if (text.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (expanded && text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                MarkdownText(
                    text = text,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    onLink = { url ->
                        onNavigate("browser?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                    },
                )
            }
        }
    }
}

private fun toolLabel(msg: ChatMessage) = msg.content.removeSuffix("…").trim()

@Composable
private fun ToolCallNote(events: List<ChatMessage>) {
    if (events.isEmpty()) return
    val running = events.any { it.isToolCall }
    val hasError = events.any { it.toolError != null }
    var expanded by remember(events.first().timestamp) { mutableStateOf(false) }
    val labels = events.map { toolLabel(it) }.filter { it.isNotBlank() }
    val summary = when {
        running -> labels.lastOrNull().orEmpty().ifBlank { "调用中" }
        hasError -> labels.joinToString(" · ").ifBlank { "调用失败" }
        else -> labels.joinToString(" · ")
    }
    val muted = if (hasError) MiuixTheme.colorScheme.error.copy(alpha = 0.85f)
    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f)
    val canExpand = events.size > 1 || hasError || events.any { it.toolError != null }
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            modifier = if (canExpand) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded } else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (running) CircularProgressIndicator(size = 10.dp, strokeWidth = 1.5.dp)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote2,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(visible = expanded && canExpand) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                events.forEach { event ->
                    val err = event.toolError
                    Text(
                        if (err != null) "${toolLabel(event)} · $err" else toolLabel(event),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (err != null) MiuixTheme.colorScheme.error.copy(alpha = 0.85f) else muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    showReasoning: Boolean,
    onNavigate: (String) -> Unit,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    toolEvents: List<ChatMessage> = emptyList(),
) {
    // [LocalClipboard] 取代已废弃的 [LocalClipboardManager]：suspend setClip，跨进程兼容。
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun copyText(text: String) {
        coroutineScope.launch {
            clipboard.setClipEntry(
                androidx.compose.ui.platform.ClipEntry(
                    android.content.ClipData.newPlainText("message", text)
                )
            )
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
    when (msg.role) {
        "user" -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .then(
                            if (canEdit) Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onEdit,
                            ) else Modifier
                        ),
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
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (showReasoning && msg.reasoningContent.isNotBlank()) {
                    ReasoningBar(text = msg.reasoningContent, onNavigate = onNavigate)
                    Spacer(Modifier.height(6.dp))
                }
                if (msg.content.isNotBlank()) {
                    MarkdownText(
                        text = msg.content,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { copyText(msg.content) },
                            ),
                        onLink = { url ->
                            onNavigate("browser?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                        }
                    )
                }
                msg.widgets.forEach { widget ->
                    AgentWidgetView(
                        widget,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (msg.content.isNotBlank()) 10.dp else 0.dp),
                    )
                }
                if (msg.navSuggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        msg.navSuggestions.forEach { (label, route) ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MiuixTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { onNavigate(route) }
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (toolEvents.isNotEmpty()) {
                    ToolCallNote(toolEvents)
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
            // 记住的偏好必须**可见可删**：模型往本机写了东西，用户有权知道写了什么。
            // 放在能力开关上面，因为看见内容才谈得上决定要不要关掉这个能力。
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var memories by remember { mutableStateOf(AgentMemory.all(ctx)) }
            if (memories.isNotEmpty()) {
                Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("记住的偏好", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                        Text(
                            "只存在这台设备上，不上传。最多 ${AgentMemory.MAX_ITEMS} 条。",
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
        item {
            val capabilities = listOf(
                "schedule" to "课表、校历、全校课程与空教室",
                "grades" to "成绩",
                "attendance" to "考勤",
                "card" to "校园卡",
                "notifications" to "通知公告",
                "yellow_page" to "校园黄页",
                "faculty" to "教师主页",
                "memory" to "记住我的偏好",
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
