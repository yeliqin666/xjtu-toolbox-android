package com.xjtu.toolbox.community

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.ui.components.AppCardColor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.material.icons.filled.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
* 社区：本仓库的 GitHub Discussions。必须登录 GitHub 才能看，App 里不内置任何 token。
*
* 列表 / 详情 / 发帖 / 登录都在这一个路由里切换，返回键逐层退回。
*/
@Composable
fun CommunityScreen(
    session: GithubSession,
    /** 设备码登录的实现（okhttp，留在 :app）。由导航层注入，界面不再自己造。 */
    deviceAuth: GithubDeviceAuthRepository,
    onBack: () -> Unit,
    onOpenLegacyFeedback: () -> Unit,
) {
    // MIUIX 的 OverlayDialog 只在某个 Scaffold 底下才渲染得出来：账号弹窗、删除确认、放弃编辑这些
    // 都写在各页 Scaffold 的外面，这一层外壳给它们兜底，弹窗也就铺满全屏
    Scaffold { _ -> CommunityContent(session, deviceAuth, onBack, onOpenLegacyFeedback) }
}

@Composable
private fun CommunityContent(
    session: GithubSession,
    deviceAuth: GithubDeviceAuthRepository,
    onBack: () -> Unit,
    onOpenLegacyFeedback: () -> Unit,
) {
    val login by session.login.collectAsStateWithLifecycle()
    // 登录 / 退出时两套界面淡入淡出地换
    Crossfade(targetState = login, animationSpec = tween(300), label = "communityLogin") { signedIn ->
        if (signedIn == null) {
            CommunityLoginScreen(session, deviceAuth, onBack, onOpenLegacyFeedback)
        } else {
            CommunityForum(session, signedIn, onBack, onOpenLegacyFeedback)
        }
    }
}

/** 社区里的三种页面；层级决定转场方向。 */
private sealed interface ForumPage {
    val depth: Int
    data object Home : ForumPage { override val depth = 0 }
    data object Compose : ForumPage { override val depth = 1 }
    data class Detail(val discussion: GithubDiscussion) : ForumPage { override val depth = 1 }
}

@Composable
private fun CommunityForum(
    session: GithubSession,
    signedIn: String,
    onBack: () -> Unit,
    onOpenLegacyFeedback: () -> Unit,
) {
    val repo = session.repository
    val vm: DiscussionsViewModel = viewModel(key = "community-$signedIn") {
        // miuix-nav 的页面没有 SavedStateRegistryOwner，不能 createSavedStateHandle()（会直接崩），草稿只放内存
        DiscussionsViewModel(CommunityRepo.OWNER, CommunityRepo.NAME, repo)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<GithubDiscussion?>(null) }
    var accountDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    // 发帖成功直接进新帖
    LaunchedEffect(state.created) {
        state.created?.let { open = it; vm.consumeCreated() }
    }

    val page: ForumPage = when {
        open != null -> ForumPage.Detail(open!!)
        state.composing -> ForumPage.Compose
        else -> ForumPage.Home
    }
    AnimatedContent(
        targetState = page,
        // 同一篇帖子的内容更新（点赞数之类）不算换页，不要重播转场
        contentKey = { if (it is ForumPage.Detail) "detail:${it.discussion.id}" else it.toString() },
        transitionSpec = { communitySlide(forward = targetState.depth >= initialState.depth) },
        label = "communityPage",
    ) { shown ->
        when (shown) {
            is ForumPage.Detail -> {
                BackHandler { open = null }
                DiscussionDetailScreen(
                    initial = shown.discussion,
                    repo = repo,
                    onChanged = vm::replace,
                    onDeleted = { vm.remove(shown.discussion.id); open = null },
                    onBack = { open = null },
                )
            }
            ForumPage.Compose -> {
                BackHandler { if (!state.submitting) vm.setComposing(false) }
                DiscussionComposer(
                    state = state,
                    onEdit = vm::edit,
                    onSubmit = vm::create,
                    onBack = { vm.setComposing(false) },
                    onRetryCategories = vm::loadCategories,
                )
            }
            ForumPage.Home -> DiscussionsScreen(
                state = state,
                onBack = onBack,
                onRefresh = { vm.load() },
                onLoadMore = { vm.load(more = true) },
                onCreate = { vm.setComposing(true) },
                onOpen = { open = it },
                onFilter = vm::selectFilter,
                onAccount = { accountDialog = true },
                onLegacyFeedback = onOpenLegacyFeedback,
            )
        }
    }

    if (accountDialog) {
        OverlayDialog(
            show = true,
            title = "GitHub 账号",
            summary = "已登录 @$signedIn。退出只清除本机授权；彻底撤销请到 GitHub 设置 → Applications。",
            onDismissRequest = { accountDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "在浏览器打开讨论区",
                    onClick = { accountDialog = false; uriHandler.openUri(CommunityRepo.WEB_URL) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    TextButton(text = "取消", onClick = { accountDialog = false }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "退出登录",
                        onClick = { accountDialog = false; session.signOut() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/** 设备码登录：拿到一次性验证码，用户去 github.com/login/device 输入，这边轮询等授权。 */
@Composable
private fun CommunityLoginScreen(
    session: GithubSession,
    auth: GithubDeviceAuthRepository,
    onBack: () -> Unit,
    onOpenLegacyFeedback: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // Compose 的多平台剪切板（Android 上它就是平台 ClipboardManager），所以这一屏不必再要 Context。
    // 用的是已标 deprecated 的 LocalClipboardManager：新的 LocalClipboard 要平台自己的 ClipEntry，
    // 把“复制一段文本”这件小事写成了三端各一份 —— 等它在所有端都好用了再迁。
    val clipboard = LocalClipboardManager.current
    var authorization by remember { mutableStateOf<GithubDeviceAuthorization?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val colors = MiuixTheme.colorScheme

    val pending = authorization
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        AwaitGithubDeviceAuthorizationUseCase(auth)(pending).fold(
            onSuccess = { token ->
                session.signIn(token).onFailure { message = "授权成功了，但读取 GitHub 账号失败，请重试。" }
            },
            onFailure = { error ->
                message = when (error) {
                    is GithubDeviceAuthorizationDeniedException -> "你在 GitHub 上拒绝了授权。"
                    is GithubDeviceAuthorizationExpiredException -> "验证码过期了，请重新获取。"
                    else -> "连不上 GitHub，检查网络后重试。"
                }
            },
        )
        authorization = null
    }
    if (pending != null) BackHandler { authorization = null }

    CommunityPage(title = "社区", onBack = onBack) { top ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = top + 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.Forum, null, tint = colors.primary, modifier = Modifier.size(56.dp))
            Text("岱宗盒子社区", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(
                "提建议、报问题、交流心得。社区搭在 GitHub 上，看帖发帖需登录 GitHub。",
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            when {
                !auth.isConfigured && !auth.supportsManualToken -> Text("社区登录还没配置好，敬请期待。", color = colors.error, style = MiuixTheme.textStyles.body2)
                // 走不通设备码流程的端（Web）：同一个登录页多一条「粘贴 token」的路，
                // 而不是在那一端另写一个登录界面 —— 用户看到的都是「社区 → 登录」。
                auth.supportsManualToken && pending == null -> ManualTokenLogin(
                    auth = auth,
                    busy = busy,
                    onBusyChange = { busy = it },
                    onMessage = { message = it },
                )
                pending == null -> Button(
                    onClick = {
                        busy = true; message = null
                        scope.launch {
                            auth.start().fold(
                                onSuccess = { authorization = it },
                                onFailure = { message = "连不上 GitHub，检查网络后重试。" },
                            )
                            busy = false
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(size = 18.dp) else Text("用 GitHub 登录", color = colors.onPrimary)
                }
                else -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(16.dp),
                    colors = CardDefaults.defaultColors(color = AppCardColor),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("在 GitHub 页面输入这个验证码", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                        Text(
                            pending.userCode,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            letterSpacing = 3.sp,
                        )
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(pending.userCode))
                                uriHandler.openUri(pending.verificationUri)
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("复制验证码并打开 GitHub", color = colors.onPrimary) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(size = 14.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("授权完成后会自动登录", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
                        }
                        TextButton(text = "取消", onClick = { authorization = null })
                    }
                }
            }
            message?.let { Text(it, color = colors.error, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center) }
            TextButton(text = "没有 GitHub 账号？免费注册一个", onClick = { uriHandler.openUri("https://github.com/signup") })
            Spacer(Modifier.height(8.dp))
            LegacyFeedbackEntry(onOpenLegacyFeedback)
        }
    }
}

/**
 * 「粘贴 token」登录 —— 只有 [GithubDeviceAuthRepository.supportsManualToken] 的端会走到
 *（目前是 Web：浏览器直连 `github.com/login/device` 拿不到 CORS 头）。
 *
 * `:app` 不实现那条能力（默认 false），所以 Android 端的登录页与行为零变化。
 * 成败判据与设备码那条路完全一致：`signInWithToken` 内部先用 token 查一次用户名。
 */
@Composable
private fun ManualTokenLogin(
    auth: GithubDeviceAuthRepository,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onMessage: (String?) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    var text by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "浏览器里走不通 GitHub 的设备码流程（那边不给跨源响应）。粘贴一个带 discussions 写权限的 token 即可登录，它只存在这台设备的浏览器里。",
            style = MiuixTheme.textStyles.body2,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        TextField(
            value = text,
            onValueChange = { text = it },
            label = "GitHub token",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val raw = text.trim()
                if (raw.isEmpty()) {
                    onMessage("先粘贴一个 token。")
                } else {
                    onBusyChange(true)
                    onMessage(null)
                    scope.launch {
                        auth.signInWithToken(raw).fold(
                            onSuccess = { onMessage(null) },
                            onFailure = { onMessage("这个 token 用不了，检查是否复制完整、是否有 discussions 写权限。") },
                        )
                        onBusyChange(false)
                    }
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(size = 18.dp) else Text("用 token 登录", color = colors.onPrimary)
        }
        TextButton(
            text = "去 GitHub 创建一个 token",
            onClick = { uriHandler.openUri(GITHUB_NEW_TOKEN_URL) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 经典 token 创建页，预勾「公开仓库读写」（`public_repo`）与「讨论区」（`write:discussion`）。 */
private const val GITHUB_NEW_TOKEN_URL =
    "https://github.com/settings/tokens/new?scopes=public_repo,write:discussion&description=XJTU%20Toolbox%20community"

/** 旧的页内反馈（飞书多维表格）挪到社区里当二级入口，准备停用。 */
@Composable
fun LegacyFeedbackEntry(onClick: () -> Unit) =
    com.xjtu.toolbox.ui.components.SecondaryEntry(
        androidx.compose.material.icons.Icons.Default.Info, MiuixTheme.colorScheme.onSurfaceVariantSummary,
        "旧版反馈", "不用登录的页内反馈，建议改到社区发帖", status = "即将停用", onClick = onClick,
    )

