package com.xjtu.toolbox.lms

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.rememberRetainedLazyStaggeredGridState
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xjtu.toolbox.nav.AppRoute

private const val TAG = "LmsScreen"

// ════════════════════════════════════════
//  页面 2 — 活动列表
// ════════════════════════════════════════

@Composable
internal fun ActivityListPage(
    api: LmsApi,
    cache: LmsPageCache,
    course: LmsCourseSummary,
    onBack: () -> Unit,
    onActivitySelected: (LmsActivity) -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val activities = cache.activities[course.id].orEmpty()
    var isLoading by remember { mutableStateOf(cache.activities[course.id] == null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedType by remember { mutableStateOf(cache.selectedTypes[course.id]) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── 批量下载（照 yan-xiaoo/XJTUToolBox）：勾选活动 → 选下载内容 → 后台收集并下载 ──
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Int>() }
    var showBatchDialog by remember { mutableStateOf(false) }
    val batchState by LmsBatchDownload.state.collectAsStateWithLifecycle()
    BackHandler(enabled = selecting) { selecting = false; picked.clear() }

    LaunchedEffect(selectedType) { cache.selectedTypes[course.id] = selectedType }

    val listState = rememberRetainedLazyStaggeredGridState("lms_activities_${course.id}")
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    fun loadActivities() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                cache.activities[course.id] = withContext(Dispatchers.IO) { api.getCourseActivities(course.id) }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(AppRoute.Lms(), onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadActivities error", e)
                errorMsg = "加载活动失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (cache.activities[course.id] == null) loadActivities() }

    val types = remember(activities) {
        activities.map { it.type }.distinct().sortedBy { it.ordinal }
    }

    val filtered = remember(activities, selectedType) {
        if (selectedType == null) activities
        else activities.filter { it.type == selectedType }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = course.name,
                largeTitle = course.name,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (activities.any { it.type in BATCH_TYPES }) {
                        IconButton(onClick = {
                            selecting = !selecting
                            if (!selecting) picked.clear()
                        }) {
                            Icon(
                                if (selecting) Icons.Default.Close else Icons.Default.Download,
                                contentDescription = if (selecting) "退出批量下载" else "批量下载",
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        if (showBatchDialog) {
            BatchDownloadDialog(
                count = picked.size,
                hasHomework = activities.any { it.id in picked && it.type == LmsActivityType.HOMEWORK },
                hasLesson = activities.any { it.id in picked && it.type == LmsActivityType.LESSON },
                onDismiss = { showBatchDialog = false },
                onStart = { options ->
                    showBatchDialog = false
                    LmsBatchDownload.start(context, api, course.name, activities.filter { it.id in picked }, options)
                    selecting = false
                    picked.clear()
                },
            )
        }
        Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            when {
                isLoading && activities.isEmpty() -> LoadingIndicator("加载活动列表…")
                errorMsg != null && activities.isEmpty() -> ErrorRetry(errorMsg!!) { loadActivities() }
                activities.isEmpty() -> EmptyState(Icons.Default.Inbox, "暂无活动", "该课程还没有发布任何活动")
                else -> {
                    // 宽屏卡片分两三列（见 AdaptiveCardGrid）；卡片自带外边距，间距给 0
                    AdaptiveCardGrid(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = glassTop, bottom = 16.dp),
                        spacing = 0.dp,
                        horizontalSpacing = 0.dp,
                    ) {
                        if (types.size > 1) {
                            fullLineItem(key = "type_filter") {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    cornerRadius = 22.dp,
                                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                                        Text(
                                            "课程内容",
                                            style = MiuixTheme.textStyles.subtitle,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                        Text(
                                            selectedType?.displayName() ?: "全部内容",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)
                                        )
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            AppFilterChip(selected = selectedType == null, onClick = { selectedType = null }, label = "全部")
                                            types.forEach { type ->
                                                AppFilterChip(
                                                    selected = selectedType == type,
                                                    onClick = { selectedType = type },
                                                    label = type.displayName()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val batch = batchState
                        if (batch !is LmsBatchDownload.State.Idle) fullLineItem(key = "batch") {
                            BatchProgressCard(batch)
                        }
                        fullLineItem(key = "count") {
                            Text(
                                if (selecting) "勾选要下载的活动（课件、作业、回放）" else "共 ${filtered.size} 个活动",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                        items(filtered, key = { it.id }) { activity ->
                            val canPick = activity.type in BATCH_TYPES
                            LmsActivityCard(
                                activity,
                                selected = if (selecting) activity.id in picked else null,
                                enabled = !selecting || canPick,
                            ) {
                                when {
                                    !selecting -> onActivitySelected(activity)
                                    !canPick -> Unit
                                    activity.id in picked -> picked.remove(activity.id)
                                    else -> picked.add(activity.id)
                                }
                            }
                        }
                        if (selecting) fullLineItem(key = "select_bar_space") { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
            if (selecting) {
                // 底部操作条：全选当前筛选下能下的 / 开始下载
                val pickable = filtered.filter { it.type in BATCH_TYPES }
                val allPicked = pickable.isNotEmpty() && pickable.all { it.id in picked }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(com.xjtu.toolbox.ui.components.AppCardColor)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = if (allPicked) "取消全选" else "全选",
                        onClick = {
                            if (allPicked) picked.removeAll(pickable.map { it.id }.toSet())
                            else pickable.forEach { if (it.id !in picked) picked.add(it.id) }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${picked.size}", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { showBatchDialog = true },
                        enabled = picked.isNotEmpty() && !LmsBatchDownload.isRunning,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(if (LmsBatchDownload.isRunning) "下载中" else "下载", color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
/** 能批量下的活动：课件、作业有附件，课堂有回放。其余（讨论、问卷、页面…）没有文件。 */
private val BATCH_TYPES = setOf(LmsActivityType.MATERIAL, LmsActivityType.HOMEWORK, LmsActivityType.LESSON)
@Composable
private fun BatchDownloadDialog(
    count: Int,
    hasHomework: Boolean,
    hasLesson: Boolean,
    onDismiss: () -> Unit,
    onStart: (LmsBatchDownload.Options) -> Unit,
) {
    var perActivity by remember { mutableStateOf(true) }
    var uploads by remember { mutableStateOf(true) }
    var submissions by remember { mutableStateOf(true) }
    var marked by remember { mutableStateOf(false) }
    var replays by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "批量下载 $count 个活动",
        summary = "存到「${LmsDownloadStore.publicDisplayPath()}课程名/」",
        onDismissRequest = onDismiss,
    ) {
        Column {
            BatchOptionRow("按活动分文件夹", "关掉就全放在课程文件夹里", perActivity) { perActivity = it }
            BatchOptionRow("课件与作业附件", "老师上传的文件", uploads) { uploads = it }
            if (hasHomework) {
                BatchOptionRow("我的提交", "自己交过的作业文件", submissions) { submissions = it }
                BatchOptionRow("批阅附件", "老师批改后的标注版", marked) { marked = it }
            }
            if (hasLesson) {
                BatchOptionRow("课堂回放", "视频较大，加入下载管理排队下载", replays) { replays = it }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "开始下载",
                    onClick = {
                        onStart(LmsBatchDownload.Options(perActivity, uploads, submissions && hasHomework, marked && hasHomework, replays && hasLesson))
                    },
                    enabled = uploads || (hasHomework && (submissions || marked)) || (hasLesson && replays),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
@Composable
private fun BatchOptionRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
@Composable
private fun BatchProgressCard(state: LmsBatchDownload.State) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (state) {
                is LmsBatchDownload.State.Collecting -> {
                    Text("正在整理 ${state.courseName}（${state.done + 1}/${state.total}）", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                    Text(state.current, style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                }
                is LmsBatchDownload.State.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在下载 ${state.done}/${state.total}" + if (state.failed > 0) "，失败 ${state.failed}" else "",
                            style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        TextButton(text = "停止", onClick = { LmsBatchDownload.cancel() })
                    }
                    Text(state.current, style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = if (state.total == 0) null else state.done.toFloat() / state.total,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is LmsBatchDownload.State.Finished -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (state.cancelled) "已停止" else "下载完成") + "：${state.ok} 个文件",
                                style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                            )
                            val notes = buildList {
                                if (state.forbidden > 0) add("${state.forbidden} 个活动已结束、学堂关闭了下载")
                                if (state.failed - state.forbidden > 0) add("${state.failed - state.forbidden} 个失败")
                                if (state.videosQueued > 0) add("${state.videosQueued} 段回放已加入下载管理")
                                if (state.ok == 0 && state.failed == 0 && state.videosQueued == 0) add("选中的活动里没有可下载的文件")
                            }
                            if (notes.isNotEmpty()) Text(notes.joinToString("；"), style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text("在「${LmsDownloadStore.publicDisplayPath()}${state.courseName}」",
                                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        TextButton(text = "知道了", onClick = { LmsBatchDownload.dismiss() })
                    }
                }
                LmsBatchDownload.State.Idle -> Unit
            }
        }
    }
}
@Composable
private fun LmsActivityCard(
    activity: LmsActivity,
    /** 批量选择时的勾选状态；null 表示不在选择模式。 */
    selected: Boolean? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (icon, color) = activityTypeVisual(activity.type)
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .graphicsLayerAlpha(if (enabled) 1f else 0.45f),
        // 和课程卡、其他页面的卡片同一个不透明底色。原来是半透明的 secondaryContainer，
        // 贴在页面灰底上几乎看不出卡片边界，内容从玻璃顶栏下面穿过时还会透出后面的东西
        colors = CardDefaults.defaultColors(
            color = com.xjtu.toolbox.ui.components.AppCardColor
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = color.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    activity.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        activity.type.displayName(),
                        fontSize = 12.sp,
                        color = color
                    )
                    if (activity.isClosed) {
                        Text("已结束", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    activity.startTime?.let {
                        Text(
                            formatLmsTime(it),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            if (selected != null) {
                if (enabled) Checkbox(
                    state = androidx.compose.ui.state.ToggleableState(selected),
                    onClick = null,
                )
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
private fun Modifier.graphicsLayerAlpha(a: Float): Modifier =
    if (a >= 1f) this else this.then(Modifier.alpha(a))
