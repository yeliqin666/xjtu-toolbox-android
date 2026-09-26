package com.xjtu.toolbox.zyxf

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 仲英学辅资料站的原生浏览页。
 *
 * 取代原来把整个 zyxf.top 塞进 WebView 的做法：列表、检索、下载都走它的公开只读接口
 * （[ZyxfApi]），排版用 MIUIX，和应用其余部分一致。
 *
 * ### 一条纪律：不预取
 * 资料站的签名链**按 IP 限流**（每分钟 60 次、每小时 240 次）。网页版天然安全，
 * 因为用户点一次才请求一次；原生端一旦做"列表页预取缩略图/预热直链"，一屏就能打爆，
 * 而且打爆的是**别人的**服务。所以这里严格保持"一次交互一次请求"，
 * 目录内容按需拉、直链点了才要。
 *
 * 预览另见 [ZyxfPreviewSheet]：那一层的渲染借阿里云 IMM，不自己造。
 */
@Composable
fun ZyxfBrowseScreen(
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** 宿主的折叠标题栏。列表滚动要驱动它，否则大标题永远不收。 */
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
    /** 宿主玻璃顶栏的高度：列表铺到顶栏下面，这段留白放进列表的 contentPadding。经典风格为 0。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: ZyxfBrowseViewModel = viewModel { ZyxfBrowseViewModel(context) }
    val stack = vm.stack
    val entries = vm.entries
    val searching = vm.searching
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(vm.loading) { if (!vm.loading) refreshing = false }

    val isWide = com.xjtu.toolbox.ui.isWideLayout()

    // 目录内返回上一级；已在根目录时交给宿主。
    // 宽屏右栏的预览不算一层：返回不先关预览（和教师主页、日程的分屏同一个约定）。
    // 窄屏下预览是弹窗，它自己接返回。
    BackHandler(enabled = stack.size > 1 || searching) {
        when {
            searching -> vm.clearSearch()
            else -> vm.goTo(stack.lastIndex - 1)
        }
    }


    // 宽屏分栏里选的预览，转成窄屏后作废（见 previewFromSplit）。在组合里就判断、不渲染，
    // 所以不会先闪一下全屏再关掉；状态的清理放到 SideEffect 里，不在组合过程中写状态。
    val splitPreviewStale = !isWide && vm.previewFromSplit && vm.previewing != null
    if (splitPreviewStale) SideEffect { vm.previewing = null; vm.previewFromSplit = false }

    // 窄屏的预览是接近全高的底部弹窗（盖住底部 Tab 栏），一直留在组合里、按有没有选中文件开合。
    // 宽屏不走这一支：预览已经长在右栏里。
    ZyxfPreviewSheet(
        file = if (!isWide && !splitPreviewStale) vm.previewing else null,
        onDismiss = { vm.previewing = null },
        onDownload = vm::download,
    )

    // 宽屏：左栏列表、右栏预览。窄屏下 TwoPane 只渲染列表，预览走上面那个底部弹窗。
    //
    // 搜索框、面包屑、排序条都放进列表里跟着滚，不钉在顶上：玻璃顶栏下面铺的是列表，
    // 钉住的头部要么被压在玻璃后面，要么自己占一截、让玻璃底下永远只是一条纯色。
    val listPane: @Composable () -> Unit = {
    Column(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        val header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            item(key = "search", contentType = "header") {
                SearchField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    onSearch = vm::runSearch,
                    onClear = vm::clearSearch,
                )
            }
            if (!searching && stack.size > 1) {
                item(key = "crumb", contentType = "header") {
                    Breadcrumb(stack = stack, onJump = vm::goTo)
                }
            }
            if (!searching) {
                item(key = "sort", contentType = "header") {
                    SortBar(
                        sort = vm.sort,
                        desc = vm.desc,
                        onPick = vm::pickSort,
                    )
                }
            }
            if (searching) {
                item(key = "searchInfo", contentType = "header") {
                    Text(
                        if (vm.truncated) "检索结果（较多，已截断；关键词更具体能看到更多）"
                        else "检索结果 · ${entries.size} 条",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }

        val pullState = rememberPullToRefreshState()
        PullToRefresh(
            refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
            // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
            topAppBarScrollBehavior = scrollBehavior,
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                vm.reload()
            },
            pullToRefreshState = pullState,
            // 下拉指示器从玻璃顶栏下面出来，不藏到玻璃后面
            contentPadding = PaddingValues(top = contentTopPadding),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .then(
                        scrollBehavior?.let {
                            Modifier.nestedScroll(it.nestedScrollConnection)
                        } ?: Modifier
                    )
                    .overScrollVertical(),
                // 底部留白必须走列表的 contentPadding，不能垫在外层 Column 上：
                // 外层是「先铺底色、再 padding」，垫上去等于在悬浮底栏下面留了一整条
                // 死的纯色带（底栏高 + 导航条，实测 80dp 上下），看着就是「底部一条很宽的状态栏」。
                // 放进 contentPadding 则是列表能滚过去、玻璃底栏下面透出的是真内容。
                contentPadding = PaddingValues(
                    top = contentTopPadding,
                    bottom = 16.dp + contentPadding.calculateBottomPadding(),
                ),
            ) {
                header()
                // 加载、出错、空目录也是列表里的一项：头部照样在，能改搜索词、能点面包屑回上一级
                val stateText = when {
                    vm.loading && entries.isEmpty() -> null
                    vm.error != null -> vm.error
                    entries.isEmpty() -> if (searching) "没有匹配的资料" else "这个目录是空的"
                    else -> ""
                }
                if (stateText != "") {
                    item(key = "state", contentType = "state") {
                        Box(
                            Modifier
                                .fillParentMaxWidth()
                                .fillParentMaxHeight(0.6f),
                            Alignment.Center,
                        ) {
                            if (stateText == null) {
                                com.xjtu.toolbox.ui.components.MorphingLoader()  // 整页加载统一用形变加载器
                            } else {
                                Text(
                                    stateText,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                } else {
                    // 文件夹在上、排成两列的小卡片；文件在下、合成一张分组卡片，行与行之间一道细分隔线。
                    // 以前每一项都是一张独立的灰卡片，一长串看着像随手堆上去的。
                    val folders = entries.filter { it.isFolder }
                    val files = entries.filterNot { it.isFolder }
                    val onEntryClick: (ZyxfApi.Entry) -> Unit = { entry ->
                        if (entry.isFolder) vm.openFolder(entry)
                        else if (ZyxfApi.previewable(entry.ext)) {
                            vm.previewing = entry
                            vm.previewFromSplit = isWide
                        } else vm.download(entry)
                    }
                    if (folders.isNotEmpty()) {
                        item(key = "folderHead", contentType = "section") { SectionLabel("文件夹", folders.size) }
                        items(folders.chunked(2), key = { "folders-${it.first().id}" }, contentType = { "folderRow" }) { pair ->
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pair.forEach { folder ->
                                    FolderTile(folder, Modifier.weight(1f)) { onEntryClick(folder) }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (files.isNotEmpty()) {
                        item(key = "fileHead", contentType = "section") { SectionLabel("文件", files.size) }
                        itemsIndexed(files, key = { _, it -> "file-${it.id}" }, contentType = { _, _ -> "file" }) { i, entry ->
                            FileRow(
                                entry = entry,
                                state = vm.downloadState[entry.id],
                                first = i == 0,
                                last = i == files.lastIndex,
                                onClick = { onEntryClick(entry) },
                                onDownload = { vm.download(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    com.xjtu.toolbox.ui.adaptive.TwoPane(
        list = listPane,
        detail = {
          // 右栏的预览不跟着列表滚，整栏让出顶栏高度
          Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(top = contentTopPadding)) {
            val file = vm.previewing
            if (file != null) {
                // 不包 Dialog：它就长在右栏里。
                PreviewContent(
                    fileId = file.id,
                    fileName = file.name,
                    sizeBytes = file.sizeBytes,
                    onBack = { vm.previewing = null },
                    onDownload = { vm.download(file) },
                    embedded = true,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "选择一个文件预览",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
          }
        },
        listWidth = 380.dp,
    )
}



@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(
                    "搜课件、历年卷、笔记…",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = "清空",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Breadcrumb(stack: List<Crumb>, onJump: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stack.forEachIndexed { i, crumb ->
            if (i > 0) {
                Text(
                    "  ›  ",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                crumb.name,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (i == stack.lastIndex) FontWeight.Bold else FontWeight.Normal,
                color = if (i == stack.lastIndex) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.clickable(enabled = i < stack.lastIndex) { onJump(i) },
            )
        }
    }
}

/**
 * 排序条。
 *
 * 字段与服务端 `SORT_FIELDS` 对齐（默认/名称/时间/大小），沿用网页版的交互：
 * 点当前项翻方向、点别项换字段。时间默认降序——找资料时想看的是"最近传了什么"，
 * 而不是三年前的第一份。
 */
@Composable
private fun SortBar(
    sort: ZyxfApi.Sort,
    desc: Boolean,
    onPick: (ZyxfApi.Sort) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZyxfApi.Sort.entries.forEach { option ->
            val active = option == sort
            val arrow = when {
                !active || option == ZyxfApi.Sort.MANUAL -> ""
                desc -> " ↓"
                else -> " ↑"
            }
            com.xjtu.toolbox.ui.components.AppFilterChip(
                selected = active,
                onClick = { onPick(option) },
                label = option.label + arrow,
            )
        }
    }
}

/**
 * 文件类型色标。
 *
 * 用带颜色的扩展名方块而不是给每种类型画一个图标：资料站上就那么几类
 * （pdf / ppt / doc / xls / 压缩包），颜色已经足够一眼分辨，
 * 而引二十个图标既臃肿又和 MIUIX 的线性图标风格打架。
 */
private fun extTone(ext: String): Pair<Long, String> {
    val e = ext.lowercase().removePrefix(".")
    return when (e) {
        "pdf" -> 0xFFE5484D to "PDF"
        "doc", "docx", "wps", "rtf", "dot", "dotx", "wpt" -> 0xFF3B82F6 to "DOC"
        "ppt", "pptx", "pps", "ppsx", "potx", "dpt", "dps" -> 0xFFF0A23C to "PPT"
        "xls", "xlsx", "xlt", "xltx", "et", "csv" -> 0xFF3FBF7F to "XLS"
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2" -> 0xFF9BA1A6 to "ZIP"
        "txt", "md" -> 0xFF6B7280 to "TXT"
        else -> 0xFF8B5CF6 to (e.take(3).uppercase().ifBlank { "?" })
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Row(
        Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

/** 文件夹：带色块图标的小卡片，两列排开，一屏能看到的目录多一倍。 */
@Composable
private fun FolderTile(entry: ZyxfApi.Entry, modifier: Modifier, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            entry.name,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 文件行：分组卡片里的一行。首行圆上角、末行圆下角，行间画细分隔线，
 * 连起来就是一整张卡。左边是带颜色的类型方块，右边是下载按钮或下载状态。
 */
@Composable
private fun FileRow(
    entry: ZyxfApi.Entry,
    state: String?,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val r = 18.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (tone, label) = extTone(entry.ext)
            val color = androidx.compose.ui.graphics.Color(tone)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Black,
                    color = color,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    entry.path.takeIf { it.isNotBlank() },
                    entry.sizeText.takeIf { it.isNotBlank() },
                    entry.timeText.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        sub,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                state == DOWNLOADING -> CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp, progress = null)
                state != null -> Text(
                    state,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                )
                else -> IconButton(onClick = onDownload, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
        if (!last) {
            Box(
                Modifier
                    .padding(start = 66.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.dividerLine),
            )
        }
    }
}
