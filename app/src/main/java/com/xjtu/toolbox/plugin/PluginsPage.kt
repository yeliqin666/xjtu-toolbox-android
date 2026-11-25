package com.xjtu.toolbox.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.SmallTitle
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 插件：安装、启用、允许小岱调用、打开页面、卸载。
 *
 * 插件是进程内代码，安装时把"同权限"这件事说清楚，不做逐次授权（也挡不住）。
 */
@Composable
internal fun PluginsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()

    var pending by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.inspect(uri) } }
                .onSuccess { pending = it }
                .onFailure { message = it.message ?: "读取插件失败" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { manager.refresh() }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "插件",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                subtitle = "Android 上的进程内扩展；只装你信任的插件",
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding.withoutTop(glass))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 玻璃顶栏悬在内容上方：不补这段，按钮会画到状态栏/顶栏底下点不到
            Spacer(Modifier.height(glassTop))
            message?.let {
                Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.padding(top = 6.dp))
            }
            TextButton(
                text = if (busy) "处理中…" else "安装插件 APK",
                onClick = {
                    picker.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                enabled = !busy,
            )
            Spacer(Modifier.padding(top = 8.dp))
            if (busy) {
                Text(
                    "正在校验插件签名与清单…",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (manager.records.isEmpty()) {
                Text(
                    "还没有安装插件。插件是带签名的 APK，不会被系统安装，只由本 App 加载。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            manager.records.forEach { record ->
                val broken = record.id in manager.brokenIds
                SmallTitle(record.manifest.name + " · " + record.versionName.ifBlank { "v${record.versionCode}" })
                CouiSettingsCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = record.manifest.name,
                                style = MiuixTheme.textStyles.title3,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = record.versionName.ifBlank { "v${record.versionCode}" },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (record.manifest.summary.isNotBlank()) {
                            Text(
                                text = record.manifest.summary,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                    SwitchPreference(
                        title = "启用",
                        summary = if (broken) "APK 丢失，请卸载后重装" else "关闭后页面和小岱工具都不可用",
                        checked = record.enabled && !broken,
                        enabled = !broken,
                        onCheckedChange = { on ->
                            scope.launch { manager.setEnabled(record.id, on) }
                        },
                    )
                    SwitchPreference(
                        title = "允许小岱调用",
                        summary = "把这个插件的工具列给小岱；插件代码本来就能在本机运行",
                        checked = record.toolsEnabled && !broken,
                        enabled = record.enabled && !broken,
                        onCheckedChange = { on ->
                            scope.launch { manager.setToolsEnabled(record.id, on) }
                        },
                    )
                    record.manifest.pages.forEach { page ->
                        ArrowPreference(
                            title = page.title.ifBlank { page.id },
                            summary = "打开插件页面",
                            enabled = record.enabled && !broken,
                            onClick = { PluginOverlay.open(record.id, page.id) },
                        )
                    }
                    ArrowPreference(
                        title = "卸载",
                        summary = "删除插件 APK 与数据目录",
                        onClick = {
                            scope.launch { manager.uninstall(record.id) }
                        },
                    )
                }
                Spacer(Modifier.padding(bottom = 6.dp))
            }

            // MIUIX 的 Overlay 必须挂在 Scaffold 内容里；放到 Scaffold 外面不显示
            val install = pending
            if (install != null) {
                OverlayDialog(
                    show = true,
                    title = if (install.isUpdate) "更新插件" else "安装插件",
                    summary = buildString {
                        append("${install.manifest.name}  v${install.manifest.version}（versionCode ${install.manifest.versionCode}）\n")
                        install.oldVersionName?.let { append("已安装 v$it → 本次更新\n") }
                        append("页面 ${install.manifest.pages.size} 个，工具 ${install.manifest.tools.size} 个\n")
                        append("签名 ${install.certSha256.take(16)}…\n\n")
                        append("插件会以与本 App 相同的权限运行，能读取你的账号、登录会话、本机数据并联网。只安装你信任来源的插件。")
                    },
                    onDismissRequest = {
                        manager.cancel(install)
                        pending = null
                    },
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "取消",
                            onClick = {
                                manager.cancel(install)
                                pending = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = if (install.isUpdate) "确认更新" else "确认安装",
                            onClick = {
                                pending = null
                                busy = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { manager.commit(install) } }
                                        .onSuccess {
                                            message = "已${if (install.isUpdate) "更新" else "安装"}：${install.manifest.name}"
                                        }
                                        .onFailure { message = it.message ?: "安装失败" }
                                    manager.refresh()
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
