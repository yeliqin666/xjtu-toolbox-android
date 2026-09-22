package com.xjtu.toolbox

import androidx.compose.ui.graphics.graphicsLayer
import com.xjtu.toolbox.ui.components.enterOnce
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.launch

// ══════════════════════════════════════════
//  Tab 4 — 我的（含统一登录）
// ══════════════════════════════════════════

/**
 * 学籍档案卡。数据来自 hello.xjtu.edu.cn，字段缺失时整行不渲染——
 * 宁可少一行，也不要出现"专业：—"这种占位。
 */
@Composable
private fun ProfileInfoCard(p: com.xjtu.toolbox.hello.HelloProfile) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeSectionHeader("学籍信息", Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(22.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))

                    val rows = buildList {
                        p.departmentName.takeIf { it.isNotBlank() }?.let { add("学院" to it) }
                        p.academyName.takeIf { it.isNotBlank() }?.let { add("书院" to it) }
                        p.professionName.takeIf { it.isNotBlank() }?.let { add("专业" to it) }
                        p.className.takeIf { it.isNotBlank() }?.let { add("班级" to it) }
                        p.campusName.takeIf { it.isNotBlank() }?.let { add("校区" to it) }
                        if (p.grade > 0) {
                            val len = if (p.schoolingLen > 0) "（学制 ${p.schoolingLen} 年）" else ""
                            add("年级" to "${p.grade} 级$len")
                        }
                        p.enterSchoolDate.takeIf { it.isNotBlank() }?.let { add("入学" to it) }
                        p.cardId.takeIf { it.isNotBlank() }?.let { add("校园卡号" to it) }
                    }
                    rows.forEachIndexed { index, (label, value) ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                label,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.width(64.dp)
                            )
                            Text(
                                value,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (p.hasMentor()) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                        Spacer(Modifier.height(12.dp))
                        listOfNotNull(
                            p.counselorName.takeIf { it.isNotBlank() }
                                ?.let { Triple("辅导员", it, p.counselorPhone) },
                            p.classTeacherName.takeIf { it.isNotBlank() }
                                ?.let { Triple("班主任", it, p.classTeacherPhone) },
                        ).forEachIndexed { index, (label, name, phone) ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.width(64.dp)
                                )
                                Text(name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                                if (phone.isNotBlank()) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        phone,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        p.counselorOffice.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "办公室",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.width(64.dp)
                                )
                                Text(it, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "我的"页卡片的下压暗叠层按压反馈，替代 SinkFeedback 收缩动画 */
@Composable
private fun Modifier.pressOverlay(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    return this
        .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
        .drawWithContent {
            drawContent()
            if (isPressed) drawRect(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.07f))
        }
}

@Composable
internal fun ProfileTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    credentialStore: CredentialStore,
    accountManager: com.xjtu.toolbox.account.AccountManager,
    scrollBehavior: ScrollBehavior? = null,
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFeedback: () -> Unit = {},
    onNavigateToAccounts: () -> Unit = {},
    /** 悬浮底栏的总占位高度（= MainScreen 的 floatingBarReserve）：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onWarmupRequest: () -> Unit = {},
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()

    // 登录表单状态
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginProgress by remember { mutableStateOf(0f) }   // 0.0 ~ 1.0
    var loginStage by remember { mutableStateOf("") }       // 当前步骤描述

    // ── 个人档案（hello.xjtu.edu.cn）──
    // 缓存优先：进页面先把磁盘上的读出来立刻渲染，再按新鲜度决定要不要静默刷新，
    // 全程不阻塞 UI，也不显示加载态——拿不到就退回原有的 YWTB 基础信息。
    val ctx = LocalContext.current
    var helloProfile by remember {
        mutableStateOf(com.xjtu.toolbox.hello.HelloProfileStore.cached(ctx))
    }
    var helloAvatar by remember {
        mutableStateOf(com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx))
    }

    /**
     * 学工档案拿到的姓名同时记成账号昵称。档案缓存在 cacheDir，会被系统清理、升级清缓存
     * 抹掉；昵称以前只从一网通办来，而一网通办经常登不上，两头都落空时"我的"页就只剩学号。
     */
    fun rememberRealName(name: String?) {
        if (name.isNullOrBlank() || name == loginState.cachedNickname) return
        loginState.cachedNickname = name
        credentialStore.saveNickname(name)
        loginState.accountId.takeIf { it.isNotEmpty() }?.let { accountManager.updateNickname(it, name) }
    }
    // ── 自定义头像 ──
    // 默认仍是学工证件照，用户点头像可换成自己的图；换完只刷新这一处 state，不动档案缓存。
    var showAvatarSheet by remember { mutableStateOf(false) }
    var hasCustomAvatar by remember {
        mutableStateOf(com.xjtu.toolbox.hello.HelloProfileStore.hasCustomAvatar(ctx))
    }
    var avatarSaving by remember { mutableStateOf(false) }
    // 选中的图先交给裁剪器，确认后才落盘。直接存原图的话，非正方形的照片
    // 会被显示侧的圆形裁切成随机的一块（多数人截图都是竖的，脸正好在圈外）。
    var avatarCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        avatarCropUri = uri
    }

    LaunchedEffect(loginState.isLoggedIn, loginState.activeUsername) {
        if (!loginState.isLoggedIn) {
            helloProfile = null
            helloAvatar = null
            return@LaunchedEffect
        }
        // 切账号后缓存目录随之变化，这里重新读一次本账号的
        helloProfile = com.xjtu.toolbox.hello.HelloProfileStore.cached(ctx)
        helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
        hasCustomAvatar = com.xjtu.toolbox.hello.HelloProfileStore.hasCustomAvatar(ctx)
        com.xjtu.toolbox.hello.HelloProfileStore
            .ensure(ctx, loginState.sessionManager)
            ?.let {
                helloProfile = it
                helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
                rememberRealName(it.name)
            }
    }

    // 智能登录：JWXT→核心登录→YWTB后台
    fun loginAllSystems(user: String, pwd: String) {
        val user = user.trim()
        isLoggingIn = true
        loginError = null
        loginProgress = 0f
        loginState.prepareCredentialsForLogin(user, pwd)

        scope.launch {
            loginStage = "认证中..."
            loginProgress = 0.1f
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    loginState.ensureCampusDetected()
                    loginState.sessionManager?.ensureSite(LoginType.JWXT)
                }
            } catch (e: Exception) {
                loginState.discardPreparedCredentials()
                isLoggingIn = false
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 熔断已上＝CAS 明确判了密码错（包括原样重试被熔断拦下），直接说人话
                loginError = if (loginState.passwordInvalidatedLatch) "学号或密码错误，请检查后再试"
                else "登录异常: ${e.message}"
                return@launch
            }

            loginProgress = 0.8f

            // ── 完成核心登录 ──
            loginProgress = 1f
            isLoggingIn = false
            loginState.saveCredentials(user, pwd)
            // 落库到 AccountStore（多账号架构），同时兼容旧 CredentialStore 单值
            accountManager.persistCurrentLogin(user, pwd, loginState.accountType)
            loginState.persistCredentials(credentialStore)

            // ── 后台: 仅预热必要 SSO，其余子系统由用户进入时按需登录 ──
            onWarmupRequest()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val ywtbSite = loginState.sessionManager?.ensureSite(LoginType.YWTB)
                    if (ywtbSite != null && loginState.ywtbUserInfo == null) {
                        loginState.ywtbUserInfo = com.xjtu.toolbox.ywtb.YwtbApi(ywtbSite).getUserInfo()
                        // YWTB 拿到昵称后回写到 AccountStore
                        loginState.ywtbUserInfo?.userName?.let { name ->
                            accountManager.updateNickname(user, name)
                        }
                        // 身份（本科生/研究生）以学校的登记为准，不劳用户自己在设置里选。
                        // identityTypeName 是一网通办返回的原字段，见 AccountType.fromIdentityName。
                        applyDetectedAccountType(
                            ctx, loginState, accountManager,
                            loginState.ywtbUserInfo?.identityTypeName,
                        )
                    }
                } catch (_: Exception) { }
                // 首次登录抓一次个人档案 + 头像并落盘，之后"我的"页直接读缓存。
                // 失败不影响登录流程：档案是锦上添花，YWTB 那份基础信息仍在。
                try {
                    helloProfile = com.xjtu.toolbox.hello.HelloProfileStore
                        .ensure(ctx, loginState.sessionManager, force = true)
                    helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
                    rememberRealName(helloProfile?.name)
                } catch (_: Exception) { }
            }
        }
    }

    // ── 头像裁剪 ──
    avatarCropUri?.let { cropUri ->
        com.xjtu.toolbox.ui.components.AvatarCropDialog(
            uri = cropUri,
            onCancel = { avatarCropUri = null },
            onConfirm = { bitmap ->
                avatarCropUri = null
                avatarSaving = true
                scope.launch {
                    val ok = com.xjtu.toolbox.hello.HelloProfileStore
                        .saveCustomAvatarBitmap(ctx, bitmap)
                    if (ok) {
                        helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
                        hasCustomAvatar = true
                    }
                    avatarSaving = false
                    showAvatarSheet = false
                }
            },
        )
    }

    // ── 换头像弹窗 ──
    if (showAvatarSheet) {
        BackHandler { if (!avatarSaving) showAvatarSheet = false }
        OverlayDialog(
            show = true,
            title = "更换头像",
            summary = if (hasCustomAvatar) "当前使用自定义头像。可重新选择，或恢复为学工系统证件照。"
                      else "默认使用学工系统证件照，可换成自己的图片。",
            onDismissRequest = { if (!avatarSaving) showAvatarSheet = false }
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = if (avatarSaving) "处理中..." else "从相册选择",
                    onClick = {
                        if (!avatarSaving) {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                if (hasCustomAvatar) {
                    TextButton(
                        text = "恢复默认头像",
                        onClick = {
                            if (!avatarSaving) {
                                com.xjtu.toolbox.hello.HelloProfileStore.clearCustomAvatar(ctx)
                                helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
                                hasCustomAvatar = false
                                showAvatarSheet = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(
                    text = "取消",
                    onClick = { if (!avatarSaving) showAvatarSheet = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ── UI ──
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
    ) {
        // 玻璃顶栏：内容铺到顶栏下面，留白放在滚动内容里
        Spacer(Modifier.height(contentTopPadding))
        // ━━ Hero Header ━━
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (loginState.isLoggedIn) Modifier.clickable { onNavigateToAccounts() } else Modifier),
            color = MiuixTheme.colorScheme.surface
        ) {
          Box(Modifier.fillMaxWidth()) {
            // 原来是三段静态纵向渐变，改成主题色流动底色，4 秒后停（在玻璃顶栏下面）
            com.xjtu.toolbox.ui.components.HeroMesh(
                base = MiuixTheme.colorScheme.surface,
                accent = MiuixTheme.colorScheme.primary,
                modifier = Modifier.matchParentSize(),
                runForMillis = 4_000L,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .enterOnce(0)
                    .padding(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Avatar：登录后显示姓名首字母，未登录显示通用 Icon
                    // 点头像 = 换头像；点头像以外的区域仍是进账号管理（内层 clickable 会吃掉事件）
                    val avatarPop = remember { androidx.compose.animation.core.Animatable(1f) }
                    LaunchedEffect(helloAvatar) {
                        if (helloAvatar != null) {
                            avatarPop.snapTo(0.82f)
                            avatarPop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 420f))
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .graphicsLayer { scaleX = avatarPop.value; scaleY = avatarPop.value }
                            .size(72.dp)
                            .then(
                                if (loginState.isLoggedIn) Modifier.clickable { showAvatarSheet = true }
                                else Modifier
                            ),
                        shape = CircleShape,
                        color = MiuixTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val avatar = helloAvatar
                            when {
                                // 学工系统的证件照。拿不到就退回姓名首字母，绝不留空。
                                loginState.isLoggedIn && avatar != null -> Image(
                                    bitmap = avatar.asImageBitmap(),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                loginState.isLoggedIn -> {
                                    val initial = (helloProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername).take(1)
                                    Text(initial, color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                                }
                                else -> Icon(Icons.Outlined.Person, null, Modifier.size(36.dp), tint = MiuixTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column {
                        if (loginState.isLoggedIn) {
                            Text(
                                helloProfile?.name?.takeIf { it.isNotBlank() }
                                    ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername,
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                loginState.activeUsername,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            helloProfile?.professionName?.takeIf { it.isNotBlank() }?.let { major ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    major,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        } else {
                            Text("岱宗盒子", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                            Text("登录以使用全部功能", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
          }
        }

        // ━━ 未登录 → 登录表单 ━━
        if (!loginState.isLoggedIn) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))

                // 登录表单
                Card(
                    modifier = Modifier.enterOnce(1).fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            "统一身份认证",
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "CAS 统一认证登录",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )

                        Spacer(Modifier.height(20.dp))

                        TextField(
                            value = username,
                            onValueChange = { username = it; loginError = null },
                            label = "学号 / 手机号",
                            singleLine = true,
                            enabled = !isLoggingIn,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        Spacer(Modifier.height(12.dp))
                        var passwordVisible by remember { mutableStateOf(false) }
                        TextField(
                            value = password,
                            onValueChange = { password = it; loginError = null },
                            label = "密码",
                            singleLine = true,
                            enabled = !isLoggingIn,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                    )
                                }
                            }
                        )
                        if (loginError != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(loginError!!, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(start = 4.dp))
                        }

                        Spacer(Modifier.height(20.dp))

                        // 登录按钮 + 进度
                        Button(
                            onClick = {
                                if (username.isBlank() || password.isBlank()) {
                                    loginError = "请输入学号和密码"
                                    return@Button
                                }
                                loginAllSystems(username, password)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = !isLoggingIn
                        ) {
                            if (isLoggingIn) {
                                CircularProgressIndicator(
                                    size = 20.dp,
                                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                        foregroundColor = MiuixTheme.colorScheme.onPrimary
                                    ),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(loginStage)
                            } else {
                                Text("登录", style = MiuixTheme.textStyles.subtitle)
                            }
                        }

                        // 进度条
                        if (isLoggingIn) {
                            Spacer(Modifier.height(16.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = loginProgress,
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                                label = "loginProgress"
                            )
                            LinearProgressIndicator(
                                progress = animatedProgress,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                    backgroundColor = MiuixTheme.colorScheme.surfaceVariant
                                )
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "密码仅用于本地加密后发送至学校 CAS 服务器",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.enterOnce(2).fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToSettings() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("设置", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToFeedback() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("反馈与建议", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                Text(
                                    "说说哪儿不好用，或想加什么",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // ━━ 已登录 → 在校信息 + 辅导员 + 系统状态 ━━
        if (loginState.isLoggedIn) {
            val context = LocalContext.current

            LaunchedEffect(loginState.hasCredentials) {
                if (loginState.ywtbUserInfo != null) return@LaunchedEffect
                if (!loginState.hasCredentials) return@LaunchedEffect
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val ywtbSite = runCatching { loginState.sessionManager?.ensureSite(LoginType.YWTB) }.getOrNull()
                    if (ywtbSite != null && loginState.ywtbUserInfo == null) {
                        runCatching {
                            loginState.ywtbUserInfo = com.xjtu.toolbox.ywtb.YwtbApi(ywtbSite).getUserInfo()
                            // 老用户不会再走一次首登流程，身份校正得在这儿也挂一次，
                            // 否则升级上来的人还停在当初手选的那个值上。
                            applyDetectedAccountType(
                                ctx, loginState, accountManager,
                                loginState.ywtbUserInfo?.identityTypeName,
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // 学籍档案（hello.xjtu.edu.cn）。缓存优先，没有就整块不渲染。
                helloProfile?.takeIf { it.hasContent() }?.let { p ->
                    Box(Modifier.enterOnce(1)) { ProfileInfoCard(p) }
                }

                Spacer(Modifier.height(12.dp))

                // ━━ 下载管理入口卡片 ━━
                var downloadStats by remember { mutableStateOf<com.xjtu.toolbox.media.DownloadManager.DownloadStats?>(null) }
                var lmsDownloadCount by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val downloadManager = com.xjtu.toolbox.media.DownloadManager.getInstance(context)
                        downloadStats = downloadManager.getDownloadStats()
                        lmsDownloadCount = com.xjtu.toolbox.lms.LmsDownloadStore.getAll(context).size
                    }
                }
                Card(
                    onClick = onNavigateToDownloads,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    pressFeedbackType = PressFeedbackType.Sink,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("下载管理", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            val stats = downloadStats
                            if (stats != null) {
                                val statsText = buildString {
                                    if (stats.downloadingCount > 0) append("${stats.downloadingCount}个下载中")
                                    if (stats.completedCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${stats.completedCount}个已完成")
                                    }
                                    if (lmsDownloadCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${lmsDownloadCount}个课件")
                                    }
                                    if (isEmpty()) append("暂无下载")
                                }
                                Text(statsText, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } else {
                                Text("查看下载进度和记录", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ━━ 账号管理 + 设置 + 退出登录 ━━
                Card(
                    modifier = Modifier.enterOnce(3).fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        // 账号管理入口行
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToAccounts() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("账号管理", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                val cnt = accountManager.accountCount()
                                if (cnt > 0) {
                                    Text(
                                        "已保存 $cnt 个账号",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // 设置入口行
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToSettings() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("设置", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToFeedback() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("反馈与建议", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                Text(
                                    "说说哪儿不好用，或想加什么",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // 退出登录行
                        val showLogoutDialog = remember { mutableStateOf(false) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { showLogoutDialog.value = true }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.onError.copy(alpha = 0.5f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("退出登录", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        if (showLogoutDialog.value) {
                            BackHandler { showLogoutDialog.value = false }
                            OverlayDialog(
                                show = showLogoutDialog.value,
                                title = "确认退出",
                                summary = "退出当前账号的登录，清除其会话 Cookie。账号记录与本地缓存保留，下次可在「账号管理」快速切回。",
                                onDismissRequest = { showLogoutDialog.value = false }
                            ) {
                                Row(Modifier.fillMaxWidth()) {
                                    TextButton(
                                        text = "取消",
                                        onClick = { showLogoutDialog.value = false },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(20.dp))
                                    TextButton(
                                        text = "退出登录",
                                        onClick = {
                                            showLogoutDialog.value = false
                                            scope.launch { accountManager.logoutCurrent() }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColors(
                                            textColor = MiuixTheme.colorScheme.error
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(extraBottomPadding))
    }
}

