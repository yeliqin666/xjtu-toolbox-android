package com.xjtu.toolbox.community

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 社区：本仓库的 GitHub Discussions。必须登录 GitHub 才能看，App 里不内置任何 token。
 *
 * 列表 / 详情 / 发帖 / 登录都在这一个路由里切换，返回键逐层退回。
 */
@Composable
fun CommunityScreen(onBack: () -> Unit, onOpenLegacyFeedback: () -> Unit) {
    // MIUIX 的 OverlayDialog 只在某个 Scaffold 底下才渲染得出来：账号弹窗、删除确认、放弃编辑这些
    // 都写在各页 Scaffold 的外面，这一层外壳给它们兜底，弹窗也就铺满全屏
    Scaffold { _ -> CommunityContent(onBack, onOpenLegacyFeedback) }
}

@Composable
private fun CommunityContent(onBack: () -> Unit, onOpenLegacyFeedback: () -> Unit) {
    val context = LocalContext.current
    val session = remember { GithubSession.get(context) }
    val login by session.login.collectAsStateWithLifecycle()
    // 登录 / 退出时两套界面淡入淡出地换
    Crossfade(targetState = login, animationSpec = tween(300), label = "communityLogin") { signedIn ->
        if (signedIn == null) {
            CommunityLoginScreen(session, onBack, onOpenLegacyFeedback)
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
            summary = "已登录为 @$signedIn。退出只清除本机保存的授权；要彻底撤销，去 GitHub 设置 → Applications 里删掉授权。",
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
private fun CommunityLoginScreen(session: GithubSession, onBack: () -> Unit, onOpenLegacyFeedback: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val auth = remember { GithubOAuthDeviceAuthRepository(GithubNetwork.client, CommunityRepo.CLIENT_ID) }
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
                "提建议、报问题、交流心得都在这里。社区搭在 GitHub Discussions 上，看帖和发帖都要登录 GitHub 账号。",
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            when {
                !auth.isConfigured -> Text("社区登录还没配置好，敬请期待。", color = colors.error, style = MiuixTheme.textStyles.body2)
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
                                copyToClipboard(context, pending.userCode)
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

/** 旧的页内反馈（飞书多维表格）挪到社区里当二级入口，准备停用。 */
@Composable
internal fun LegacyFeedbackEntry(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("旧版反馈", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                Text(
                    "不用登录的页内反馈，即将停用，建议改到社区发帖",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            CommunityTag("即将停用", MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub 验证码", text))
}
