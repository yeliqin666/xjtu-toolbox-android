package com.xjtu.toolbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.glassTopBar
import com.xjtu.toolbox.ui.glass.withoutTop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.*
import kotlinx.coroutines.launch

// ══════════════════════════════════════════
//  用户协议弹窗
// ══════════════════════════════════════════

@Composable
internal fun EulaScreen(onAccept: () -> Unit) {
    val scrollState = rememberScrollState()
    var canAccept by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 滚动到底部才可同意。
    //
    // 平板上协议一屏放得下，`maxValue` 永远是 0 —— 旧的「maxValue > 0」判断于是
    // 永远不解锁，同意按钮一直是灰的，新装用户直接卡在首启。
    // 内容一屏放得下时根本没有「滑到底」这回事，视为已读完即可。
    //
    // `viewportSize > 0` 是「已经量过一次」的信号：首帧布局前 canScrollForward
    // 也是 false，不先等布局的话手机上会一进来就解锁。
    val reachedEnd by remember {
        derivedStateOf {
            scrollState.viewportSize > 0 &&
                (!scrollState.canScrollForward || scrollState.value >= scrollState.maxValue - 50)
        }
    }
    LaunchedEffect(reachedEnd) { if (reachedEnd) canAccept = true }


    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = com.xjtu.toolbox.ui.glass.rememberPageGlass()
    Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "用户协议与免责声明",
                largeTitle = "用户协议与免责声明",
                color = com.xjtu.toolbox.ui.glass.glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        // 宽屏上一行正文横跨整个平板宽度没法读，限宽居中；手机窄于 720dp，布局不变。
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp + padding.glassTop(glass)))
            Text(
                "请仔细阅读以下条款。继续使用本应用即表示您同意以下全部内容。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(16.dp))

            com.xjtu.toolbox.legal.Eula.Body()

            Spacer(Modifier.height(16.dp))

            if (!canAccept) {
                Text(
                    "↓ 请阅读至底部后同意",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = onAccept,
                enabled = canAccept,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("我已阅读并同意")
            }

            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        }
    }
}

// ══════════════════════════════════════════
//  本地 What's New 弹窗 —— 堆叠展示版
// ══════════════════════════════════════════

@Composable
internal fun UpdateNoticeDialog(
    entries: List<Pair<String, com.xjtu.toolbox.util.VersionChangelog>>,
    show: MutableState<Boolean>,
    fromVersion: String? = null,
    onDismiss: () -> Unit
) {
    if (entries.isEmpty()) return
    BackHandler(enabled = show.value) { onDismiss() }
    val title = if (entries.size == 1) {
        "岱宗盒子 v${entries.first().first}"
    } else {
        "岱宗盒子 v${entries.first().first}（含 ${entries.size} 次更新）"
    }
    // 用 WindowBottomSheet 而不是 OverlayBottomSheet：本弹窗由 AppNavigation 直接调用，
    // 那一层**没有任何 Scaffold**（NavHost 也在同层），而 Overlay* 要靠 Scaffold 提供的
    // LocalDialogStates 宿主才会被渲染 —— 否则注册进空列表，静默不显示。
    // 后果是「发现新版本」和「更新说明」用户根本看不到。改用自带独立 Window 的变体。
    WindowBottomSheet(
        show = show.value,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (!fromVersion.isNullOrBlank()) {
                Text(
                    "从 v$fromVersion 升级到 v${BuildConfig.VERSION_NAME}，下面是这次跨版本包含的新内容。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
            }
            entries.forEachIndexed { index, (version, changelog) ->
                if (index > 0) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    text = "v$version",
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                changelog.items.forEach { (emoji, text) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(emoji, style = MiuixTheme.textStyles.body1)
                        Spacer(Modifier.width(8.dp))
                        Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    }
                }
                if (changelog.issues.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已知问题",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    changelog.issues.forEach { issue ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("•", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text(issue, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(text = "知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// ══════════════════════════════════════════
//  自动更新弹窗（启动时后台检查到新版本时弹出）
// ══════════════════════════════════════════

@Composable
fun AutoUpdateDialog(
    version: String,
    body: String,
    downloadUrl: String,
    releaseUrl: String,
    channelLabel: String = "",
    isPreview: Boolean = false,
    onDismiss: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedApk by remember { mutableStateOf<java.io.File?>(null) }

    BackHandler(enabled = show.value) {
        show.value = false
        onDismiss()
    }

    // 用 WindowBottomSheet 而不是 OverlayBottomSheet：本弹窗由 AppNavigation 直接调用，
    // 那一层**没有任何 Scaffold**（NavHost 也在同层），而 Overlay* 要靠 Scaffold 提供的
    // LocalDialogStates 宿主才会被渲染 —— 否则注册进空列表，静默不显示。
    // 后果是「发现新版本」和「更新说明」用户根本看不到。改用自带独立 Window 的变体。
    WindowBottomSheet(
        show = show.value,
        title = if (isPreview) "发现预览版 $version" else "发现新版本 v$version",
        onDismissRequest = {
            show.value = false
            onDismiss()
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (channelLabel.isNotBlank()) {
                Text(
                    "来源：$channelLabel",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
            }
            // Release body（Markdown changelog）
            if (body.isNotBlank()) {
                MarkdownReleaseNotes(body)
            }

            Spacer(Modifier.height(16.dp))

            // 下载按钮
            if (downloadUrl.isNotEmpty()) {
                if (isDownloading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            progress = downloadProgress,
                            size = 20.dp,
                            strokeWidth = 2.dp,
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = MiuixTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${(downloadProgress * 100).toInt()}%",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            val readyApk = downloadedApk
                            if (readyApk != null && readyApk.exists()) {
                                if (com.xjtu.toolbox.util.AppUpdater.canInstallPackages(context)) {
                                    com.xjtu.toolbox.util.AppUpdater.install(context, readyApk)
                                    show.value = false
                                    onDismiss()
                                } else {
                                    com.xjtu.toolbox.util.AppUpdater.requestInstallPermission(context)
                                    android.widget.Toast.makeText(
                                        context,
                                        "允许安装后，返回并点击“继续安装”",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                return@Button
                            }
                            isDownloading = true
                            downloadProgress = 0f
                            scope.launch {
                                try {
                                    val apkFile = com.xjtu.toolbox.util.AppUpdater.download(
                                        context,
                                        com.xjtu.toolbox.util.AppUpdateInfo(
                                            version = version,
                                            notes = body,
                                            downloadUrl = downloadUrl,
                                            releaseUrl = releaseUrl
                                        )
                                    ) { progress ->
                                        scope.launch { downloadProgress = progress }
                                    }
                                    downloadedApk = apkFile
                                    isDownloading = false
                                    if (com.xjtu.toolbox.util.AppUpdater.canInstallPackages(context)) {
                                        com.xjtu.toolbox.util.AppUpdater.install(context, apkFile)
                                        show.value = false
                                        onDismiss()
                                    } else {
                                        com.xjtu.toolbox.util.AppUpdater.requestInstallPermission(context)
                                        android.widget.Toast.makeText(
                                            context,
                                            "允许安装后，返回并点击“继续安装”",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    isDownloading = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "更新失败：${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (downloadedApk != null) "继续安装" else "下载并安装")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "稍后提醒",
                onClick = {
                    show.value = false
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun MarkdownReleaseNotes(markdown: String) {
    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line == "---") return@forEach
        val headingLevel = line.takeWhile { it == '#' }.length
        val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")
        val quote = line.startsWith("> ")
        val cleaned = line
            .removePrefix("#".repeat(headingLevel)).trim()
            .removePrefix("- ").removePrefix("* ").removePrefix("+ ")
            .removePrefix("> ")
            .replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")

        when {
            headingLevel > 0 -> Text(
                cleaned,
                style = if (headingLevel <= 2) MiuixTheme.textStyles.subtitle else MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 3.dp)
            )
            bullet -> Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", color = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(cleaned, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
            }
            quote -> Surface(
                shape = RoundedCornerShape(10.dp),
                color = MiuixTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) {
                Text(
                    cleaned,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
            else -> Text(
                cleaned,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
