package com.xjtu.toolbox

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ══════════════════════════════════════════
//  Tab 4 — 我的（含统一登录）
// ══════════════════════════════════════════

/** 「我的」页各卡的圆角与间距，和首页同一档。 */
private val PROFILE_RADIUS = 24.dp
private val PROFILE_GAP = 14.dp

/**
 * 学籍档案卡。数据来自 hello.xjtu.edu.cn，字段缺失时整行不渲染——
 * 宁可少一行，也不要出现"专业：—"这种占位。
 */
@Composable
private fun ProfileInfoCard(p: com.xjtu.toolbox.hello.HelloProfile, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .squircleClip(PROFILE_RADIUS)
            .background(AppCardColor)
    ) {
        ProfileMenuRow(
            icon = Icons.Outlined.School,
            tint = MiuixTheme.colorScheme.primary,
            title = "学籍信息",
            subtitle = listOf(p.departmentName, p.className).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            trailingIcon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp, bottom = 14.dp)) {
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
                    InfoLine(label, value)
                }

                if (p.hasMentor()) {
                    Spacer(Modifier.height(14.dp))
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
                                Text(phone, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                    p.counselorOffice.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        InfoLine("办公室", it)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(64.dp)
        )
        Text(value, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/** 按下时压一层淡暗色。列表行用它，比整行缩放更安静。 */
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
            if (isPressed) drawRect(color = Color.Black.copy(alpha = 0.06f))
        }
}

/**
 * 菜单行：浅色底的图标徽记 + 标题 / 说明 + 右箭头。
 * 「我的」页所有入口都用它，一组放进同一张卡，行间用缩进的细线分开。
 */
@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
) {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressOverlay(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .squircleBackground(tint.copy(alpha = if (dark) 0.22f else 0.12f), 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = titleColor)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                trailingIcon, null, Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f),
            )
        }
    }
}

/** 同一组菜单行之间的细线：从图标右边开始，不横穿徽记。 */
@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .padding(start = 62.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f))
    )
}

/** 一组菜单行的外壳。 */
@Composable
private fun MenuGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .squircleClip(PROFILE_RADIUS)
            .background(AppCardColor),
        content = content,
    )
}

private val TINT_TEAL = Color(0xFF1F9E8F)
private val TINT_SLATE = Color(0xFF6B7A90)
private val TINT_AMBER = Color(0xFFE39A1B)

/**
 * 顶部的身份卡：头像 + 姓名 + 学号 / 专业 + 一行标签。
 *
 * 高度固定：第三行在档案没加载到时换成一句提示，而不是空着——以前专业那一行要等档案回来
 * 才出现，整块往下一挤，看着像「抽了一下」。
 */
@Composable
private fun ProfileHeroCard(
    isLoggedIn: Boolean,
    name: String,
    studentId: String,
    profile: com.xjtu.toolbox.hello.HelloProfile?,
    avatar: android.graphics.Bitmap?,
    avatarVersion: Int,
    onAvatarClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier
            .fillMaxWidth()
            .appCardShadow(shape = RoundedCornerShape(PROFILE_RADIUS), strong = true)
            .squircleClip(PROFILE_RADIUS)
            .background(AppCardColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                enabled = isLoggedIn,
                onClick = onClick,
            ),
    ) {
        com.xjtu.toolbox.ui.components.HeroMesh(
            base = AppCardColor,
            accent = primary,
            modifier = Modifier.matchParentSize(),
            runForMillis = 4_000L,
        )
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            // 只在用户自己换完头像后弹一下。以前挂在头像 Bitmap 上，而档案每刷新一次就重新读出
            // 一个新的 Bitmap 对象，进页面、切回来都会莫名弹一下。
            val pop = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(avatarVersion) {
                if (avatarVersion > 0) {
                    pop.snapTo(0.84f)
                    pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
                }
            }
            Box(
                Modifier
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .size(76.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(primary)
                        .border(3.dp, AppCardColor, CircleShape)
                        .then(if (isLoggedIn) Modifier.clickable(onClick = onAvatarClick) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isLoggedIn && avatar != null -> Image(
                            bitmap = remember(avatar) { avatar.asImageBitmap() },
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        isLoggedIn -> Text(
                            name.take(1),
                            color = MiuixTheme.colorScheme.onPrimary,
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.Bold,
                        )
                        else -> Icon(Icons.Outlined.Person, null, Modifier.size(38.dp), tint = MiuixTheme.colorScheme.onPrimary)
                    }
                }
                if (isLoggedIn) {
                    // 右下角的小相机：告诉人「头像可以点」
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(AppCardColor)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "更换头像", tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isLoggedIn) name else "岱宗盒子",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (isLoggedIn) listOfNotNull(studentId, profile?.professionName?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    else "登录以使用全部功能",
                    style = MiuixTheme.textStyles.footnote1,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLoggedIn) {
                    Spacer(Modifier.height(8.dp))
                    val tags = listOfNotNull(
                        profile?.academyName?.takeIf { it.isNotBlank() },
                        profile?.grade?.takeIf { it > 0 }?.let { "$it 级" },
                        profile?.campusName?.takeIf { it.isNotBlank() },
                    )
                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag ->
                                Text(
                                    tag,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = primary,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .squircleBackground(primary.copy(alpha = 0.10f), 8.dp)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    } else {
                        Text("点这里管理账号", style = MiuixTheme.textStyles.footnote2, color = primary)
                    }
                }
            }
            if (isLoggedIn) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = muted.copy(alpha = 0.6f))
            }
        }
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
    /** 用户自己换 / 恢复头像的次数，头像只在它变化时弹一下。 */
    var avatarVersion by remember { mutableIntStateOf(0) }
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
                withContext(Dispatchers.IO) {
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
            // 姓名、头像来自学工档案，和一网通办互不依赖，两路同时开始：
            // 以前串在一网通办后面，它慢或者登不上时，头像和姓名要白等十几秒。
            // 而且要在预热（onWarmupRequest）之前发起：所有站点的 CAS 登录共用一把公平锁排队，
            // 先发起的先登，档案不该排在一串子系统预热后面。
            scope.launch(Dispatchers.IO) {
                try {
                    helloProfile = com.xjtu.toolbox.hello.HelloProfileStore
                        .ensure(ctx, loginState.sessionManager, force = true) { p ->
                            // 档案一到先上姓名、专业，头像下完再补
                            helloProfile = p
                            rememberRealName(p.name)
                        }
                    helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
                } catch (_: Exception) { }
            }
            onWarmupRequest()
            scope.launch(Dispatchers.IO) {
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
                        avatarVersion++
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
                                avatarVersion++
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

    // 已登录时后台补一次一网通办的基础信息（身份校正也在这里）
    if (loginState.isLoggedIn) {
        LaunchedEffect(loginState.hasCredentials) {
            if (loginState.ywtbUserInfo != null) return@LaunchedEffect
            if (!loginState.hasCredentials) return@LaunchedEffect
            withContext(Dispatchers.IO) {
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
    }

    // 账号数、下载统计都在后台读一次：以前 accountCount() 直接写在组合里，每次重组都解析一遍加密账号库
    val accountCount by produceState(0, loginState.accountId) {
        value = withContext(Dispatchers.IO) { runCatching { accountManager.accountCount() }.getOrDefault(0) }
    }
    val downloadSummary by produceState<String?>(null, loginState.isLoggedIn) {
        if (!loginState.isLoggedIn) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stats = com.xjtu.toolbox.media.DownloadManager.getInstance(ctx).getDownloadStats()
                val lms = com.xjtu.toolbox.lms.LmsDownloadStore.getAll(ctx).size
                buildString {
                    if (stats.downloadingCount > 0) append("${stats.downloadingCount} 个下载中")
                    if (stats.completedCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${stats.completedCount} 个已完成")
                    }
                    if (lms > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("$lms 个课件")
                    }
                    if (isEmpty()) append("暂无下载")
                }
            }.getOrNull()
        }
    }

    val displayName = helloProfile?.name?.takeIf { it.isNotBlank() }
        ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername

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
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
        ) {
            ProfileHeroCard(
                isLoggedIn = loginState.isLoggedIn,
                name = displayName,
                studentId = loginState.activeUsername,
                profile = helloProfile,
                avatar = helloAvatar,
                avatarVersion = avatarVersion,
                onAvatarClick = { showAvatarSheet = true },
                onClick = onNavigateToAccounts,
                modifier = Modifier.enterOnce(0),
            )
            Spacer(Modifier.height(PROFILE_GAP))

            if (!loginState.isLoggedIn) {
                // ━━ 未登录 → 登录表单 ━━
                Column(
                    Modifier
                        .enterOnce(1)
                        .fillMaxWidth()
                        .squircleClip(PROFILE_RADIUS)
                        .background(AppCardColor)
                        .padding(20.dp)
                ) {
                    Text("统一身份认证", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
                    Text(
                        "用学号（或手机号）和 CAS 密码登录",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(18.dp))
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
                        Spacer(Modifier.height(6.dp))
                        Text(loginError!!, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(start = 4.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            if (username.isBlank() || password.isBlank()) {
                                loginError = "请输入学号和密码"
                                return@Button
                            }
                            loginAllSystems(username, password)
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !isLoggingIn,
                        colors = ButtonDefaults.buttonColorsPrimary(),
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
                            Text(loginStage, color = MiuixTheme.colorScheme.onPrimary)
                        } else {
                            Text("登录", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                    if (isLoggingIn) {
                        Spacer(Modifier.height(14.dp))
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "密码加密保存在本机，只发往学校 CAS",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(PROFILE_GAP))
                MenuGroup(Modifier.enterOnce(2)) {
                    ProfileMenuRow(Icons.Outlined.Settings, TINT_SLATE, "设置", onClick = onNavigateToSettings)
                    MenuDivider()
                    ProfileMenuRow(Icons.Outlined.ChatBubbleOutline, TINT_AMBER, "反馈与建议", onClick = onNavigateToFeedback, subtitle = "说说哪儿不好用，或想加什么")
                }
            } else {
                // ━━ 已登录 ━━
                helloProfile?.takeIf { it.hasContent() }?.let { p ->
                    ProfileInfoCard(p, Modifier.enterOnce(1))
                    Spacer(Modifier.height(PROFILE_GAP))
                }

                MenuGroup(Modifier.enterOnce(2)) {
                    ProfileMenuRow(
                        Icons.Outlined.ManageAccounts, MiuixTheme.colorScheme.primary, "账号管理",
                        onClick = onNavigateToAccounts,
                        subtitle = if (accountCount > 0) "已保存 $accountCount 个账号" else "切换或添加账号",
                    )
                    MenuDivider()
                    ProfileMenuRow(
                        Icons.Outlined.Download, TINT_TEAL, "下载管理",
                        onClick = onNavigateToDownloads,
                        subtitle = downloadSummary ?: "查看下载进度和记录",
                    )
                }
                Spacer(Modifier.height(PROFILE_GAP))
                MenuGroup(Modifier.enterOnce(3)) {
                    ProfileMenuRow(Icons.Outlined.Settings, TINT_SLATE, "设置", onClick = onNavigateToSettings)
                    MenuDivider()
                    ProfileMenuRow(Icons.Outlined.ChatBubbleOutline, TINT_AMBER, "反馈与建议", onClick = onNavigateToFeedback, subtitle = "说说哪儿不好用，或想加什么")
                }
                Spacer(Modifier.height(PROFILE_GAP))

                // 退出登录单独一张：危险操作不和日常入口挤在一组里
                val showLogoutDialog = remember { mutableStateOf(false) }
                MenuGroup(Modifier.enterOnce(4)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .pressOverlay { showLogoutDialog.value = true }
                            .padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("退出登录", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.error)
                        }
                    }
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

        Spacer(Modifier.height(PROFILE_GAP + extraBottomPadding))
    }
}
