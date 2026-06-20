package com.xjtu.toolbox.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.xjtu.toolbox.AppLoginState
import com.xjtu.toolbox.auth.AccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 多账号管理页。
 *
 * - 顶部「+」新增账号
 * - 卡片列表展示全部账号：头像（学号首字 + 品牌色渐变）、学号、昵称、身份 chip、当前徽标、上次使用时间
 * - 点击非当前账号 → 切换；长按/「更多」→ 修改密码 / 删除
 * - 切换/删除均为异步，期间禁用按钮并显示 loading
 */
@Composable
fun AccountManagerScreen(
    accountManager: AccountManager,
    loginState: AppLoginState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(accountManager.accountList()) }
    val activeId = loginState.accountId

    // 切换/新增/删除时的 busy 账号 id（避免重复点击）
    var busyAccountId by remember { mutableStateOf<String?>(null) }
    var globalBusy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 新增账号弹窗
    var showAddSheet by remember { mutableStateOf(false) }

    // 操作弹窗
    var pendingSwitch by remember { mutableStateOf<Account?>(null) }
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    var pendingEditPwd by remember { mutableStateOf<Account?>(null) }

    fun refresh() { accounts = accountManager.accountList() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = "账号管理",
                largeTitle = "账号管理",
                color = MiuixTheme.colorScheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddSheet = true },
                        enabled = !globalBusy && busyAccountId == null
                    ) {
                        Icon(MiuixIcons.Add, contentDescription = "新增账号")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            SmallTitle("已保存的账号（${accounts.size}）")
            accounts.forEach { account ->
                AccountCard(
                    account = account,
                    isActive = account.accountId == activeId,
                    busy = busyAccountId == account.accountId,
                    onSwitch = {
                        if (account.accountId == activeId) return@AccountCard
                        pendingSwitch = account
                    },
                    onEditPassword = { pendingEditPwd = account },
                    onDelete = { pendingDelete = account },
                )
            }

            if (accounts.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Person, null,
                        Modifier.size(48.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有账号", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showAddSheet = true }) { Text("添加账号") }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "切换账号时会清空当前账号的内存会话并加载目标账号的缓存与 Cookie。" +
                    "各账号的课表、成绩、对话、校园卡数据相互隔离。",
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))

            toast?.let {
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(1800)
                    toast = null
                }
                Text(
                    it, modifier = Modifier.padding(horizontal = 20.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }
    }

    // ── 新增账号弹窗 ──
    if (showAddSheet) {
        AddAccountDialog(
            onDismiss = { showAddSheet = false },
            onSubmit = { user, pwd, type ->
                showAddSheet = false
                globalBusy = true
                scope.launch {
                    val result = accountManager.addAccount(user, pwd, type)
                    globalBusy = false
                    result.fold(
                        onSuccess = { refresh(); toast = "已添加账号 ${it.accountId}" },
                        onFailure = { toast = "添加失败：${it.message ?: "未知错误"}" },
                    )
                }
            }
        )
    }

    // ── 切换确认 ──
    pendingSwitch?.let { target ->
        SwitchConfirmDialog(
            target = target,
            onConfirm = {
                pendingSwitch = null
                busyAccountId = target.accountId
                scope.launch {
                    accountManager.switchTo(target.accountId)
                    busyAccountId = null
                    refresh()
                    toast = "已切换到 ${target.nickname ?: target.accountId}"
                }
            },
            onDismiss = { pendingSwitch = null }
        )
    }

    // ── 修改密码弹窗 ──
    pendingEditPwd?.let { target ->
        EditPasswordDialog(
            target = target,
            onDismiss = { pendingEditPwd = null },
            onSubmit = { newPwd ->
                accountManager.updatePassword(target.accountId, newPwd)
                pendingEditPwd = null
                refresh()
                toast = "已更新 ${target.accountId} 的密码"
            }
        )
    }

    // ── 删除确认 ──
    pendingDelete?.let { target ->
        var deleteCache by remember(target.accountId) { mutableStateOf(true) }
        DeleteConfirmDialog(
            target = target,
            deleteCache = deleteCache,
            onCacheToggle = { deleteCache = it },
            onConfirm = {
                pendingDelete = null
                busyAccountId = target.accountId
                scope.launch {
                    accountManager.removeAccount(target.accountId, deleteCache)
                    busyAccountId = null
                    refresh()
                    toast = "已删除账号 ${target.accountId}"
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    isActive: Boolean,
    busy: Boolean,
    onSwitch: () -> Unit,
    onEditPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    val initial = (account.nickname ?: account.accountId).take(1).uppercase()
    val gradient = Brush.verticalGradient(
        colors = if (isActive) listOf(MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.primaryVariant)
        else listOf(MiuixTheme.colorScheme.surfaceVariant, MiuixTheme.colorScheme.surfaceVariant)
    )
    val onAvatar = if (isActive) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(initial, color = onAvatar, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            // 学号/昵称/身份
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.nickname ?: account.accountId,
                        style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                "当前", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    account.accountId,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    account.accountType.displayName + " · " + lastUsedText(account.lastUsedAt),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                )
            }
            // 操作
            if (busy) {
                CircularProgressIndicator(
                    size = 22.dp, strokeWidth = 2.dp,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors()
                )
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    if (!isActive) {
                        TextButton(text = "切换", onClick = onSwitch, modifier = Modifier.height(36.dp))
                    }
                    Row {
                        IconButton(onClick = onEditPassword, modifier = Modifier.size(36.dp)) {
                            Icon(MiuixIcons.Edit, contentDescription = "修改密码", tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(MiuixIcons.Delete, contentDescription = "删除账号", tint = MiuixTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSubmit: (username: String, password: String, accountType: AccountType) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pwdVisible by remember { mutableStateOf(false) }
    var accountType by remember { mutableStateOf(AccountType.UNDERGRADUATE) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onDismiss() }
    OverlayDialog(
        show = true,
        title = "添加账号",
        summary = "输入新账号的统一身份认证信息，将先登录验证后再保存。",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = username, onValueChange = { username = it; error = null },
                label = "学号 / 手机号", singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            TextField(
                value = password, onValueChange = { password = it; error = null },
                label = "密码", singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { pwdVisible = !pwdVisible }) {
                        Icon(
                            if (pwdVisible) MiuixIcons.Ok else MiuixIcons.Edit,
                            contentDescription = if (pwdVisible) "隐藏密码" else "显示密码"
                        )
                    }
                }
            )
            // 身份选择
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccountType.entries.forEach { type ->
                    val selected = accountType == type
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceVariant,
                        onClick = { accountType = type }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                type.displayName,
                                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            error?.let {
                Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            error = "请输入学号和密码"; return@Button
                        }
                        onSubmit(username.trim(), password, accountType)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("登录并添加") }
            }
        }
    }
}

@Composable
private fun SwitchConfirmDialog(
    target: Account,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    OverlayDialog(
        show = true,
        title = "切换账号",
        summary = "将切换到 ${target.nickname ?: target.accountId}，当前账号的内存会话将被清空（缓存保留）。",
        onDismissRequest = onDismiss
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("切换") }
        }
    }
}

@Composable
private fun EditPasswordDialog(
    target: Account,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { onDismiss() }
    OverlayDialog(
        show = true,
        title = "修改密码",
        summary = "更新 ${target.accountId} 的密码。下次使用该账号时会用新密码登录。",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = pwd, onValueChange = { pwd = it; error = null },
                label = "新密码", singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) MiuixIcons.Ok else MiuixIcons.Edit, contentDescription = null)
                    }
                }
            )
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (pwd.isBlank()) { error = "密码不能为空"; return@Button }
                        onSubmit(pwd)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    target: Account,
    deleteCache: Boolean,
    onCacheToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    OverlayDialog(
        show = true,
        title = "删除账号",
        summary = "将从本机移除 ${target.accountId} 的凭据与 Cookie。" +
            if (deleteCache) "同时删除其课表、成绩、对话缓存。" else "保留其本地缓存。",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(24.dp), shape = CircleShape,
                    color = if (deleteCache) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant,
                    onClick = { onCacheToggle(!deleteCache) }
                ) {
                    if (deleteCache) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(MiuixIcons.Ok, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("同时删除本地缓存", style = MiuixTheme.textStyles.body2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error
                    )
                ) { Text("删除") }
            }
        }
    }
}

private fun lastUsedText(ts: Long): String {
    if (ts <= 0L) return "未使用"
    val delta = System.currentTimeMillis() - ts
    val min = delta / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        min < 60 * 24 * 30 -> "${min / (60 * 24)} 天前"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(java.util.Date(ts))
    }
}
