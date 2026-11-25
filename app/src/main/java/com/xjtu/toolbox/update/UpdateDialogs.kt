package com.xjtu.toolbox.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.ui.markdown.GithubMarkdown
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

// ══════════════════════════════════════════
//  本地 What's New 弹窗 —— 堆叠展示版
// ══════════════════════════════════════════

@Composable
internal fun UpdateNoticeDialog(
    entries: List<Pair<String, VersionChangelog>>,
    show: MutableState<Boolean>,
    fromVersion: String? = null,
    onDismiss: () -> Unit
) {
    if (entries.isEmpty()) return
    BackHandler(enabled = show.value) { onDismiss() }
    val latest = entries.first().first
    // 用 WindowBottomSheet 而不是 OverlayBottomSheet：本弹窗由 AppNavigation 直接调用，
    // 那一层**没有任何 Scaffold**（NavHost 也在同层），而 Overlay* 要靠 Scaffold 提供的
    // LocalDialogStates 宿主才会被渲染 —— 否则注册进空列表，静默不显示。
    // 后果是「发现新版本」和「更新说明」用户根本看不到。改用自带独立 Window 的变体。
    // 不设表头：版本号只在题记下的落款里出现一次
    WindowBottomSheet(
        show = show.value,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // 卷首：最新那一版的 🪶 引言单独放大，像一本书的题记
            ChangelogEpigraph(
                quote = entries.first().second.quote,
                caption = buildString {
                    append("岱宗盒子 v").append(latest)
                    if (entries.size > 1) append(" · 含 ").append(entries.size).append(" 个版本")
                },
            )
            entries.forEachIndexed { index, (version, changelog) ->
                if (entries.size > 1) {
                    // 叠了几个版本时每版一个小标题；旧版本的题记缩成一行小字跟在标题下
                    ChangelogVersionHeader(version, quote = changelog.quote.takeIf { index > 0 }, first = index == 0)
                }
                changelog.notes.forEach { (emoji, text) -> ChangelogLine(emoji, text) }
                if (changelog.issues.isNotEmpty()) ChangelogIssues(changelog.issues)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("知道了", color = MiuixTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** 每版第一条 🪶 是题记（一句引言），不算改动条目。 */
private const val QUOTE_MARK = "🪶"

private val VersionChangelog.quote: String? get() = items.firstOrNull { it.first == QUOTE_MARK }?.second

private val VersionChangelog.notes: List<Pair<String, String>> get() = items.filterNot { it.first == QUOTE_MARK }

/** 两句的引言在逗号处断成两行，像诗；断不匀（一边过长）就让它自然换行。 */
private fun verse(quote: String): String {
    val cut = quote.indexOfFirst { it == '，' || it == '；' }
    if (cut <= 0 || cut == quote.lastIndex) return quote
    val head = quote.substring(0, cut + 1)
    val tail = quote.substring(cut + 1)
    return if (head.length <= 14 && tail.length <= 14) "$head\n$tail" else quote
}

/** 卷首题记：大号衬线字、居中，进场时缓缓浮起。 */
@Composable
private fun ChangelogEpigraph(quote: String?, caption: String) {
    val cs = MiuixTheme.colorScheme
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(900), label = "epigraph-alpha")
    val rise by animateFloatAsState(if (shown) 0f else 14f, tween(900), label = "epigraph-rise")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 24.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationY = rise * density
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (quote != null) {
            Text(
                "“",
                fontFamily = FontFamily.Serif,
                fontSize = 54.sp,
                lineHeight = 54.sp,
                color = cs.primary.copy(alpha = 0.32f),
                modifier = Modifier.height(34.dp),
            )
            Text(
                verse(quote),
                fontFamily = FontFamily.Serif,
                fontSize = 21.sp,
                lineHeight = 36.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = cs.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(18.dp))
        }
        Box(
            Modifier
                .width(28.dp)
                .height(1.5.dp)
                .background(cs.primary.copy(alpha = 0.55f), RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            caption,
            style = MiuixTheme.textStyles.footnote1,
            letterSpacing = 1.sp,
            color = cs.onSurfaceVariantSummary,
        )
    }
}

/** 多版本叠在一起时的版本小标题：版本号 + 一道细线。 */
@Composable
private fun ChangelogVersionHeader(version: String, quote: String?, first: Boolean) {
    val cs = MiuixTheme.colorScheme
    if (!first) Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(
            "v$version",
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.Bold,
            color = cs.primary,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(cs.outline.copy(alpha = 0.22f)))
    }
    if (quote != null) {
        Text(
            quote,
            fontFamily = FontFamily.Serif,
            style = MiuixTheme.textStyles.footnote1,
            letterSpacing = 1.sp,
            color = cs.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/** 一条改动：emoji 落在浅色圆底里，正文留足行距。 */
@Composable
private fun ChangelogLine(emoji: String, text: String) {
    val cs = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).background(cs.primary.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            lineHeight = 22.sp,
            color = cs.onSurface,
            modifier = Modifier.weight(1f).padding(top = 5.dp),
        )
    }
}

/** 已知问题：浅红底的一小块，与改动条目分开。 */
@Composable
private fun ChangelogIssues(issues: List<String>) {
    val cs = MiuixTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(cs.error.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("已知问题", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, color = cs.error)
        issues.forEach { issue ->
            Row(Modifier.padding(top = 6.dp)) {
                Text("·", style = MiuixTheme.textStyles.body2, color = cs.error)
                Spacer(Modifier.width(8.dp))
                Text(issue, style = MiuixTheme.textStyles.body2, lineHeight = 21.sp, color = cs.onSurfaceVariantSummary)
            }
        }
    }
}

// ══════════════════════════════════════════
//  自动更新弹窗（启动时后台检查到新版本时弹出）
// ══════════════════════════════════════════

@Composable
fun AutoUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val version = updateInfo.version
    val body = updateInfo.notes
    val downloadUrl = updateInfo.downloadUrl
    val releaseUrl = updateInfo.releaseUrl
    val channelLabel = updateInfo.channelLabel
    val isPreview = updateInfo.isPreview

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
            val published = updateInfo.publishedAtMillis.takeIf { it > 0 }?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            val meta = listOfNotNull(
                channelLabel.takeIf { it.isNotBlank() }?.let { "来源：$it" },
                published?.let { "发布于 $it" },
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
            }
            if (body.isNotBlank()) {
                GithubMarkdown(body, textStyle = MiuixTheme.textStyles.body2)
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
                                if (AppUpdater.canInstallPackages(context)) {
                                    AppUpdater.install(context, readyApk)
                                    show.value = false
                                    onDismiss()
                                } else {
                                    AppUpdater.requestInstallPermission(context)
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
                                    val apkFile = AppUpdater.download(
                                        context,
                                        updateInfo
                                    ) { progress ->
                                        scope.launch { downloadProgress = progress }
                                    }
                                    downloadedApk = apkFile
                                    isDownloading = false
                                    if (AppUpdater.canInstallPackages(context)) {
                                        AppUpdater.install(context, apkFile)
                                        show.value = false
                                        onDismiss()
                                    } else {
                                        AppUpdater.requestInstallPermission(context)
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
                                        com.xjtu.toolbox.error.FriendlyError.of(e, "更新"),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(if (downloadedApk != null) "继续安装" else "下载并安装", color = MiuixTheme.colorScheme.onPrimary)
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
                modifier = Modifier.fillMaxWidth(),
                colors = com.xjtu.toolbox.ui.components.appTonalTextButtonColors(),
            )
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
