package com.xjtu.toolbox.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownOption
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.DockedPageTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.MenuMorph
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.ReportScrollPosition
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.reportScrollToTopBar
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

/** 在线市场的排序方式。 */
private enum class MarketSort(val label: String) {
    Hot("热度"),
    New("最新"),
    Name("名称"),
}

/**
 * 设置 → 插件：上面一条窄 navbar 分「已安装 / 在线市场」，右上角是插件设置。
 *
 * 列表页只回答「装了什么、是什么类型、能不能用」；开关和卸载都收进详情页，
 * 免得装几个插件就是一整面开关墙。[demo] 为 true 时用假数据，方便看版式。
 */
@Composable
internal fun PluginsPage(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    demo: Boolean = PluginOverlay.listDemo,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var pendingMarket by remember { mutableStateOf<MarketEntry?>(null) }

    val records = if (demo) demoRecords() else manager.records
    val broken = if (demo) DemoBrokenIds else manager.brokenIds

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

    // 下拉刷新途中切到另一个 tab：那个 PullToRefresh 已经被组掉了，转圈状态得自己收回来
    LaunchedEffect(tab) { refreshing = false }

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

        // 用翻页器而不是 AnimatedContent：两页各自保留滚动状态，切换时顶栏
        // 和右上角按钮不会跟着内容重建（消息界面也是这套）。左右滑关掉——市场里有横滑的精选行
        AppTabPager(
            pageCount = 2,
            selectedTabIndex = tab,
            onTabSelected = { tab = it },
            modifier = Modifier.fillMaxSize(),
            swipeEnabled = false,
        ) { current ->
        if (current == 0) {
            val listState = rememberLazyListState()
            ReportScrollPosition(listState, scrollBehavior)
            LazyColumn(
                // 大标题要靠 nestedScroll 才收得起来（ReportScrollPosition 只管「压到栏下面」那层状态）
                modifier = base.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                            onClick = { onOpenDetail(record.id) },
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        } else {
            MarketTab(
                demo = demo,
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
                onInstall = { pendingMarket = it },
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

        val market = pendingMarket
        if (market != null) {
            com.xjtu.toolbox.community.ConfirmDialog(
                title = "安装「${market.name}」？",
                summary = buildString {
                    append("版本 ${market.version} · 作者 ${market.author}\n")
                    append(if (market.isWeb) "web 插件，在内置浏览器里打开网页，与 App 隔离。\n\n" else "dex 插件，与本 App 同权限运行。\n\n")
                    append("（演示数据：确认后这里会去下载并校验插件包。）")
                },
                confirm = "安装",
                onConfirm = {
                    pendingMarket = null
                    message = "演示数据：真装要等索引和包格式接上"
                },
                onDismiss = { pendingMarket = null },
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
                    append("签名者 ${install.inspected.signerSha256.take(16)}…\n\n")
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
}

/** 作者头像：有图就用图，没有就用名字首字画个圆。 */
@Composable
private fun AuthorAvatar(name: String) {
    val initial = name.trim().take(1).ifBlank { "?" }
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(DexBadgeColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MiuixTheme.textStyles.footnote1,
            color = DexBadgeColor,
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
    // 停用的插件标题置灰加删除线，扫一眼就知道它现在不工作
    val dimmed = !record.enabled
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
                    color = if (dimmed) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    textDecoration = if (dimmed) TextDecoration.LineThrough else null,
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
                // web 插件开着 VPN 时提醒一句（插件整体停用就不加）
                if (record.manifest.isWeb && record.enabled && !broken && record.webVpnEnabled()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "· 经 WebVPN",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                if (record.manifest.author.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = record.manifest.author,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
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
            if (record.manifest.tools.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "工具 ${record.enabledToolCount()}/${record.manifest.tools.size}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
    /** 标签：索引根下 `tags` 数组里的值。 */
    val tags: List<String> = emptyList(),
)

@Composable
private fun MarketTab(
    demo: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstall: (MarketEntry) -> Unit,
    modifier: Modifier,
    glassTop: androidx.compose.ui.unit.Dp,
    scrollBehavior: com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(MarketSort.Hot) }
    var types by remember { mutableStateOf(setOf("dex", "web")) }
    var tags by remember { mutableStateOf(emptySet<String>()) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    val entries = if (demo) demoMarket() else emptyList()
    val allTags = if (demo) DemoTags else emptyList()

    val shown = entries
        .filter { entry -> if (entry.isWeb) "web" in types else "dex" in types }
        .filter { entry -> tags.isEmpty() || entry.tags.any { it in tags } }
        .filter { entry ->
            query.isBlank() ||
                entry.name.contains(query, ignoreCase = true) ||
                entry.summary.contains(query, ignoreCase = true)
        }
        .let { list ->
            when (sort) {
                MarketSort.Hot -> list
                MarketSort.New -> list.reversed()
                MarketSort.Name -> list.sortedBy { it.name }
            }
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
                item(key = "featured") {
                    Column(Modifier.padding(top = 12.dp)) {
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
                            items(featured, key = { it.id }) { entry ->
                                FeaturedCard(
                                    entry = entry,
                                    onClick = { onInstall(entry) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }

            item(key = "filters") {
                Column(
                    Modifier.padding(
                        horizontal = 16.dp,
                        vertical = if (featured.isEmpty()) 12.dp else 0.dp,
                    ),
                ) {
                    AppSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        label = "搜索插件",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 排序：单选
                        Box {
                            AppFilterChip(
                                selected = sort != MarketSort.Hot,
                                onClick = { openMenu = "sort" },
                                label = "排序：${sort.label}",
                                trailingIcon = { ChipArrow() },
                            )
                            AppDropdownMenu(
                                expanded = openMenu == "sort",
                                onDismissRequest = { openMenu = null },
                                morph = MenuMorph.FromTab,
                            ) {
                                MarketSort.entries.forEach { option ->
                                    AppDropdownOption(option.label, option == sort) {
                                        sort = option
                                        openMenu = null
                                    }
                                }
                            }
                        }
                        // 类型：dex / web 多选
                        Box {
                            AppFilterChip(
                                selected = types.size != 2,
                                onClick = { openMenu = "type" },
                                label = "类型",
                                trailingIcon = { ChipArrow() },
                            )
                            AppDropdownMenu(
                                expanded = openMenu == "type",
                                onDismissRequest = { openMenu = null },
                                morph = MenuMorph.FromTab,
                            ) {
                                listOf("dex" to "dex 插件", "web" to "web 插件").forEach { (value, label) ->
                                    AppDropdownOption(label, value in types) {
                                        types = if (value in types) types - value else types + value
                                    }
                                }
                            }
                        }
                        // 标签：索引根下 tags 数组，多选
                        Box {
                            AppFilterChip(
                                selected = tags.isNotEmpty(),
                                onClick = { if (allTags.isNotEmpty()) openMenu = "tag" },
                                label = if (tags.isEmpty()) "标签" else "标签 ${tags.size}",
                                trailingIcon = { ChipArrow() },
                            )
                            AppDropdownMenu(
                                expanded = openMenu == "tag",
                                onDismissRequest = { openMenu = null },
                                morph = MenuMorph.FromTab,
                            ) {
                                allTags.forEach { tag ->
                                    AppDropdownOption(tag, tag in tags) {
                                        tags = if (tag in tags) tags - tag else tags + tag
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "在线市场还没开张",
                        subtitle = if (entries.isEmpty()) {
                            "还没配索引地址，或者索引里还没有内容。"
                        } else {
                            "没有符合筛选条件的插件。"
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
                    MarketRow(
                        entry = entry,
                        onClick = { onInstall(entry) },
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** 胶囊尾巴上的下拉箭头。 */
@Composable
private fun ChipArrow() {
    Icon(
        Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
    )
}

/** 横滑精选卡：不自动播放，只占首屏一小条。 */
@Composable
private fun FeaturedCard(
    entry: MarketEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(220.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
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
                modifier = Modifier.weight(1f),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.version,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.author,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
    }
}

@Composable
private fun MarketRow(entry: MarketEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.author,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                .padding(vertical = 4.dp),
        ) {
            // 卡片自带 16dp 外边距，输入框补同样的边，两者才左右对齐
            TextField(
                value = indexUrl,
                onValueChange = {
                    indexUrl = it
                    if (!demo) PluginSettings.setIndexUrl(context, it)
                },
                label = "索引地址",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "在线市场从这个地址读插件列表；留空就用默认市场。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "用默认市场（${PluginSettings.DEFAULT_INDEX_URL}）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            indexUrl = PluginSettings.DEFAULT_INDEX_URL
                            if (!demo) PluginSettings.setIndexUrl(context, PluginSettings.DEFAULT_INDEX_URL)
                        }
                        .padding(vertical = 6.dp),
                )
            }
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

/**
 * 详情页。分组从上到下：开关（启用 / 允许小岱）、页面、作者与项目、卸载。
 *
 * 开关和页面分开放两个卡：一个是「这个插件有没有被允许运行」，一个是「它能从哪里进去」，
 * 混在一起时用户点开关会以为在控制页面。
 */
/**
 * 插件详情单独一页（路由 `AppRoute.PluginDetail`）。
 *
 * 列表和详情必须是两个路由类型：miuix-nav 按类型复用 entry，
 * 用同一个类的参数区分列表/详情的话，第二条路由会显示成第一条的内容。
 */
@Composable
internal fun PluginDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
    demo: Boolean = PluginOverlay.listDemo,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()

    val records = if (demo) demoRecords() else manager.records
    val broken = if (demo) DemoBrokenIds else manager.brokenIds

    if (!demo) LaunchedEffect(Unit) { manager.refresh() }

    val record = records.firstOrNull { it.id == pluginId }
    if (record == null) {
        // 冷启动直接深链进来时列表还没读完，先给一屏「不在」，读完会自己重组出来
        Scaffold(
            topBar = {
                GlassTopAppBar(
                    title = "插件",
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                )
            },
        ) { _ ->
            EmptyState(
                title = "插件不在了",
                subtitle = "可能已经卸载，或者列表还没读完；返回上一页看看。",
            )
        }
        return
    }

    DetailPage(
        record = record,
        broken = record.id in broken,
        onBack = onBack,
        onOpenPage = { pageId ->
            if (record.manifest.isWeb) {
                PluginLauncher.openWeb?.invoke(record.id)
            } else {
                val launch = PluginLauncher.openRoute
                if (launch != null) launch(record.id, pageId) else PluginOverlay.open(record.id, pageId)
            }
        },
        onToggleEnabled = { on ->
            if (!demo) scope.launch { manager.setEnabled(record.id, on) }
        },
        onToggleTools = { on ->
            if (!demo) scope.launch { manager.setToolsEnabled(record.id, on) }
        },
        onToggleVpn = { on ->
            if (!demo) scope.launch { manager.setVpnEnabled(record.id, on) }
        },
        onOpenTools = { PluginLauncher.openTools?.invoke(record.id) },
        onUninstall = {
            if (!demo) {
                scope.launch {
                    manager.uninstall(record.id)
                    manager.refresh()
                }
            }
            onBack()
        },
    )
}

@Composable
private fun DetailPage(
    record: PluginRecord,
    broken: Boolean,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleTools: (Boolean) -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onOpenTools: () -> Unit,
    onUninstall: () -> Unit,
) {
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()
    var confirm by remember { mutableStateOf(false) }

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
                // 只在内容真的能滚时才把滚动量报给顶栏：内容不够一屏时上拉只是回弹，不该折叠
                .reportScrollToTopBar(scrollState, scrollBehavior)
                .verticalScroll(scrollState)
                .padding(padding.withoutTop(glass))
                // 顶栏的毛玻璃要采样正文，不挂这个就只有实色底
                .glassSource(glass)
                .padding(horizontal = 16.dp)
                .padding(top = glassTop, bottom = 24.dp),
        ) {
            // 大标题下面先空一段，不然徽章那行贴在标题底边上
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(isWeb = record.manifest.isWeb)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.versionName.ifBlank { "v${record.versionCode}" },
                    style = MiuixTheme.textStyles.body2,
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
                Spacer(Modifier.height(12.dp))
                Text(
                    text = record.manifest.summary,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(22.dp))

            // ① 开关
            CouiSettingsCard {
                SwitchPreference(
                    title = "启用",
                    summary = if (broken) "插件包丢失，请卸载后重装" else "关闭后页面和小岱工具都不可用",
                    checked = record.enabled && !broken,
                    enabled = !broken,
                    onCheckedChange = onToggleEnabled,
                )
                // web 插件：VPN 策略为 required / off 时灰掉，但状态照实显示
                if (record.manifest.isWeb) {
                    SwitchPreference(
                        title = "走 WebVPN",
                        summary = when (record.manifest.webVpn) {
                            "required" -> "插件要求必须开启，校外也能访问"
                            "off" -> "插件要求直连，不能开启"
                            else -> "校外要访问校内站点时打开"
                        },
                        checked = record.webVpnEnabled(),
                        enabled = !record.webVpnLocked() && record.enabled && !broken,
                        onCheckedChange = onToggleVpn,
                    )
                }
                if (record.manifest.tools.isNotEmpty()) {
                    ArrowPreference(
                        title = "允许小岱调用",
                        summary = "工具 ${record.enabledToolCount()}/${record.manifest.tools.size}",
                        enabled = record.enabled && !broken,
                        onClick = onOpenTools,
                    )
                }
            }

            // ② 页面（web 插件只有一个「打开网页」）
            Spacer(Modifier.height(14.dp))
            when {
                record.manifest.isWeb -> CouiSettingsCard {
                    ArrowPreference(
                        title = "打开网页",
                        summary = buildString {
                            append(webHostOf(record.manifest.webStartUrl))
                            if (record.webVpnEnabled()) append(" · 经 WebVPN")
                        },
                        enabled = record.enabled && !broken,
                        onClick = { onOpenPage("") },
                    )
                }
                record.manifest.pages.isEmpty() -> Text(
                    text = "这个插件没有页面，只给小岱加工具。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                else -> CouiSettingsCard {
                    record.manifest.pages.forEach { page ->
                        ArrowPreference(
                            title = page.title.ifBlank { page.id },
                            summary = "打开插件页面",
                            enabled = record.enabled && !broken,
                            onClick = { onOpenPage(page.id) },
                        )
                    }
                }
            }

            // ③ 作者与项目
            if (record.manifest.author.isNotBlank() || record.manifest.projectUrl.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                CouiSettingsCard {
                    if (record.manifest.author.isNotBlank()) {
                        ArrowPreference(
                            title = "作者",
                            summary = record.manifest.author,
                            startAction = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AuthorAvatar(record.manifest.author)
                                    Spacer(Modifier.width(12.dp))
                                }
                            },
                            enabled = record.manifest.authorUrl.isNotBlank(),
                            onClick = { uriHandler.openUri(record.manifest.authorUrl) },
                        )
                    }
                    if (record.manifest.projectUrl.isNotBlank()) {
                        ArrowPreference(
                            title = "项目地址",
                            summary = record.manifest.projectUrl,
                            onClick = { uriHandler.openUri(record.manifest.projectUrl) },
                        )
                    }
                }
            }

            // ④ 卸载
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

/** 索引根下的 `tags` 数组，市场筛选用。 */
private val DemoTags: List<String> = listOf("课表", "通知", "学习", "生活", "工具")

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
    root = "$id/1",
    signerSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
    enabled = enabled,
    toolsEnabled = tools > 0,
    installedAt = 0L,
    manifest = PluginManifest(
        apiVersion = 2,
        id = id,
        name = name,
        author = "Hz6826",
        authorUrl = "https://github.com/Hz6826",
        projectUrl = "https://github.com/yeliqin666/xjtu-toolbox-android",
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

internal fun demoRecords(): List<PluginRecord> = listOf(
    demoRecord(
        id = "top.dzhz.plugin.sample",
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
        author = "Hz6826",
        tags = listOf("学习", "通知"),
        featured = true,
    ),
    MarketEntry(
        id = "com.example.emptyroom.map",
        name = "空教室地图",
        summary = "按楼栋画一张热力图，一眼看出哪层还有空教室。",
        isWeb = true,
        version = "0.8.2",
        author = "Xu Guochao",
        tags = listOf("学习", "课表"),
        featured = true,
    ),
    MarketEntry(
        id = "com.example.canteen.menu",
        name = "食堂今日菜单",
        summary = "四个食堂的菜单和排队提示。",
        isWeb = true,
        version = "2.1.0",
        author = "Triangle-GitHub",
        tags = listOf("生活"),
        featured = true,
    ),
    MarketEntry(
        id = "com.example.lms.reminder",
        name = "作业截止提醒",
        summary = "思源学堂作业截止前一天提醒，已经交过的不吵。",
        isWeb = false,
        version = "1.0.3",
        author = "Xu Guochao",
        tags = listOf("学习", "通知"),
    ),
    MarketEntry(
        id = "com.example.bus.live",
        name = "校车实时位置",
        summary = "校车开到哪了，还有几分钟到站。",
        isWeb = true,
        version = "1.2.1",
        author = "Triangle-GitHub",
        tags = listOf("生活"),
    ),
)

/** 从网址里取主机名，取不到就原样返回（详情页摘要用）。 */
private fun webHostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
