package com.xjtu.toolbox.zyxf

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
 * 预览另见 [ZyxfPreviewScreen]：那一层的渲染借阿里云 IMM，不自己造。
 */
@Composable
fun ZyxfBrowseScreen(
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** 宿主的折叠标题栏。列表滚动要驱动它，否则大标题永远不收。 */
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    /** 目录栈。栈底是根目录，用于面包屑和返回。 */
    val stack = remember { mutableStateListOf(Crumb(0, "全部资料")) }
    var entries by remember { mutableStateOf<List<ZyxfApi.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }

    // 排序跟网页版一致：默认是管理员手工排的顺序，再点同一项翻转升降序。
    var sort by remember { mutableStateOf(ZyxfApi.Sort.MANUAL) }
    var desc by remember { mutableStateOf(false) }

    /** 每个文件的下载状态，key 是文件 ID。 */
    val downloadState = remember { mutableStateMapOf<Int, String>() }

    /** 正在预览的文件；非空时盖住整页。 */
    var previewing by remember { mutableStateOf<ZyxfApi.Entry?>(null) }

    suspend fun loadFolder(id: Int) {
        loading = true
        error = null
        runCatching { withContext(Dispatchers.IO) { ZyxfApi.listFolder(id, sort, desc) } }
            .onSuccess { entries = it; truncated = false }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    fun openFolder(entry: ZyxfApi.Entry) {
        query = ""
        searching = false
        stack.add(Crumb(entry.id, entry.name))
        scope.launch { loadFolder(entry.id) }
    }

    fun goTo(index: Int) {
        if (index >= stack.lastIndex) return
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        query = ""
        searching = false
        scope.launch { loadFolder(stack.last().id) }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            searching = false
            scope.launch { loadFolder(stack.last().id) }
            return
        }
        searching = true
        loading = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ZyxfApi.search(q) } }
                .onSuccess { entries = it.entries; truncated = it.truncated }
                .onFailure { error = it.message ?: "检索失败" }
            loading = false
        }
    }

    fun download(entry: ZyxfApi.Entry) {
        if (downloadState[entry.id] == DOWNLOADING) return
        downloadState[entry.id] = DOWNLOADING
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { ZyxfApi.download(context, entry.id) }.getOrNull()
            }
            downloadState[entry.id] = if (saved != null) "已保存到下载" else "下载失败"
        }
    }

    LaunchedEffect(Unit) { loadFolder(0) }

    val isWide = com.xjtu.toolbox.ui.isWideLayout()

    // 目录内返回上一级；已在根目录时交给宿主。
    // 宽屏多一级：右栏正在预览时先关预览（窄屏下预览是 Dialog，它自己接返回）。
    BackHandler(enabled = (isWide && previewing != null) || stack.size > 1 || searching) {
        when {
            isWide && previewing != null -> previewing = null
            searching -> { query = ""; searching = false; scope.launch { loadFolder(stack.last().id) } }
            else -> goTo(stack.lastIndex - 1)
        }
    }


    // 窄屏的预览是盖住全屏的浮层（含底部 Tab 栏），所以放在列表之外、由 Dialog 承载。
    // 宽屏不走这一支：预览已经长在右栏里。
    if (!isWide) previewing?.let { file ->
        ZyxfPreviewScreen(
            fileId = file.id,
            fileName = file.name,
            sizeBytes = file.sizeBytes,
            onBack = { previewing = null },
            onDownload = { download(file) },
        )
    }

    // 宽屏：左栏列表、右栏预览。窄屏下 TwoPane 只渲染列表，预览仍走上面那个全屏 Dialog。
    val listPane: @Composable () -> Unit = {
    Column(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .padding(contentPadding),
    ) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            onSearch = { runSearch() },
            onClear = { query = ""; searching = false; scope.launch { loadFolder(stack.last().id) } },
        )

        if (!searching && stack.size > 1) {
            Breadcrumb(stack = stack, onJump = ::goTo)
        }
        if (!searching) {
            SortBar(
                sort = sort,
                desc = desc,
                onPick = { picked ->
                    // 再点当前项＝翻转方向，换一项＝切字段并回到升序。
                    if (picked == sort) desc = !desc else { sort = picked; desc = picked == ZyxfApi.Sort.TIME }
                    scope.launch { loadFolder(stack.last().id) }
                },
            )
        }
        if (searching) {
            Text(
                if (truncated) "检索结果（较多，已截断；关键词更具体能看到更多）"
                else "检索结果 · ${entries.size} 条",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        val pullState = rememberPullToRefreshState()
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    if (searching) runSearch() else loadFolder(stack.last().id)
                    refreshing = false
                }
            },
            pullToRefreshState = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        error!!,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (searching) "没有匹配的资料" else "这个目录是空的",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                else -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .then(
                            scrollBehavior?.let {
                                Modifier.nestedScroll(it.nestedScrollConnection)
                            } ?: Modifier
                        )
                        .overScrollVertical(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { "${it.isFolder}-${it.id}" }) { entry ->
                        EntryRow(
                            entry = entry,
                            state = downloadState[entry.id],
                            onClick = {
                                if (entry.isFolder) openFolder(entry)
                                else if (ZyxfApi.previewable(entry.ext)) previewing = entry
                                else download(entry)
                            },
                            onDownload = { download(entry) },
                        )
                    }
                }
            }
        }
    }
    }

    com.xjtu.toolbox.ui.adaptive.TwoPane(
        list = listPane,
        detail = {
            val file = previewing
            if (file != null) {
                // 不包 Dialog：它就长在右栏里。
                PreviewContent(
                    fileId = file.id,
                    fileName = file.name,
                    sizeBytes = file.sizeBytes,
                    onBack = { previewing = null },
                    onDownload = { download(file) },
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
        },
        listWidth = 380.dp,
    )
}

private const val DOWNLOADING = "下载中…"

private data class Crumb(val id: Int, val name: String)

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
private fun EntryRow(
    entry: ZyxfApi.Entry,
    state: String?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.isFolder) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                val (tone, label) = extTone(entry.ext)
                val color = androidx.compose.ui.graphics.Color(tone)
                Box(
                    Modifier
                        .size(width = 30.dp, height = 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1,
                    )
                }
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
                    state,
                ).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        sub,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (state != null && state != DOWNLOADING) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!entry.isFolder) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDownload, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
