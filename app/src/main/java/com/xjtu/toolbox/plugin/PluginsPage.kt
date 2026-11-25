package com.xjtu.toolbox.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
import com.xjtu.toolbox.ui.components.AppChipTabRow
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.DockedPageTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.ReportScrollPosition
import com.xjtu.toolbox.ui.glass.glassSource
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** web 插件徽章色。 */
private val WebBadgeColor = Color(0xFF3B82F6)

/** dex 插件徽章色（要压过橙色的对比度，深色下也看得清）。 */
private val DexBadgeColor = Color(0xFFF97316)

/** 徽章固定宽度：两个徽章左边对齐、右边也齐，说明文字才不会跳。 */
private val BadgeWidth = 44.dp

/** 在线市场的分类筛选。索引里暂时只有一套；分类多了再加。 */
private enum class MarketFilter(val label: String) {
    All("全部"),
    Tool("工具"),
    Study("学习"),
    Life("生活"),
}

/**
 * 设置 → 插件：上面一条窄 navbar 分「已安装 / 在线市场」，右上角是插件设置。
 *
 * 列表页只回答「装了什么、是什么类型、能不能用」；开关和卸载都收进详情页，
 * 免得装几个插件就是一整面开关墙。[demo] 为 true 时用假数据，方便看版式。
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
    var showSettings by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var marketQuery by rememberSaveable { mutableStateOf("") }
    var marketFilter by rememberSaveable { mutableStateOf(MarketFilter.All) }

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
                actions = {
                    CouiToolbarActionButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "插件设置")
                    }
                },
                bottomContent = {
                    Column {
                        TypeLegend()
                        DockedPageTabs(
                            tabs = listOf("已安装", "在线市场"),
                            selectedTabIndex = tab,
                            onTabSelected = { tab = it },
                        )
                    }
                },
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        val base = Modifier
            .fillMaxSize()
            .padding(padding.withoutTop(glass))
            .glassSource(glass)

        if (tab == 0) {
            val listState = rememberLazyListState()
            ReportScrollPosition(listState)
            LazyColumn(
                modifier = base,
                state = listState,
                contentPadding = tabContentPadding(PaddingValues(top = glassTop, bottom = 24.dp)),
            ) {
                message?.let { text ->
                    item {
                        Text(
                            text = text,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (records.isEmpty()) {
                    item {
                        EmptyState(
                            title = "还没有装插件",
                            subtitle = "右上角的设置里可以从文件安装；插件是一个 .zip 包，dex 类型里带 APK，web 类型只是打开一个网页。",
                        )
                    }
                } else {
                    items(records, key = { it.id }) { record ->
                        PluginRow(
                            record = record,
                            broken = record.id in broken,
                            onClick = { detailId = record.id },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        } else {
            MarketTab(
                demo = demo,
                query = marketQuery,
                onQueryChange = { marketQuery = it },
                filter = marketFilter,
                onFilterChange = { marketFilter = it },
                refreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        // 索引还没接：这里以后是拉索引 + 校验，现在只是把转圈走一遍
                        kotlinx.coroutines.delay(700)
                        refreshing = false
                    }
                },
                onOpenSettings = { showSettings = true },
                modifier = base,
                glassTop = glassTop,
                scrollBehavior = scrollBehavior,
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            demo = demo,
            onDismiss = { showSettings = false },
            onInstallFromFile = {
                showSettings = false
                picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
        )
    }

    val install = pending
    if (install != null) {
        com.xjtu.toolbox.community.ConfirmDialog(
            title = if (install.isUpdate) "更新「${install.manifest.name}」？" else "安装「${install.manifest.name}」？",
            summary = buildString {
                append("版本 ${install.manifest.version}（versionCode ${install.manifest.versionCode}）")
                append("，页面 ${install.manifest.pages.size} 个、工具 ${install.manifest.tools.size} 个。\n\n")
                install.oldVersionName?.let { append("已安装 v$it，会替换掉。\n") }
                append("签名 ${install.certSha256.take(16)}…\n\n")
                append("dex 插件与本 App 同权限运行，能读账号、登录会话和本机数据；只装你信任来源的插件。")
            },
            confirm = if (install.isUpdate) "更新" else "安装",
            onConfirm = {
                pending = null
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { manager.commit(install) } }
                        .onSuccess { message = "已${if (install.isUpdate) "更新" else "安装"}：${install.manifest.name}" }
                        .onFailure { message = it.message ?: "安装失败" }
                    manager.refresh()
                    busy = false
                }
            },
            onDismiss = {
                manager.cancel(install)
                pending = null
            },
        )
    }
}

/** 两类插件的图例：徽章 + 一句话说明，放在 tab 上面、两条 tab 共用。 */
@Composable
private fun TypeLegend() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(isWeb = true)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "功能有限，更安全",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(isWeb = false)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "功能丰富，权限等同 App，",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "务必确认来源",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
}

/** 类型徽章：web 蓝、dex 橙，宽度固定，两个徽章左右都对齐。 */
@Composable
private fun TypeBadge(isWeb: Boolean) {
    val color = if (isWeb) WebBadgeColor else DexBadgeColor
    Box(
        Modifier
            .width(BadgeWidth)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isWeb) "web" else "dex",
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            maxLines = 1,
        )
    }
}

/** 列表里的一项：名字 + 版本 / 徽章 + 状态 / 三行简介。 */
@Composable
private fun PluginRow(
    record: PluginRecord,
    broken: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        broken -> "插件包丢失，卸载后重装"
        !record.enabled -> "已停用"
        record.toolsEnabled -> "已启用 · 允许小岱调用"
        else -> "已启用"
    }
    Card(
        modifier = modifier
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
                    maxLines = 1,
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

// ── 在线市场 ────────────────────────────────────────────────────────────────

/** 市场里的一条。索引接上以后由 JSON 映射过来。 */
private data class MarketEntry(
    val id: String,
    val name: String,
    val summary: String,
    val isWeb: Boolean,
    val version: String,
    val author: String,
    /** 索引里带 `featured: true` 的进顶部横滑精选。 */
    val featured: Boolean = false,
)

@Composable
private fun MarketTab(
    demo: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: MarketFilter,
    onFilterChange: (MarketFilter) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
    glassTop: androidx.compose.ui.unit.Dp,
    scrollBehavior: com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior?,
) {
    val entries = if (demo) demoMarket() else emptyList()
    val shown = entries.filter { entry ->
        (filter == MarketFilter.All || filter.label in entry.summary || filter.label == entry.author) &&
            (query.isBlank() || entry.name.contains(query, ignoreCase = true) || entry.summary.contains(query, ignoreCase = true))
    }
    val featured = entries.filter { it.featured }

    val listState = rememberLazyListState()
    ReportScrollPosition(listState)

    AppPullToRefresh(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        scrollBehavior = scrollBehavior,
        topPadding = glassTop,
        modifier = modifier,
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = tabContentPadding(PaddingValues(top = glassTop, bottom = 24.dp)),
        ) {
            if (featured.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = "精选",
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            items(featured, key = { it.id }) { entry -> FeaturedCard(entry) }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    AppSearchBar(
                        query = query,
                        onQueryChange = onQueryChange,
                        label = "搜索插件",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    AppChipTabRow(
                        options = MarketFilter.entries,
                        selected = filter,
                        onSelect = onFilterChange,
                        label = { it.label },
                    )
                }
            }

            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        title = "在线市场还没开张",
                        subtitle = if (entries.isEmpty()) {
                            "还没配索引地址，或者索引里还没有内容。右上角设置里可以填索引地址。"
                        } else {
                            "没有匹配的插件。"
                        },
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "去设置里填索引地址…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenSettings)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                items(shown, key = { it.id }) { entry ->
                    MarketRow(entry, Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/** 横滑精选卡：不自动播放，只占首屏一小条。 */
@Composable
private fun FeaturedCard(entry: MarketEntry) {
    Card(
        modifier = Modifier
            .width(220.dp)
            .heightIn(min = 104.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                TypeBadge(isWeb = entry.isWeb)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MarketRow(entry: MarketEntry, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.version,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(isWeb = entry.isWeb)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.author,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 设置 ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSheet(demo: Boolean, onDismiss: () -> Unit, onInstallFromFile: () -> Unit) {
    val context = LocalContext.current
    var indexUrl by remember { mutableStateOf(PluginSettings.indexUrl(context)) }
    var wifiOnly by remember { mutableStateOf(PluginSettings.wifiOnly(context)) }

    OverlayBottomSheet(
        show = true,
        title = "插件设置",
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            TextField(
                value = indexUrl,
                onValueChange = {
                    indexUrl = it
                    if (!demo) PluginSettings.setIndexUrl(context, it)
                },
                label = "索引地址",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "在线市场从这个地址读插件列表。留空就是空市场。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp),
            )
            CouiSettingsCard {
                SwitchPreference(
                    title = "只在 Wi-Fi 下加载索引",
                    summary = "移动网络下打开插件市场不联网，省流量",
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        if (!demo) PluginSettings.setWifiOnly(context, it)
                    },
                )
                ArrowPreference(
                    title = "从文件安装插件",
                    summary = "选一个 .zip 插件包",
                    onClick = onInstallFromFile,
                )
                ArrowPreference(
                    title = "清空插件缓存",
                    summary = "只删临时文件，不动已装的插件和它们的数据",
                    onClick = {
                        context.cacheDir.listFiles { f -> f.name.startsWith("plugin_import_") }
                            ?.forEach { runCatching { it.delete() } }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
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
        }
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
        name = "课表导出 ICS",
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
        name = "选课捡漏雷达",
        summary = "盯住你指定的几门课，有人退课就立刻在通知栏喊你，还能设置只在白天盯、只在某几周盯。",
        isWeb = false,
        version = "3.2.0",
        versionCode = 21,
        tools = 4,
        pages = 3,
    ),
)

private fun demoMarket(): List<MarketEntry> = listOf(
    MarketEntry(
        id = "com.example.notice.digest",
        name = "通知摘要",
        summary = "把一周的校园通知压成五条，睡前推一次。",
        isWeb = false,
        version = "1.4.0",
        author = "工具",
        featured = true,
    ),
    MarketEntry(
        id = "com.example.emptyroom.map",
        name = "空教室地图",
        summary = "按楼栋画一张热力图，一眼看出哪层还有空教室。",
        isWeb = true,
        version = "0.8.2",
        author = "学习",
        featured = true,
    ),
    MarketEntry(
        id = "com.example.canteen.menu",
        name = "食堂今日菜单",
        summary = "四个食堂的菜单和排队提示。",
        isWeb = true,
        version = "2.1.0",
        author = "生活",
        featured = true,
    ),
    MarketEntry(
        id = "com.example.lms.reminder",
        name = "作业截止提醒",
        summary = "思源学堂作业截止前一天提醒，已经交过的不吵。",
        isWeb = false,
        version = "1.0.3",
        author = "学习",
    ),
    MarketEntry(
        id = "com.example.bus.live",
        name = "校车实时位置",
        summary = "校车开到哪了，还有几分钟到站。",
        isWeb = true,
        version = "1.2.1",
        author = "生活",
    ),
)
