package com.xjtu.toolbox.plugin

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.showBriefMessage
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownOption
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiPreferenceSpec
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.DockedPageTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.MenuMorph
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.components.TextLink
import com.xjtu.toolbox.ui.components.enterOnce
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
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.SinkFeedback

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
    var pendingMarket by remember { mutableStateOf<MarketEntry?>(null) }
    var settingsRevision by remember { mutableIntStateOf(0) }

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
                if (busy) item(key = "busy") { BusyRow(Modifier.animateItem()) }
                // 只放失败原因（要留着看）；成功走 toast
                message?.let { text ->
                    item(key = "message") {
                        Text(
                            text = text,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (records.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.animateItem().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(
                                title = "还没有装插件",
                                subtitle = "插件是同学们做的扩展，web 插件打开网页，dex 插件能做原生界面。",
                            )
                            Row {
                                TextLink(text = "去在线市场", onClick = { tab = 1 })
                                TextLink(
                                    text = "从文件安装",
                                    onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(records, key = { _, it -> it.id }) { index, record ->
                        PluginRow(
                            record = record,
                            broken = record.id in broken,
                            onClick = { onOpenDetail(record.id) },
                            modifier = Modifier
                                .animateItem()
                                .enterOnce(index)
                                .padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        } else {
            MarketTab(
                demo = demo,
                busy = busy,
                installed = records.associate { it.id to it.versionCode },
                settingsRevision = settingsRevision,
                onOpenSettings = { showSettings = true },
                onInstall = { entry ->
                    val have = records.firstOrNull { it.id == entry.id }
                    // 已是最新：直接去详情页，不再问装不装
                    if (!demo && have != null && entry.versionCode <= have.versionCode) onOpenDetail(entry.id) else pendingMarket = entry
                },
                modifier = base,
                glassTop = glassTop,
                scrollBehavior = scrollBehavior,
            )
        }
        }

        if (showSettings) {
            SettingsSheet(
                demo = demo,
                onDismiss = {
                    showSettings = false
                    // 索引地址、Wi-Fi 开关可能改了，市场重拉一次
                    settingsRevision++
                },
                onInstallFromFile = {
                    showSettings = false
                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
            )
        }

        val market = pendingMarket
        if (market != null) {
            val upgrading = records.any { it.id == market.id }
            val tooNew = market.minAppVersionCode > com.xjtu.toolbox.BuildConfig.VERSION_CODE
            com.xjtu.toolbox.community.ConfirmDialog(
                title = "${if (upgrading) "更新" else "下载"}「${market.name}」？",
                summary = buildString {
                    append("版本 ${market.version} · 作者 ${market.author}")
                    if (market.size > 0) append(" · ${formatSize(market.size)}")
                    append("\n")
                    append(if (market.isWeb) "web 插件，在内置浏览器里打开网页，与 App 隔离。" else "dex 插件，与本 App 同权限运行。")
                    if (tooNew) append("\n\n需要更新 App 才能装这个版本。")
                    else append("\n\n下载并校验后还会再确认一次签名与权限。")
                },
                confirm = if (tooNew) "知道了" else "下载",
                onConfirm = {
                    pendingMarket = null
                    when {
                        tooNew -> Unit
                        demo -> showBriefMessage("演示数据，不会真的下载")
                        else -> {
                            busy = true
                            message = null
                            scope.launch {
                                runCatching {
                                    val file = PluginMarket.download(context, market)
                                    manager.inspectFile(file)
                                }.onSuccess { pending = it }
                                    .onFailure {
                                        message = it.message ?: "下载失败"
                                        showBriefMessage(message.orEmpty())
                                    }
                                busy = false
                            }
                        }
                    }
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
                            .onSuccess { showBriefMessage("已${if (install.isUpdate) "更新" else "安装"}：${install.manifest.name}") }
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

/** 作者头像：清单里有 `authorAvatar` 就画包内那张图，没有就用名字首字画个圆。 */
@Composable
private fun AuthorAvatar(record: PluginRecord) {
    val context = LocalContext.current
    val name = record.manifest.author
    val initial = name.trim().take(1).ifBlank { "?" }
    val bitmap = remember(record.id, record.versionCode) {
        val path = record.manifest.authorAvatar
        if (path.isBlank()) {
            null
        } else {
            runCatching {
                val file = File(PluginRegistry.packageDir(context, record), path)
                if (file.isFile) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = initial,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
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
    PressCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column {
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
                        // 占满剩余宽度 + 文本右对齐，作者才靠到卡片右边
                        modifier = Modifier.weight(1f),
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

@Composable
private fun MarketTab(
    demo: Boolean,
    busy: Boolean,
    /** 已装插件 id → versionCode，用来标「已安装 / 可更新」。 */
    installed: Map<String, Int>,
    /** 插件设置面板关一次加一：地址或 Wi-Fi 开关可能变了，重拉目录。 */
    settingsRevision: Int,
    onOpenSettings: () -> Unit,
    onInstall: (MarketEntry) -> Unit,
    modifier: Modifier,
    glassTop: androidx.compose.ui.unit.Dp,
    scrollBehavior: com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(MarketSort.Hot) }
    var types by remember { mutableStateOf(setOf("dex", "web")) }
    var tags by remember { mutableStateOf(emptySet<String>()) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    var index by remember { mutableStateOf(if (demo) null else PluginMarket.cached(PluginSettings.indexUrl(context))) }
    var stale by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun load(force: Boolean) {
        if (demo) return
        scope.launch {
            loading = true
            runCatching { PluginMarket.load(context, force) }
                .onSuccess {
                    index = it.index
                    stale = it.stale
                    loadError = null
                }
                .onFailure { loadError = it.message ?: "目录加载失败" }
            loading = false
            refreshing = false
        }
    }
    LaunchedEffect(settingsRevision) { load(force = settingsRevision > 0) }

    val entries = if (demo) demoMarket() else index?.entries.orEmpty()
    val allTags = if (demo) DemoTags else index?.tags.orEmpty()

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
        onRefresh = {
            refreshing = true
            if (demo) refreshing = false else load(force = true)
        },
        scrollBehavior = scrollBehavior,
        topPadding = glassTop,
        modifier = modifier,
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = tabContentPadding(PaddingValues(top = glassTop, bottom = 24.dp)),
        ) {
            if (busy) item(key = "busy") { BusyRow(Modifier.animateItem()) }
            // 拿到了目录但不是最新的（非 Wi-Fi 或断网，用的上一份）
            if (stale && entries.isNotEmpty()) {
                item(key = "stale") {
                    Text(
                        text = loadError ?: "未联网刷新，显示的是上次的目录",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
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
                                    status = marketStatus(entry, installed),
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

            if (entries.isEmpty() && loading) {
                item(key = "loading") { LoadingState(message = "正在加载插件目录…", modifier = Modifier.animateItem()) }
            } else if (shown.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.animateItem().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val error = loadError
                        EmptyState(
                            title = when {
                                error != null && entries.isEmpty() -> "目录没加载出来"
                                entries.isEmpty() -> "在线市场还没有插件"
                                else -> "没有符合条件的插件"
                            },
                            subtitle = when {
                                error != null && entries.isEmpty() -> error
                                entries.isEmpty() -> "目录里还没有内容。"
                                else -> "换个关键词，或者放宽筛选条件。"
                            },
                        )
                        if (entries.isEmpty()) {
                            Row {
                                if (error != null) TextLink(text = "重试", onClick = { load(force = true) })
                                TextLink(text = "插件设置", onClick = onOpenSettings, chevron = true)
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(shown, key = { _, it -> it.id }) { index, entry ->
                    MarketRow(
                        entry = entry,
                        status = marketStatus(entry, installed),
                        onClick = { onInstall(entry) },
                        modifier = Modifier
                            .animateItem()
                            .enterOnce(index)
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** 插件列表、市场里可点的卡片：与社区帖子卡同一套圆角、底色和下沉按压。 */
@Composable
private fun PressCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        cornerRadius = 16.dp,
        insideMargin = insideMargin,
        colors = CardDefaults.defaultColors(color = AppCardColor),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) { content() }
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
    status: MarketStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PressCard(
        onClick = onClick,
        modifier = modifier.width(220.dp).height(132.dp),
        insideMargin = PaddingValues(14.dp),
    ) {
        Column {
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
                StatusLabel(status, Modifier.padding(start = 6.dp))
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
private fun MarketRow(entry: MarketEntry, status: MarketStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PressCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column {
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
                StatusLabel(status, Modifier.padding(start = 8.dp))
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

/** 市场条目相对本机的状态。 */
private enum class MarketStatus { NotInstalled, Installed, Updatable }

private fun marketStatus(entry: MarketEntry, installed: Map<String, Int>): MarketStatus {
    val have = installed[entry.id] ?: return MarketStatus.NotInstalled
    return if (entry.versionCode > have) MarketStatus.Updatable else MarketStatus.Installed
}

/** 「已安装」灰字、「可更新」主色字；没装不显示。 */
@Composable
private fun StatusLabel(status: MarketStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        MarketStatus.NotInstalled -> return
        MarketStatus.Installed -> "已安装" to MiuixTheme.colorScheme.onSurfaceVariantSummary
        MarketStatus.Updatable -> "可更新" to MiuixTheme.colorScheme.primary
    }
    Text(text = text, style = MiuixTheme.textStyles.footnote2, color = color, maxLines = 1, modifier = modifier)
}

/** 读包、下载时列表顶上那一行转圈。 */
@Composable
private fun BusyRow(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfiniteProgressIndicator()
        Spacer(Modifier.width(10.dp))
        Text(
            text = "正在处理插件包…",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
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
            }
            TextLink(
                text = "用默认市场",
                onClick = {
                    indexUrl = PluginSettings.DEFAULT_INDEX_URL
                    if (!demo) PluginSettings.setIndexUrl(context, PluginSettings.DEFAULT_INDEX_URL)
                },
                enabled = indexUrl != PluginSettings.DEFAULT_INDEX_URL,
                modifier = Modifier.padding(start = 10.dp),
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
                        val removed = context.cacheDir.listFiles { f -> f.name.startsWith("plugin_import_") }
                            ?.count { runCatching { it.delete() }.getOrDefault(false) } ?: 0
                        showBriefMessage(if (removed > 0) "已清理 $removed 个临时文件" else "没有可清理的缓存")
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
        ) { padding ->
            EmptyState(
                title = "插件不在了",
                subtitle = "可能已经卸载了",
                modifier = Modifier.padding(padding),
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
                // 不能挂在 rememberCoroutineScope 上：下一行 onBack() 就把这一页拆了，
                // 协程随作用域一起取消，卸载根本没跑完（就是「按钮点了没反应」那个毛病）。
                PluginWork.launch {
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
                                    AuthorAvatar(record)
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

            // ④ 卸载：单独一张、居中红字，与「我的」页的退出登录同一套
            Spacer(Modifier.height(14.dp))
            CouiSettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = CouiPreferenceSpec.rowMinHeight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = SinkFeedback(),
                            onClick = { confirm = true },
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(CouiPreferenceSpec.iconSize),
                        tint = MiuixTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(CouiPreferenceSpec.iconSpacing))
                    Text(
                        text = "卸载插件",
                        fontSize = CouiPreferenceSpec.titleSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = "会删除插件包与它的数据目录。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            // 确认弹窗必须挂在本页这个 Scaffold 的内容里：miuix 的 Overlay 系列靠 Scaffold
            // 提供的 MiuixPopupHost 渲染，写在 Scaffold 外面会 fallback 到最外层 Scaffold，
            // 被当前页面盖住——表现就是「点了卸载没反应」。
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
        projectUrl = "https://github.com/xjtu-dzhz/dzhz",
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
