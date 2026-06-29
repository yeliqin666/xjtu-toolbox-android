package com.xjtu.toolbox.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.xjtu.toolbox.AppLoginState
import com.xjtu.toolbox.auth.AccountType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.basic.TooltipBox
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
 * 顶部展示当前账号，列表按「当前账号优先、最近使用优先」排序。
 * 所有账号级操作共享同一 busy 状态，避免切换、删除、新增并发互相踩状态。
 */
@Composable
fun AccountManagerScreen(
    accountManager: AccountManager,
    loginState: AppLoginState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeId = loginState.accountId
    fun orderedAccounts(): List<Account> = accountManager.accountList()
        .sortedWith(
            compareByDescending<Account> { it.accountId == activeId }
                .thenByDescending { it.lastUsedAt }
        )

    var accounts by remember { mutableStateOf(orderedAccounts()) }

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

    fun refresh() { accounts = orderedAccounts() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pageBusy = globalBusy || busyAccountId != null
    val activeAccount = accounts.firstOrNull { it.accountId == activeId }

    LaunchedEffect(activeId) {
        refresh()
    }

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
                        enabled = !pageBusy
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
            ActiveAccountPanel(
                account = activeAccount,
                accountCount = accounts.size,
                busy = pageBusy,
                onAdd = { if (!pageBusy) showAddSheet = true }
            )

            if (accounts.isEmpty()) {
                EmptyAccountsPanel(
                    enabled = !pageBusy,
                    onAdd = { showAddSheet = true }
                )
            } else {
                SmallTitle("已保存的账号（${accounts.size}）")
                AccountListPanel(
                    accounts = accounts,
                    activeId = activeId,
                    busyAccountId = busyAccountId,
                    enabled = !pageBusy,
                    onSwitch = { account ->
                        if (account.accountId != activeId && !pageBusy) pendingSwitch = account
                    },
                    onEditPassword = { account ->
                        if (!pageBusy) pendingEditPwd = account
                    },
                    onDelete = { account ->
                        if (!pageBusy) pendingDelete = account
                    },
                )
            }

            toast?.let {
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(1800)
                    toast = null
                }
                StatusNotice(text = it)
            }

            Spacer(Modifier.height(28.dp))
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
                    try {
                        val result = accountManager.addAccount(user, pwd, type)
                        result.fold(
                            onSuccess = {
                                refresh()
                                toast = "已添加 ${accountTitle(it)}"
                            },
                            onFailure = { toast = "添加失败：${it.message ?: "未知错误"}" },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast = "添加失败：${e.message ?: "未知错误"}"
                    } finally {
                        globalBusy = false
                        refresh()
                    }
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
                    try {
                        val switched = accountManager.switchTo(target.accountId)
                        refresh()
                        toast = if (switched != null) {
                            "已切换到 ${accountTitle(switched)}"
                        } else {
                            "账号不存在"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast = "切换失败：${e.message ?: "未知错误"}"
                    } finally {
                        busyAccountId = null
                        refresh()
                    }
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
                try {
                    accountManager.updatePassword(target.accountId, newPwd)
                    refresh()
                    toast = "已更新 ${target.accountId} 的密码"
                } catch (e: Exception) {
                    toast = "更新失败：${e.message ?: "未知错误"}"
                } finally {
                    pendingEditPwd = null
                }
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
                    try {
                        val removed = accountManager.removeAccount(target.accountId, deleteCache)
                        refresh()
                        toast = if (removed) "已删除账号 ${target.accountId}" else "账号不存在"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast = "删除失败：${e.message ?: "未知错误"}"
                    } finally {
                        busyAccountId = null
                        refresh()
                    }
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun ActiveAccountPanel(
    account: Account?,
    accountCount: Int,
    busy: Boolean,
    onAdd: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        cornerRadius = 24.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(
                    account = account,
                    isActive = true,
                    size = 64,
                    textSize = MiuixTheme.textStyles.title1
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "当前账号",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        account?.let(::accountTitle) ?: "未登录",
                        style = MiuixTheme.textStyles.title1,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        account?.accountId ?: "添加账号后可在这里快速切换",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (busy) {
                    CircularProgressIndicator(
                        size = 24.dp,
                        strokeWidth = 2.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors()
                    )
                } else {
                    AccountIconAction(
                        icon = MiuixIcons.Add,
                        contentDescription = "新增账号",
                        tint = MiuixTheme.colorScheme.primary,
                        enabled = true,
                        onClick = onAdd
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountInfoChip("已保存 $accountCount", MiuixTheme.colorScheme.primary)
                account?.let {
                    AccountInfoChip(it.accountType.displayName, accountAccent(it, true))
                    AccountInfoChip(lastUsedText(it.lastUsedAt), MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun EmptyAccountsPanel(
    enabled: Boolean,
    onAdd: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AccountAvatar(account = null, isActive = false, size = 56, textSize = MiuixTheme.textStyles.title2)
            Spacer(Modifier.height(12.dp))
            Text(
                "还没有保存账号",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "添加后即可在本机保存并切换校园账号",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAdd, enabled = enabled) { Text("添加账号") }
        }
    }
}

@Composable
private fun AccountListPanel(
    accounts: List<Account>,
    activeId: String,
    busyAccountId: String?,
    enabled: Boolean,
    onSwitch: (Account) -> Unit,
    onEditPassword: (Account) -> Unit,
    onDelete: (Account) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
    ) {
        Column {
            accounts.forEachIndexed { index, account ->
                AccountRow(
                    account = account,
                    isActive = account.accountId == activeId,
                    busy = busyAccountId == account.accountId,
                    enabled = enabled,
                    onSwitch = { onSwitch(account) },
                    onEditPassword = { onEditPassword(account) },
                    onDelete = { onDelete(account) }
                )
                if (index != accounts.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 86.dp),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isActive: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onSwitch: () -> Unit,
    onEditPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    val rowEnabled = enabled && !busy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp)
            .clickable(enabled = rowEnabled && !isActive) { onSwitch() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountAvatar(
            account = account,
            isActive = isActive,
            size = 50,
            textSize = MiuixTheme.textStyles.title3
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    accountTitle(account),
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    AccountInfoChip("当前", MiuixTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                account.accountId,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${account.accountType.displayName} · ${lastUsedText(account.lastUsedAt)}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        if (busy) {
            CircularProgressIndicator(
                size = 24.dp,
                strokeWidth = 2.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors()
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isActive) {
                    AccountTextAction(
                        text = "切换",
                        enabled = rowEnabled,
                        onClick = onSwitch
                    )
                }
                AccountIconAction(
                    icon = MiuixIcons.Edit,
                    contentDescription = "修改密码",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    enabled = rowEnabled,
                    onClick = onEditPassword
                )
                AccountIconAction(
                    icon = MiuixIcons.Delete,
                    contentDescription = "删除账号",
                    tint = MiuixTheme.colorScheme.error,
                    enabled = rowEnabled,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun AccountAvatar(
    account: Account?,
    isActive: Boolean,
    size: Int,
    textSize: androidx.compose.ui.text.TextStyle,
) {
    val accent = account?.let { accountAccent(it, isActive) } ?: MiuixTheme.colorScheme.primary
    val gradient = Brush.verticalGradient(
        colors = if (isActive) {
            listOf(MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.primaryVariant)
        } else {
            listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.10f))
        }
    )
    val label = account?.let { accountTitle(it).take(1).uppercase() }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(gradient),
        contentAlignment = Alignment.Center
    ) {
        if (label == null) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = if (isActive) MiuixTheme.colorScheme.onPrimary else accent,
                modifier = Modifier.size((size * 0.46f).dp)
            )
        } else {
            Text(
                label,
                color = if (isActive) MiuixTheme.colorScheme.onPrimary else accent,
                style = textSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AccountInfoChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccountTextAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = if (enabled) 0.12f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AccountIconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val iconColor = if (enabled) tint else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.42f)
    TooltipBox(
        text = contentDescription,
        enabled = enabled
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = if (enabled) 0.10f else 0.05f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, tint = iconColor, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatusNotice(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
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
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onCacheToggle(!deleteCache) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(24.dp), shape = CircleShape,
                    color = if (deleteCache) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant
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

private fun accountTitle(account: Account): String =
    account.nickname?.takeIf { it.isNotBlank() } ?: account.accountId

private fun accountAccent(account: Account, isActive: Boolean): Color {
    if (isActive) return Color(0xFF0A84FF)
    val palette = listOf(
        Color(0xFF0A84FF),
        Color(0xFF34C759),
        Color(0xFFFF9F0A),
        Color(0xFFFF375F),
        Color(0xFF5E5CE6),
        Color(0xFF00A7A7),
    )
    val index = (account.accountId.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[index]
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
