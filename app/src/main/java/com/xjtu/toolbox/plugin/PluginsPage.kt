package com.xjtu.toolbox.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.DockedPageTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.tabContentPadding
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** web 插件徽章色。 */
private val WebBadgeColor = Color(0xFF3B82F6)

/** dex 插件徽章色。 */
private val DexBadgeColor = Color(0xFFF97316)

/**
 * 设置 → 插件：上面一条窄 navbar 分「已安装 / 在线市场」，点条目进详情页。
 *
 * 列表页只回答「装了什么、是什么类型、能不能用」；开关和卸载都收进详情页，免得装几个插件
 * 就是一整面开关墙。[demo] 为 true 时用假数据，方便看版式。
 */
@Composable
internal fun PluginsPage(onBack: () -> Unit, demo: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val records = if (demo) demoRecords() else manager.records
    val broken = if (demo) DemoBrokenIds else manager.brokenIds
    val detail = records.firstOrNull { it.id == detailId }

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

    if (!demo) LaunchedEffect(Unit) { manager.refresh() }

    // ── 详情页 ────────────────────────────────────────────────────────────
    if (detail != null) {
        DetailPage(
            record = detail,
            broken = detail.id in broken,
            onBack = { detailId = null },
            onOpenPage = { pageId -> PluginOverlay.open(detail.id, pageId) },
            onToggleEnabled = { on ->
                if (demo) message = "演示数据，不会真的改" else scope.launch { manager.setEnabled(detail.id, on) }
            },
            onToggleTools = { on ->
                if (demo) message = "演示数据，不会真的改" else scope.launch { manager.setToolsEnabled(detail.id, on) }
            },
            onUninstall = {
                detailId = null
                if (!demo) {
                    scope.launch {
                        manager.uninstall(detail.id)
                        manager.refresh()
                    }
                }
            },
        )
        return
    }

    // ── 列表页 ────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "插件",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                bottomContent = {
                    DockedPageTabs(
                        tabs = listOf("已安装", "在线市场"),
                        selectedTabIndex = tab,
                        onTabSelected = { tab = it },
                    )
                },
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding.withoutTop(glass))
                .padding(horizontal = 16.dp)
                .padding(top = glassTop, bottom = 24.dp),
        ) {
            if (tab == 0) {
                message?.let {
                    Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                TypeLegend()
                Spacer(Modifier.height(12.dp))

                if (records.isEmpty()) {
                    EmptyState(
                        title = "还没有装插件",
                        subtitle = "插件是一个 .zip 包：dex 类型在里面带 APK，web 类型只是打开一个网页。",
                    )
                } else {
                    records.forEach { record ->
                        PluginRow(
                            record = record,
                            broken = record.id in broken,
                            onClick = { detailId = record.id },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (busy) "正在校验插件包…" else "从文件安装插件…",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (busy) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) {
                                picker.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                        "*/*",
                                    ),
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            } else {
                EmptyState(
                    title = "在线市场还没开张",
                    subtitle = "以后这里会读一份索引 JSON，列可安装的插件、版本和签名指纹。现在先占个位。",
                )
            }
        }
    }

}

/** 两类插件的图例：徽章 + 一句话说明。dex 那条末尾是红的。 */
@Composable
private fun TypeLegend() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                TypeBadge(isWeb = true)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "在内置浏览器上运行，与 App 隔离，一般更安全。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                TypeBadge(isWeb = false)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "加载外部代码，功能、性能与界面都强于 web 插件，但与 App 拥有同等权限。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "务必确认插件来源可信！",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 类型徽章：web 蓝、dex 橙。 */
@Composable
private fun TypeBadge(isWeb: Boolean) {
    val color = if (isWeb) WebBadgeColor else DexBadgeColor
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (isWeb) "web" else "dex",
            style = MiuixTheme.textStyles.footnote2,
            color = color,
        )
    }
}

/** 列表里的一项：名字 + 版本 / 徽章 + 状态 / 三行简介。 */
@Composable
private fun PluginRow(record: PluginRecord, broken: Boolean, onClick: () -> Unit) {
    val status = when {
        broken -> "插件包丢失，卸载后重装"
        !record.enabled -> "已停用"
        record.toolsEnabled -> "已启用 · 允许小岱调用"
        else -> "已启用"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.manifest.name,
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.versionName.ifBlank { "v${record.versionCode}" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(isWeb = record.manifest.isWeb)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (broken) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
            if (record.manifest.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = record.manifest.summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 详情页：开关、页面入口、卸载。 */
@Composable
private fun DetailPage(
    record: PluginRecord,
    broken: Boolean,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleTools: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    val glass = rememberPageGlass()
    var confirm by remember { mutableStateOf(false) }
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = record.manifest.name,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding.withoutTop(glass))
                .padding(horizontal = 16.dp)
                .padding(top = glassTop, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(isWeb = record.manifest.isWeb)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.versionName.ifBlank { "v${record.versionCode}" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.id,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (record.manifest.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = record.manifest.summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(14.dp))

            CouiSettingsCard {
                SwitchPreference(
                    title = "启用",
                    summary = if (broken) "插件包丢失，请卸载后重装" else "关闭后页面和小岱工具都不可用",
                    checked = record.enabled && !broken,
                    enabled = !broken,
                    onCheckedChange = onToggleEnabled,
                )
                SwitchPreference(
                    title = "允许小岱调用",
                    summary = "把这个插件的工具列给小岱；插件代码本来就能在本机运行",
                    checked = record.toolsEnabled && !broken,
                    enabled = record.enabled && !broken,
                    onCheckedChange = onToggleTools,
                )
                record.manifest.pages.forEach { page ->
                    ArrowPreference(
                        title = page.title.ifBlank { page.id },
                        summary = "打开插件页面",
                        enabled = record.enabled && !broken,
                        onClick = { onOpenPage(page.id) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            CouiSettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { confirm = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "卸载插件",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "删除插件包与数据目录",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
        }

        if (confirm) {
            com.xjtu.toolbox.community.ConfirmDialog(
                title = "卸载「${record.manifest.name}」？",
                summary = "会删掉插件包和它自己的数据目录，这个插件的页面和小岱工具立刻失效。\n\n已经产生的本地数据不能恢复。",
                confirm = "卸载",
                onConfirm = {
                    confirm = false
                    onUninstall()
                },
                onDismiss = { confirm = false },
                danger = true,
            )
        }
    }
}

// ── 假数据：只为看版式 ──────────────────────────────────────────────────────

private val DemoBrokenIds: Set<String> = setOf("com.example.card.watch")

private fun demoRecord(
    id: String,
    name: String,
    summary: String,
    isWeb: Boolean,
    version: String = "1.0.0",
    versionCode: Int = 1,
    enabled: Boolean = true,
    tools: Int = 0,
    pages: Int = 1,
): PluginRecord = PluginRecord(
    id = id,
    versionCode = versionCode,
    versionName = version,
    apk = "$id/$versionCode.apk",
    certSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
    enabled = enabled,
    toolsEnabled = tools > 0,
    installedAt = 0L,
    manifest = PluginManifest(
        apiVersion = 2,
        id = id,
        name = name,
        summary = summary,
        type = if (isWeb) "web" else "dex",
        version = version,
        versionCode = versionCode,
        minAppVersionCode = 101,
        entryClass = "$id.Plugin",
        pages = (1..pages).map { PluginPageManifest("page$it", "页面 $it") },
        tools = (1..tools).map { PluginToolManifest("tool$it", JsonObject(emptyMap())) },
    ),
)

private fun demoRecords(): List<PluginRecord> = listOf(
    demoRecord(
        id = "com.xjtu.toolbox.plugin.sample",
        name = "示例插件",
        summary = "把课程、考试、成绩、图书馆座位和空教室整理到一页，顺手给小岱加两个只读工具，不碰登录会话也不上传数据",
        isWeb = false,
        version = "1.1.1",
        versionCode = 4,
        tools = 2,
    ),
    demoRecord(
        id = "com.example.library.web",
        name = "图书馆座位预约",
        summary = "直接在盒子里打开图书馆的预约页，登录状态跟浏览器分开，不和 App 共享任何东西。",
        isWeb = true,
        version = "2.0.3",
        versionCode = 11,
    ),
    demoRecord(
        id = "com.example.ics.export",
        name = "课程表导出成 ICS 日历文件的小工具",
        summary = "把本学期课表和考试安排导出成标准 ICS，可以导进系统日历、Outlook 或者任何支持订阅的日历应用。",
        isWeb = false,
        version = "1.0.0-beta.12",
        versionCode = 7,
        enabled = false,
        pages = 2,
        tools = 1,
    ),
    demoRecord(
        id = "com.example.card.watch",
        name = "校园卡余额提醒",
        summary = "每天早上八点查一次余额，低于 30 块就用通知栏提醒你去充。",
        isWeb = true,
        version = "0.9.1",
        versionCode = 3,
    ),
    demoRecord(
        id = "com.example.pickcourse.radar",
        name = "选课捡漏雷达（一直蹲在后台盯着退课，捡到就弹通知）",
        summary = "盯住你指定的几门课，有人退课就立刻在通知栏喊你，还能设置只在白天盯、只在某几周盯。",
        isWeb = false,
        version = "3.2.0",
        versionCode = 21,
        tools = 4,
        pages = 3,
    ),
)
