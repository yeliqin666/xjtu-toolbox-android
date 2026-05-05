package com.xjtu.toolbox.ui.settings

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

@Composable
fun SettingsScreen(
    credentialStore: CredentialStore,
    onBack: () -> Unit,
    onNavBarStyleChanged: (String) -> Unit = {},
    onDarkModeChanged: (String) -> Unit = {},
    onDefaultTabChanged: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var navBarStyle by remember { mutableStateOf(credentialStore.navBarStyle) }
    var darkMode by remember { mutableStateOf(credentialStore.darkMode) }
    var defaultTab by remember { mutableStateOf(credentialStore.defaultTab) }
    var networkMode by remember { mutableStateOf(credentialStore.networkMode) }
    var autoCheckUpdate by remember { mutableStateOf(credentialStore.autoCheckUpdate) }
    var updateChannel by remember { mutableStateOf(credentialStore.updateChannel) }
    var cacheSizeText by remember { mutableStateOf("计算中...") }
    var showChangelog by remember { mutableStateOf(false) }
    var showEula by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSizeText = runCatching {
                context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.map(::formatFileSize).getOrDefault("无法获取")
        }
    }

    val versionText = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val lmsDownloadDir = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }
    val replayDownloadDir = remember {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ClassReplay"
        ).absolutePath
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置",
                largeTitle = "设置",
                color = MiuixTheme.colorScheme.surfaceVariant,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            SettingsGroupHeader("外观")
            SettingsCard {
                SettingsRadioRow(
                    icon = Icons.Default.DarkMode,
                    iconColor = MiuixTheme.colorScheme.primary,
                    title = "深色模式",
                    subtitle = when (darkMode) {
                        CredentialStore.DARK_MODE_LIGHT -> "始终浅色"
                        CredentialStore.DARK_MODE_DARK -> "始终深色"
                        else -> "跟随系统"
                    },
                    options = listOf(
                        "跟随系统" to CredentialStore.DARK_MODE_SYSTEM,
                        "始终浅色" to CredentialStore.DARK_MODE_LIGHT,
                        "始终深色" to CredentialStore.DARK_MODE_DARK
                    ),
                    selected = darkMode,
                    onSelect = {
                        darkMode = it
                        credentialStore.darkMode = it
                        onDarkModeChanged(it)
                    }
                )
                SettingsRadioRow(
                    icon = Icons.Default.SpaceBar,
                    iconColor = MiuixTheme.colorScheme.primaryVariant,
                    title = "底栏风格",
                    subtitle = when (navBarStyle) {
                        CredentialStore.NAV_STYLE_FLOATING -> "悬浮胶囊"
                        CredentialStore.NAV_STYLE_LIQUID -> "液态玻璃"
                        else -> "经典底栏"
                    },
                    options = listOf(
                        "悬浮胶囊" to CredentialStore.NAV_STYLE_FLOATING,
                        "经典底栏" to CredentialStore.NAV_STYLE_CLASSIC,
                        "液态玻璃" to CredentialStore.NAV_STYLE_LIQUID
                    ),
                    selected = navBarStyle,
                    onSelect = {
                        navBarStyle = it
                        credentialStore.navBarStyle = it
                        onNavBarStyleChanged(it)
                    }
                )
                SettingsRadioRow(
                    icon = Icons.Default.Tab,
                    iconColor = MiuixTheme.colorScheme.secondary,
                    title = "默认启动 Tab",
                    subtitle = tabLabel(defaultTab),
                    options = listOf(
                        "首页" to CredentialStore.TAB_HOME,
                        "日程" to CredentialStore.TAB_COURSES,
                        "工具" to CredentialStore.TAB_TOOLS,
                        "我的" to CredentialStore.TAB_PROFILE
                    ),
                    selected = defaultTab,
                    onSelect = {
                        defaultTab = it
                        credentialStore.defaultTab = it
                        onDefaultTabChanged(it)
                    }
                )
            }

            SettingsGroupHeader("网络")
            SettingsCard {
                SettingsRadioRow(
                    icon = Icons.Default.Wifi,
                    iconColor = MiuixTheme.colorScheme.primary,
                    title = "连接模式",
                    subtitle = when (networkMode) {
                        CredentialStore.NETWORK_DIRECT -> "强制直连"
                        CredentialStore.NETWORK_VPN -> "强制 WebVPN"
                        else -> "自动检测"
                    },
                    options = listOf(
                        "自动检测" to CredentialStore.NETWORK_AUTO,
                        "强制直连" to CredentialStore.NETWORK_DIRECT,
                        "强制 WebVPN" to CredentialStore.NETWORK_VPN
                    ),
                    selected = networkMode,
                    onSelect = {
                        networkMode = it
                        credentialStore.networkMode = it
                    }
                )
            }

            SettingsGroupHeader("数据")
            SettingsCard {
                SettingsInfoRow(
                    icon = Icons.Default.Storage,
                    iconColor = MiuixTheme.colorScheme.primaryVariant,
                    title = "缓存大小",
                    subtitle = cacheSizeText
                )
                SettingsClickRow(
                    icon = Icons.Default.DeleteSweep,
                    iconColor = MiuixTheme.colorScheme.error,
                    title = "清除缓存",
                    subtitle = "清除临时文件和图片缓存，不影响登录状态与下载文件",
                    onClick = { showClearCacheDialog = true }
                )
                SettingsPathRow(
                    icon = Icons.Default.Folder,
                    iconColor = MiuixTheme.colorScheme.primaryVariant,
                    title = "LMS 下载位置",
                    path = lmsDownloadDir
                )
                SettingsPathRow(
                    icon = Icons.Default.Folder,
                    iconColor = MiuixTheme.colorScheme.secondary,
                    title = "课堂回放下载位置",
                    path = replayDownloadDir
                )
            }

            SettingsGroupHeader("更新")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Default.SystemUpdate,
                    iconColor = MiuixTheme.colorScheme.primaryVariant,
                    title = "启动时检查更新",
                    subtitle = "打开 App 时自动检查新版本",
                    checked = autoCheckUpdate,
                    onCheckedChange = {
                        autoCheckUpdate = it
                        credentialStore.autoCheckUpdate = it
                    }
                )
                SettingsRadioRow(
                    icon = Icons.Default.SettingsSuggest,
                    iconColor = MiuixTheme.colorScheme.secondary,
                    title = "更新渠道",
                    subtitle = if (updateChannel == CredentialStore.CHANNEL_BETA) "测试版" else "稳定版",
                    options = listOf(
                        "稳定版" to CredentialStore.CHANNEL_STABLE,
                        "测试版" to CredentialStore.CHANNEL_BETA
                    ),
                    selected = updateChannel,
                    onSelect = {
                        updateChannel = it
                        credentialStore.updateChannel = it
                    }
                )
            }

            SettingsGroupHeader("关于")
            SettingsCard {
                SettingsInfoRow(
                    icon = Icons.Default.Info,
                    iconColor = MiuixTheme.colorScheme.primary,
                    title = "版本号",
                    subtitle = versionText
                )
                SettingsClickRow(
                    icon = Icons.Default.History,
                    iconColor = MiuixTheme.colorScheme.primaryVariant,
                    title = "更新日志",
                    subtitle = "查看历史版本变化",
                    onClick = { showChangelog = true }
                )
                SettingsClickRow(
                    icon = Icons.Default.OpenInBrowser,
                    iconColor = MiuixTheme.colorScheme.secondary,
                    title = "项目主页",
                    subtitle = "GitHub · yeliqin666/xjtu-toolbox-android",
                    onClick = { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android") }
                )
                SettingsClickRow(
                    icon = Icons.Default.Feedback,
                    iconColor = MiuixTheme.colorScheme.primaryVariant,
                    title = "反馈建议",
                    subtitle = "提交 GitHub Issue",
                    onClick = { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android/issues") }
                )
                SettingsClickRow(
                    icon = Icons.Default.Description,
                    iconColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    title = "用户协议与隐私政策",
                    subtitle = "",
                    onClick = { showEula = true }
                )
            }

            SettingsGroupHeader("致谢")
            SettingsCard {
                SettingsClickRow(
                    icon = Icons.Default.Info,
                    iconColor = MiuixTheme.colorScheme.primary,
                    title = "XJTUToolBox by yan-xiaoo",
                    subtitle = "初代工具箱项目",
                    onClick = { uriHandler.openUri("https://github.com/yan-xiaoo/XJTUToolBox") }
                )
            }

            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (showClearCacheDialog) {
        SuperDialog(
            show = remember { mutableStateOf(true) }.also { it.value = showClearCacheDialog },
            title = "清除缓存",
            summary = "将清除约 $cacheSizeText 的临时缓存，不会影响登录状态和下载文件。",
            onDismissRequest = { showClearCacheDialog = false }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { showClearCacheDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        showClearCacheDialog = false
                        scope.launch(Dispatchers.IO) {
                            val cleared = runCatching {
                                context.cacheDir.deleteRecursively()
                                context.cacheDir.mkdirs()
                            }.isSuccess
                            withContext(Dispatchers.Main) {
                                if (cleared) {
                                    cacheSizeText = "0 B"
                                    Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "清除失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认清除")
                }
            }
        }
    }

    if (showChangelog) {
        ChangelogSheet(
            show = remember { mutableStateOf(true) }.also { it.value = showChangelog },
            onDismiss = { showChangelog = false }
        )
    }

    if (showEula) {
        EulaSheet(
            show = remember { mutableStateOf(true) }.also { it.value = showEula },
            onDismiss = { showEula = false }
        )
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 36.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingsRadioRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        SettingsBaseRow(
            icon = icon,
            iconColor = iconColor,
            title = title,
            subtitle = subtitle,
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
                )
            }
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 70.dp, end = 20.dp, bottom = 8.dp)) {
                options.forEach { (label, value) ->
                    val isSelected = selected == value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            modifier = Modifier.size(20.dp),
                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline.copy(alpha = 0.28f)
                        ) {
                            if (isSelected) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Surface(
                                        shape = CircleShape,
                                        modifier = Modifier.size(10.dp),
                                        color = MiuixTheme.colorScheme.onPrimary
                                    ) {}
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.body2,
                            color = if (isSelected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
        SettingsDivider()
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsBaseRow(
        icon = icon,
        iconColor = iconColor,
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
    SettingsDivider()
}

@Composable
private fun SettingsClickRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    SettingsBaseRow(
        icon = icon,
        iconColor = iconColor,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
            )
        }
    )
    SettingsDivider()
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    allowLongSubtitle: Boolean = false
) {
    SettingsBaseRow(
        icon = icon,
        iconColor = iconColor,
        title = title,
        subtitle = subtitle,
        allowLongSubtitle = allowLongSubtitle
    )
    SettingsDivider()
}

@Composable
private fun SettingsPathRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    path: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = path,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    SettingsDivider()
}

@Composable
private fun SettingsBaseRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    allowLongSubtitle: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (trailing == null) 0.dp else 12.dp)
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = if (allowLongSubtitle) 3 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}

@Composable
private fun ChangelogSheet(show: MutableState<Boolean>, onDismiss: () -> Unit) {
    BackHandler(enabled = show.value) { onDismiss() }
    SuperBottomSheet(
        show = show,
        title = "更新日志",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            changelogItems().forEach { entry ->
                Text(
                    text = entry.version,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                entry.items.forEach { item ->
                    Text(
                        text = item,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                SettingsDivider()
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("知道了")
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun EulaSheet(show: MutableState<Boolean>, onDismiss: () -> Unit) {
    BackHandler(enabled = show.value) { onDismiss() }
    SuperBottomSheet(
        show = show,
        title = "用户协议与隐私政策",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text("用户协议", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "本应用仅用于个人学习和校园信息查询。请遵守西安交通大学信息系统使用规定，不要使用本应用进行任何违规操作。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(16.dp))
            Text("隐私政策", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "登录凭据仅在本地加密存储。应用直接访问学校官方系统，不会向第三方服务器上传密码或个人数据。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("知道了")
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private fun tabLabel(value: String): String = when (value) {
    CredentialStore.TAB_COURSES -> "日程"
    CredentialStore.TAB_TOOLS -> "工具"
    CredentialStore.TAB_PROFILE -> "我的"
    else -> "首页"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

private data class ChangelogEntry(
    val version: String,
    val items: List<String>
)

private fun changelogItems(): List<ChangelogEntry> = listOf(
    ChangelogEntry(
        version = "v${BuildConfig.VERSION_NAME}",
        items = listOf(
            "修复设置页文案显示和长路径布局问题",
            "统一设置页与应用二级页面的顶部栏和卡片风格"
        )
    ),
    ChangelogEntry(
        version = "v3.2.0",
        items = listOf(
            "新增假期日历与日程体验优化",
            "完善登录和 WebVPN 相关流程"
        )
    ),
    ChangelogEntry(
        version = "v3.1.0",
        items = listOf(
            "新增电子教材中心、NeoSchool 和校园卡新平台支持",
            "优化课程、资源与下载相关体验"
        )
    )
)
