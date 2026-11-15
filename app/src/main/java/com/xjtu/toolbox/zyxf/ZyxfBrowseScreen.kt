package com.xjtu.toolbox.zyxf

import androidx.compose.animation.animateContentSize

import com.xjtu.toolbox.ui.components.AppChipTabRow
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.platform.BackHandler
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.glass.coui.ChipTab
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingRenderer
import com.xjtu.toolbox.ui.glass.coui.CouiSearchBarState
import com.xjtu.toolbox.ui.glass.coui.LocalCouiToolbarBackdrop
import com.xjtu.toolbox.ui.glass.coui.SearchBar
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiSearchBarState
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
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
 * 目录内容按需拉、直链点了才要；关键词输入停止 500ms 后才检索，不预取下载地址。
 *
 * 预览另见 [ZyxfPreviewSheet]：那一层的渲染借阿里云 IMM，不自己造。
 */
@Composable
internal fun ZyxfBrowseScreen(
    vm: ZyxfBrowseViewModel,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** 宿主的折叠标题栏。列表滚动要驱动它，否则大标题永远不收。 */
    scrollBehavior: com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior? = null,
    /** 宿主玻璃顶栏的高度：列表铺到顶栏下面，这段留白放进列表的 contentPadding。经典风格为 0。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 顶栏是否已经承载搜索（学辅 tab）。true 时列表顶部不再重复一只搜索框。 */
    searchInTopBar: Boolean = false,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val dark = LocalIsDarkTheme.current
    val visible = com.xjtu.toolbox.ui.components.LocalPageVisible.current
    LaunchedEffect(visible) {
        if (!visible) {
            // 离开这一页只释放输入：查询词留给 ViewModel，回来时按原样重跑检索，
            // 不在这里 clearSearch()——那会顺手多发一次目录请求，而资料站是按 IP 限流的。
            focus.clearFocus(force = true)
            keyboard?.hide()
            vm.cancelPendingSearch()
        } else if (vm.query.isNotBlank()) {
            vm.runSearch()
        }
    }
    val stack = vm.stack
    val entries = vm.entries
    val searching = vm.searching
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(vm.loading) { if (!vm.loading) refreshing = false }
    // 第一次上屏才发目录请求（ViewModel 可能提前建在宿主层，见 ensureStarted）
    LaunchedEffect(vm) { vm.ensureStarted() }
    // 上次没拉成（比如当时没网），再进这一页就自己重试，不用非得下拉
    LaunchedEffect(Unit) { if (vm.error != null && !vm.loading) vm.reload() }

    val isWide = com.xjtu.toolbox.ui.isWideLayout()

    // 目录内返回上一级；已在根目录时交给宿主。
    // 宽屏右栏的预览不算一层：返回不先关预览（和教师主页、日程的分屏同一个约定）。
    // 窄屏下预览是弹窗，它自己接返回。
    // 隐藏的 visited tab 仍留在组合里，返回键只该由当前可见的这一页接管
    BackHandler(enabled = visible && (stack.size > 1 || searching)) {
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
        downloadState = { vm.downloadState[it] },
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
        val header: LazyListScope.() -> Unit = {
            // 学辅 tab 的搜索和排序在顶栏里；压栈页没有那条顶栏，放在列表头部
            if (!searchInTopBar) {
                item(key = "search", contentType = "header") {
                    CouiSearchHeader(
                        query = vm.query,
                        dark = dark,
                        onQueryChange = vm::updateQuery,
                        onSearch = {
                            vm.runSearch()
                            focus.clearFocus(force = true)
                            keyboard?.hide()
                        },
                    )
                }
            }
            if (!searchInTopBar && !searching) {
                item(key = "sort", contentType = "header") {
                    SortChipTab(
                        sort = vm.sort,
                        desc = vm.desc,
                        onPick = vm::pickSort,
                    )
                }
            }
            // 屁岱「为你找资料」：排序胶囊下面（只在根目录、没在搜的时候出现）
            if (stack.size <= 1 && !searching && com.xjtu.toolbox.agent.AgentInsightHub.insights.available) {
                item(key = "pidai_picks", contentType = "pidai") {
                    ZyxfPicksCard(
                        onPreview = { vm.previewing = it; vm.previewFromSplit = isWide },
                        onDownload = vm::download,
                        downloadState = { vm.downloadState[it] },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                            .enterOnce(0),
                    )
                }
            }
            // 面包屑放在排序胶囊下面：先选维度，再看「我在哪、能退到哪」
            if (!searching && stack.size > 1) {
                item(key = "crumb", contentType = "header") {
                    Box(Modifier.enterOnce(1)) { Breadcrumb(stack = stack, onJump = vm::goTo) }
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

        AppPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                vm.reload()
            },
            scrollBehavior = scrollBehavior,
            // 下拉指示器从玻璃顶栏下面出来，不藏到玻璃后面
            topPadding = contentTopPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = com.xjtu.toolbox.ui.glass.rememberReportedLazyListState(),
                modifier = Modifier
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
                                com.xjtu.toolbox.ui.components.LoadingState(message = null)
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        when {
                                            vm.error != null -> Icons.Default.CloudOff
                                            searching -> Icons.Default.SearchOff
                                            else -> Icons.Default.FolderOpen
                                        },
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        stateText,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
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
                        item(key = "folderHead", contentType = "section") {
                            Box(Modifier.enterOnce(2)) {
                                SectionLabel(if (searching) "匹配的文件夹 · 点开查看内容" else "文件夹", folders.size)
                            }
                        }
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
                        item(key = "fileHead", contentType = "section") {
                            Box(Modifier.enterOnce(3)) {
                                if (searching && folders.isNotEmpty()) Spacer(Modifier.height(20.dp))
                                SectionLabel(if (searching) "独立匹配的文件 · 来源见下方路径" else "文件", files.size)
                            }
                        }
                        itemsIndexed(files, key = { _, it -> "file-${it.id}" }, contentType = { _, _ -> "file" }) { i, entry ->
                            Box(Modifier.enterOnce(4 + i)) {
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
                    downloadState = vm.downloadState[file.id],
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
private fun Breadcrumb(stack: List<Crumb>, onJump: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stack.forEachIndexed { i, crumb ->
            val current = i == stack.lastIndex
            if (i > 0) {
                Text(
                    "›",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            // 胶囊：能点的上一级淡蓝底，当前所在目录灰底加粗，一眼看出「我在哪、能退到哪」
            Text(
                crumb.name,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (current) MiuixTheme.colorScheme.surfaceVariant
                        else MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                    )
                    .clickable(enabled = !current) { onJump(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 搜索头：COUI 常驻搜索胶囊（组件页上方那条）。
 *
 * 视觉来自 COUI-Kit 的 `SearchBar`：带放大镜的输入胶囊、占位提示、输入后的圆点清空叉。
 * 与顶栏里那条的区别是**一直展开**：学辅页的搜索就长在列表里，不需要「点开 / 取消」的
 * 分裂胶囊，所以关掉右侧取消（`showCancel = false`），输入区始终铺满整行。
 *
 * 材质需要一层不透明的采样源，这里就地录一条页面底色（和 COUI Demo 的分类栏同一个做法），
 * 不去采整页正文——页面采样源包含搜索框自己，采自己会形成反馈。
 */
@Composable
private fun CouiSearchHeader(
    query: String,
    dark: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val backdrop = rememberCouiBackdrop()
    val state = rememberCouiSearchBarState(expanded = true)
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .couiBackdrop(backdrop)
                .background(MiuixTheme.colorScheme.surface),
        )
        SearchBar(
            // 上下各留 8dp：浮层投影要有一圈可采样的底色，否则阴影外沿会采到透明
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            state = state,
            query = query,
            onQueryChange = onQueryChange,
            // 常驻展开态没有「退出搜索」，取消胶囊在这个页面没有落点
            onExpandedChange = {},
            showCancel = false,
            // 进页面就弹键盘会挡住半屏资料；点输入框时 BasicTextField 自己会取焦
            requestFocusOnExpand = false,
            placeholder = "搜课件、历年卷、笔记…",
            cancelText = "取消",
            clearDescription = "清空搜索",
            dark = dark,
            backdrop = backdrop,
            onSearch = onSearch,
        )
    }
}

/**
 * 学辅 tab 顶栏里的 COUI 搜索栏。
 *
 * 顶栏（[CollapsingTopAppBar]）把它和标题一起画在同一块 COUI 表面里：收起时是标题下方
 * 一条全宽搜索胶囊，点开输入时标题收走、搜索胶囊分裂出「取消」，滚动锁定；这些弹簧和
 * 测量都在 COUI 原版里，宿主只持有查询和展开目标。
 *
 * 与顶栏联动所需的状态（[CouiSearchBarState] / 折叠 state）由宿主创建；这里只负责把
 * ViewModel 的查询接到组件上。材质采的是顶栏表面通过 `LocalCouiToolbarBackdrop` 交下来的
 * 那一层，不需要自己再录。
 */
@Composable
internal fun ZyxfTopBarSearch(
    vm: ZyxfBrowseViewModel,
    searchState: CouiSearchBarState,
    shadowProgress: Float,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    SearchBar(
        state = searchState,
        query = vm.query,
        onQueryChange = vm::updateQuery,
        // 常驻展开：输入框一直在，没有「点开 / 取消」这一步
        onExpandedChange = {},
        showCancel = false,
        requestFocusOnExpand = false,
        placeholder = "搜课件、历年卷、笔记…",
        cancelText = "取消",
        clearDescription = "清空搜索",
        dark = LocalIsDarkTheme.current,
        onSearch = {
            vm.runSearch()
            focus.clearFocus(force = true)
            keyboard?.hide()
        },
        shadowProgress = shadowProgress,
    )
}

/**
 * 排序顺序：COUI 分类胶囊行（`AppChipTabRow` → COUI `ChipTabRow`）。
 *
 * 字段与服务端 `SORT_FIELDS` 对齐（默认/名称/时间/大小）。和服务端/网页版一致：
 * 点当前项翻升/降序、点别的项换字段。原版分类栏点已选中项不回调，这里打开 `reselectable`
 * 让二次点击翻方向；「默认」本身没有方向，再点由 ViewModel 直接 no-op。
 * 时间默认降序——找资料时想看的是"最近传了什么"，而不是三年前的第一份。
 *
 * 用 `ChipTabRow` 而不是自己铺一排 `ChipTab`：二次点击和横滑滚动它都自带，而且胶囊的浮层投影
 * 由整栏共享一层、向下留了 100dp 余量；自己塞进 `horizontalScroll` 的话，投影会在滚动容器的
 * 下沿被硬裁掉，看起来就是胶囊下面一条深灰 / 浅灰的分界线。顶栏里采顶栏那层模糊，
 * 列表里由它自己垫一条实色底。
 */
@Composable
private fun SortChipTab(
    sort: ZyxfApi.Sort,
    desc: Boolean,
    onPick: (ZyxfApi.Sort) -> Unit,
) {
    val arrow = when {
        sort == ZyxfApi.Sort.MANUAL -> ""
        desc -> " ↓"
        else -> " ↑"
    }
    AppChipTabRow(
        options = ZyxfApi.Sort.entries,
        selected = sort,
        onSelect = onPick,
        label = { option -> if (option == sort) option.label + arrow else option.label },
        reselectable = true,
    )
}

/**
 * 学辅 tab 顶栏里的排序胶囊：和搜索框在同一个 COUI 顶栏表面里，
 * 上滑后大标题收起，搜索 + 排序一起悬浮在页面顶部。
 *
 * 排序字段/方向由宿主从 ViewModel 读出来后传进来，宿主那一层也跟着重组，
 * 避免顶栏子组合拿到旧的选中态。
 */
@Composable
internal fun ZyxfTopBarSortChips(
    sort: ZyxfApi.Sort,
    desc: Boolean,
    onPick: (ZyxfApi.Sort) -> Unit,
) {
    SortChipTab(sort = sort, desc = desc, onPick = onPick)
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

/** 文件夹配色：按名字取，同一个目录每次颜色一样；八种都是中等饱和度，深浅色下都不刺眼。 */
private val FOLDER_TONES = longArrayOf(
    0xFF3B82F6, 0xFF14B8A6, 0xFFF0A23C, 0xFF8B5CF6, 0xFFEC4899, 0xFF3FBF7F, 0xFFE5484D, 0xFF0EA5E9,
)

private fun folderTone(name: String) =
    androidx.compose.ui.graphics.Color(FOLDER_TONES[(name.hashCode() and 0x7fffffff) % FOLDER_TONES.size])

/** 取名字里第一个能当「首字」的字符，跳过开头的括号和空格。 */
private fun folderInitial(name: String): String =
    name.trim().trimStart('(', '（', '[', '【', '《', '「').firstOrNull()?.toString() ?: "?"

/**
 * 文件夹：课程首字 + 按名字取色的小卡片，两列排开，一屏能看到的目录多一倍。
 * 以前统一一个蓝色文件夹图标，根目录一屏全是同一种颜色，只能靠读字找课。
 */
@Composable
private fun FolderTile(entry: ZyxfApi.Entry, modifier: Modifier, onClick: () -> Unit) {
    val tone = folderTone(entry.name)
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
                .background(tone.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                folderInitial(entry.name),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
            )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isFresh) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "新",
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
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
