package com.xjtu.toolbox.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════
//  SettingsScreen — 独立设置页
// ══════════════════════════════════════════

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
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // ── 当前设置状态（直接从 CredentialStore 读取，修改即写回） ──
    var navBarStyle by remember { mutableStateOf(credentialStore.navBarStyle) }
    var darkMode by remember { mutableStateOf(credentialStore.darkMode) }
    var defaultTab by remember { mutableStateOf(credentialStore.defaultTab) }
    var networkMode by remember { mutableStateOf(credentialStore.networkMode) }
    var autoCheckUpdate by remember { mutableStateOf(credentialStore.autoCheckUpdate) }
    var updateChannel by remember { mutableStateOf(credentialStore.updateChannel) }

    // 缓存大小
    var cacheSizeText by remember { mutableStateOf("计算中…") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = context.cacheDir
                val size = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                cacheSizeText = formatFileSize(size)
            } catch (_: Exception) {
                cacheSizeText = "无法获取"
            }
        }
    }

    // 版本号
    val versionText = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // 更新日志弹窗状态（定义在外层，供下方 ChangelogSheet 使用）
    var showChangelog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MiuixTheme.colorScheme.background)
    ) {
        // ── 外观 ──
        SettingsGroupHeader("外观")
        SettingsCard {
            // 深色模式
            SettingsRadioRow(
                icon = Icons.Default.DarkMode,
                iconColor = MiuixTheme.colorScheme.primary,
                title = "深色模式",
                subtitle = when (darkMode) {
                    "light" -> "始终浅色"
                    "dark" -> "始终深色"
                    else -> "跟随系统"
                },
                options = listOf(
                    "跟随系统" to "system",
                    "始终浅色" to "light",
                    "始终深色" to "dark"
                ),
                selected = darkMode,
                onSelect = { v ->
                    darkMode = v
                    credentialStore.darkMode = v
                    onDarkModeChanged(v)
                }
            )
            // 底栏风格
            SettingsRadioRow(
                icon = Icons.Default.SpaceBar,
                iconColor = MiuixTheme.colorScheme.primaryVariant,
                title = "底栏风格",
                subtitle = if (navBarStyle == "floating") "悬浮胶囊" else "经典底栏",
                options = listOf(
                    "悬浮胶囊" to "floating",
                    "经典底栏" to "classic"
                ),
                selected = navBarStyle,
                onSelect = { v ->
                    navBarStyle = v
                    credentialStore.navBarStyle = v
                    onNavBarStyleChanged(v)
                }
            )
            // 默认启动 Tab
            SettingsRadioRow(
                icon = Icons.Default.Tab,
                iconColor = MiuixTheme.colorScheme.secondary,
                title = "默认启动 Tab",
                subtitle = when (defaultTab) {
                    "HOME" -> "首页"
                    "COURSES" -> "日程"
                    "TOOLS" -> "工具"
                    "PROFILE" -> "我的"
                    else -> "首页"
                },
                options = listOf(
                    "首页" to "HOME",
                    "日程" to "COURSES",
                    "工具" to "TOOLS",
                    "我的" to "PROFILE"
                ),
                selected = defaultTab,
                onSelect = { v ->
                    defaultTab = v
                    credentialStore.defaultTab = v
                    onDefaultTabChanged(v)
                }
            )
        }

        // ── 网络 ──
        SettingsGroupHeader("网络")
        SettingsCard {
            SettingsRadioRow(
                icon = Icons.Default.Wifi,
                iconColor = MiuixTheme.colorScheme.primary,
                title = "连接模式",
                subtitle = when (networkMode) {
                    "direct" -> "强制直连"
                    "vpn" -> "强制 WebVPN"
                    else -> "自动检测"
                },
                options = listOf(
                    "自动检测" to "auto",
                    "强制直连" to "direct",
                    "强制 WebVPN" to "vpn"
                ),
                selected = networkMode,
                onSelect = { v ->
                    networkMode = v
                    credentialStore.networkMode = v
                }
            )
        }

        // ── 数据 ──
        SettingsGroupHeader("数据")
        SettingsCard {
            // 缓存大小
            SettingsInfoRow(
                icon = Icons.Default.Storage,
                iconColor = MiuixTheme.colorScheme.primaryVariant,
                title = "缓存大小",
                subtitle = cacheSizeText
            )
            // 清除缓存
            var showClearCacheDialog by remember { mutableStateOf(false) }
            SettingsClickRow(
                icon = Icons.Default.DeleteSweep,
                iconColor = MiuixTheme.colorScheme.error,
                title = "清除缓存",
                subtitle = "清除所有临时文件和图片缓存",
                onClick = { showClearCacheDialog = true }
            )
            if (showClearCacheDialog) {
                SuperDialog(
                    show = remember { mutableStateOf(true) }.also { it.value = showClearCacheDialog },
                    title = "清除缓存",
                    summary = "将清除所有临时文件（约 $cacheSizeText），不会影响登录状态和下载文件。",
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
                                    try {
                                        context.cacheDir.deleteRecursively()
                                        context.cacheDir.mkdirs()
                                        withContext(Dispatchers.Main) {
                                            cacheSizeText = "0 B"
                                            Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (_: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "清除失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("确认清除") }
                    }
                }
            }
            // LMS 下载位置
            val downloadDir = remember {
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ).resolve("岱宗盒子").absolutePath
            }
            SettingsInfoRow(
                icon = Icons.Default.Folder,
                iconColor = MiuixTheme.colorScheme.primaryVariant,
                title = "LMS 下载位置",
                subtitle = downloadDir
            )
        }

        // ── 更新 ──
        SettingsGroupHeader("更新")
        SettingsCard {
            // 启动时检查更新
            SettingsSwitchRow(
                icon = Icons.Default.SystemUpdate,
                iconColor = MiuixTheme.colorScheme.primaryVariant,
                title = "启动时检查更新",
                subtitle = "打开 App 时自动检查新版本",
                checked = autoCheckUpdate,
                onCheckedChange = { v ->
                    autoCheckUpdate = v
                    credentialStore.autoCheckUpdate = v
                }
            )
            // 更新渠道
            SettingsRadioRow(
                icon = Icons.Default.SettingsSuggest,
                iconColor = MiuixTheme.colorScheme.secondary,
                title = "更新渠道",
                subtitle = if (updateChannel == "beta") "测试版" else "稳定版",
                options = listOf(
                    "稳定版" to "stable",
                    "测试版" to "beta"
                ),
                selected = updateChannel,
                onSelect = { v ->
                    updateChannel = v
                    credentialStore.updateChannel = v
                }
            )
        }

        // ── 关于 ──
        SettingsGroupHeader("关于")
        SettingsCard {
            // 版本号（长按复制）
            var versionPressed by remember { mutableStateOf(false) }
            SettingsClickRow(
                icon = Icons.Default.Info,
                iconColor = MiuixTheme.colorScheme.primary,
                title = "版本号",
                subtitle = versionText,
                onClick = {
                    // 长按复制
                }
            )
            // 更新日志
            SettingsClickRow(
                icon = Icons.Default.History,
                iconColor = MiuixTheme.colorScheme.primaryVariant,
                title = "更新日志",
                subtitle = "查看历史版本变化",
                onClick = { showChangelog = true }
            )
            // 项目主页
            SettingsClickRow(
                icon = Icons.Default.OpenInBrowser,
                iconColor = MiuixTheme.colorScheme.secondary,
                title = "项目主页",
                subtitle = "GitHub · yeliqin666/xjtu-toolbox-android",
                onClick = { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android") }
            )
            // 反馈建议
            SettingsClickRow(
                icon = Icons.Default.Feedback,
                iconColor = MiuixTheme.colorScheme.primaryVariant,
                title = "反馈建议",
                subtitle = "提交 GitHub Issue",
                onClick = { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android/issues") }
            )
            // 用户协议
            var showEula by remember { mutableStateOf(false) }
            SettingsClickRow(
                icon = Icons.Default.Description,
                iconColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                title = "用户协议 & 隐私政策",
                subtitle = "",
                onClick = { showEula = true }
            )
            if (showEula) {
                EulaSheet(show = remember { mutableStateOf(true) }.also { it.value = showEula }, onDismiss = { showEula = false })
            }
        }

        // ── 更新日志 SuperBottomSheet ──
        if (showChangelog) {
            ChangelogSheet(
                show = remember { mutableStateOf(true) }.also { it.value = showChangelog },
                onDismiss = { showChangelog = false }
            )
        }

        // ── 致谢 ──
        SettingsGroupHeader("致谢")
        SettingsCard {
            SettingsClickRow(
                icon = Icons.Default.Favorite,
                iconColor = MiuixTheme.colorScheme.error,
                title = "XJTUToolBox by yan-xiaoo",
                subtitle = "初代工具箱开发者",
                onClick = { uriHandler.openUri("https://github.com/yan-xiaoo/XJTUToolBox") }
            )
        }

        Spacer(Modifier.height(32.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ══════════════════════════════════════════
//  设置页复用组件
// ══════════════════════════════════════════

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        title,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 70.dp, end = 20.dp, bottom = 8.dp)) {
                options.forEach { (label, value) ->
                    val isSelected = selected == value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                expanded = false
                            }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            modifier = Modifier.size(20.dp),
                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
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
                            label,
                            style = MiuixTheme.textStyles.body2,
                            color = if (isSelected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
        if (options.isNotEmpty()) {
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        top.yukonga.miuix.kmp.basic.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}

@Composable
private fun SettingsClickRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
    }
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}

@Composable
private fun SettingsInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.5f))
        Text(
            subtitle,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}

// ══════════════════════════════════════════
//  ChangelogSheet — 更新日志弹窗
// ══════════════════════════════════════════

@Composable
private fun ChangelogSheet(show: MutableState<Boolean>, onDismiss: () -> Unit) {
    val changelogs = remember { getAllChangelogs() }
    // 按版本排序（新→旧）
    val sortedVersions = changelogs.keys.sortedWith(compareByDescending { version ->
        version.split(".").map { it.toIntOrNull() ?: 0 }.let {
            (it.getOrElse(0) { 0 } * 1000000) + (it.getOrElse(1) { 0 } * 1000) + it.getOrElse(2) { 0 }
        }
    })

    androidx.activity.compose.BackHandler(enabled = show.value) { onDismiss() }
    SuperBottomSheet(
        show = show,
        title = "更新日志",
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            sortedVersions.forEach { version ->
                val changelog = changelogs[version] ?: return@forEach
                Text(
                    "v$version",
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                changelog.items.forEach { (emoji, text) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(emoji, style = MiuixTheme.textStyles.body2)
                        Spacer(Modifier.width(8.dp))
                        Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    }
                }
                if (changelog.issues.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已知问题",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.error
                    )
                    changelog.issues.forEach { issue ->
                        Row(Modifier.padding(vertical = 1.dp)) {
                            Text("•", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text(issue, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 8.dp),
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text("知道了") }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// ══════════════════════════════════════════
//  辅助方法
// ══════════════════════════════════════════

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

/**
 * 获取所有版本的更新日志（从 MainActivity 中 CHANGELOGS 复制到此处，
 * 作为 SettingsScreen 内的独立副本，保持可维护性）
 */
private fun getAllChangelogs(): Map<String, VersionChangelog> = mapOf(
    "2.3.2" to VersionChangelog(
        items = listOf(
            "🎉" to "正式版来了！感谢参与内测的山东老乡！",
            "🔐" to "接入学工系统，可查看详细信息。",
            "📸" to "新增成绩单下载，绕开限制；成绩查询纳入未评教成绩",
            "💳" to "图书馆智能座位推荐、地图选座V2。",
            "🏠" to "UI改版，使用MIUIX开源的HyperOS设计语言。",
            "👍" to "大量Bug修复与人性化改进！"
        ),
        issues = listOf("图书馆定时抢座功能待修复", "通知推送功能有待优化", "校园卡登录偶尔失败（教务 Token 获取）")
    ),
    "2.5.0" to VersionChangelog(
        items = listOf(
            "🎓" to "新增课程回放功能（教学平台 TronClass）",
            "🏟️" to "新增体育场馆预订",
            "📜" to "用户协议更新",
            "📚" to "图书馆状态优化",
            "🔄" to "视频播放器修复",
            "👍" to "大量UI优化与Bug修复"
        ),
        issues = listOf("通知推送功能有待优化")
    ),
    "2.5.1" to VersionChangelog(
        items = listOf("🔙" to "修复所有界面按返回直接回桌面的严重Bug", "🧹" to "移除多余的 NavigationEvent 依赖")
    ),
    "2.6.0" to VersionChangelog(
        items = listOf(
            "📖" to "新增思源学堂（LMS）功能：课程、作业、课件、课堂回放",
            "📝" to "作业详情：查看提交记录、评分、教师评语",
            "🎬" to "课堂回放：支持多机位视频下载",
            "📎" to "课件附件：一键下载课程资料",
            "👍" to "UI 优化与多处细节改进"
        )
    ),
    "2.8.0" to VersionChangelog(
        items = listOf(
            "💳" to "新增校园卡桌面小组件（4×2）：余额、今日消费及早/午/晚三餐明细",
            "🔄" to "应用内更新：直接下载并安装新版 APK（基于 Gitee Releases）",
            "🗓️" to "新增校历",
            "🐛" to "修复所有小组件崩溃/无法添加问题（RemoteViews 兼容性）",
            "👤" to "边边角角修复与优化"
        )
    ),
    "2.7.1" to VersionChangelog(
        items = listOf(
            "🧩" to "新增日程桌面小组件（2×2 / 4×2 两种规格，支持当日安排一览）",
            "🐛" to "修复日程小组件布局与数据加载问题",
            "🔏" to "APK 签名由 v2 升级为 v2+v3，增强安全性与支持未来密钥轮换"
        ),
        issues = listOf("入馆后可能错误显示「取消预约」按钮")
    ),
    "2.7.0" to VersionChangelog(
        items = listOf(
            "🔍" to "新增全校课程查询：按课程名、教师、院系等多维度检索",
            "🏠" to "首页/教务/工具 Tab 重新分区，更加合理",
            "👤" to "\"我的\" 页大幅重构：全新关于区域、开源社区入口、开发计划",
            "🎬" to "修复思源学堂视频播放闪退（横屏 Activity 重建问题）",
            "📊" to "成绩查询页新增免责声明提示",
            "🐛" to "修复全校课程 API 解析异常导致的闪退",
            "👍" to "出勤记录文案修正、多处 UI 细节优化"
        )
    ),
    "2.8.1" to VersionChangelog(
        items = listOf(
            "🏟️" to "新增场馆收藏功能，支持收藏常用场馆",
            "✨" to "支持双击场馆卡片快速收藏/取消收藏",
            "🎬" to "新增收藏动画与提示反馈，交互更顺滑",
            "📌" to "场馆列表支持按收藏状态优先排序",
            "📝" to "补充版本号与更新日志，完善发版信息"
        )
    ),
    "3.1.0" to VersionChangelog(
        items = listOf(
            "💳" to "校园卡迁移至新平台 ncard.xjtu.edu.cn，JWT 认证替代旧接口，余额与流水恢复正常",
            "📚" to "新增电子教材中心：搜索书目、在线阅览与 PDF 下载",
            "🎓" to "新增 NeoSchool（拔尖计划）：课程列表、章节、课件与资源下载"
        )
    ),
    "3.0.2" to VersionChangelog(
        items = listOf("🗓️" to "添加了日程功能")
    ),
    "3.0.1" to VersionChangelog(
        items = listOf("🎬" to "新增课程回放下载功能")
    ),
    "3.0" to VersionChangelog(
        items = listOf(
            "🧭" to "导航结构升级：教务 Tab 重构为日程 Tab，首页/小组件统一直达\"我的日程\"",
            "🗓️" to "日程页重构：支持嵌入式无边界头部，学期/Tab 交互与刷新状态提示优化",
            "🧩" to "小组件体系稳定化：日程与校园卡回退 RemoteViews 链路，兼容更多 OEM 桌面",
            "💳" to "校园卡小组件 2×2 紧凑重排，金额显示与三餐布局优化，减少溢出与加载异常",
            "✅" to "日程链路修复：从小组件进入后登录恢复可自动在线刷新，不再长期停留离线缓存",
            "🛠️" to "评教、成绩、场馆、主题与多处页面细节修复，整体体验与稳定性提升"
        ),
        issues = listOf("少数桌面宿主对旧小组件实例缓存较重，升级后建议删除并重新添加校园卡/日程小组件")
    )
)

// ══════════════════════════════════════════
//  EulaSheet — 用户协议弹窗
// ══════════════════════════════════════════

@Composable
private fun EulaSheet(show: MutableState<Boolean>, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = show.value) { onDismiss() }
    SuperBottomSheet(
        show = show,
        title = "用户协议 & 隐私政策",
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "用户协议",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "欢迎使用岱宗盒子（XJTU ToolBox）。本应用是西安交通大学校园工具箱，直接调用教务系统、图书馆、校园卡等官方接口，不依赖任何三方服务器。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "使用条款",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            val terms = listOf(
                "本应用仅用于个人学习与研究目的，不得用于商业用途。",
                "用户使用本应用登录校园系统时，密码仅在本地加密后发送至学校 CAS 服务器，应用不会上传密码至其他服务器。",
                "用户应遵守西安交通大学的信息系统使用规定，不得利用本应用进行任何违规操作。",
                "本应用对网络请求进行了缓存和复用优化，所有数据均存储在用户设备本地。",
                "开发者不对因使用本应用而导致的任何直接或间接损失承担责任。"
            )
            terms.forEachIndexed { idx, term ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("${idx + 1}.", style = MiuixTheme.textStyles.body2, modifier = Modifier.width(24.dp))
                    Text(term, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "隐私政策",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "本应用不会收集用户的任何个人信息。所有登录凭据均使用 Android Keystore 加密存储于本地设备。应用内置的更新检查功能仅查询 Gitee Releases API 获取最新版本信息，不传输用户数据。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text("知道了") }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private data class VersionChangelog(
    val items: List<Pair<String, String>>,
    val issues: List<String> = emptyList()
)
