package com.xjtu.toolbox.judge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 研究生评教界面。与 [JudgeScreen] 同一套布局；数据来自 gste，
 * 填问卷要用的课程信息来自 gmis。两个站都由本页按需登录（用户主动进入，允许弹短信验证），
 * gmis 到一键评教时才登。
 */
@Composable
fun GraduateJudgeScreen(
    sessionManager: SessionManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var api by remember { mutableStateOf<GraduateJudgeApi?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 0=未评, 1=已评
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var unfinishedList by remember { mutableStateOf<List<GraduateQuestionnaire>>(emptyList()) }
    var finishedList by remember { mutableStateOf<List<GraduateQuestionnaire>>(emptyList()) }

    var isAutoJudging by remember { mutableStateOf(false) }
    var autoJudgeProgress by remember { mutableIntStateOf(0) }
    var autoJudgeTotal by remember { mutableIntStateOf(0) }
    var autoJudgeMessage by remember { mutableStateOf("") }

    val showConfirmDialog = remember { mutableStateOf(false) }

    suspend fun ensureApi(): GraduateJudgeApi = api ?: withContext(Dispatchers.IO) {
        GraduateJudgeApi(
            gste = sessionManager.ensureSite("gste", userInitiated = true),
            gmisProvider = { sessionManager.ensureSite("gmis", userInitiated = true) },
        )
    }.also { api = it }

    // 加载问卷列表。silent：下拉刷新时保住当前列表，只转指示器。
    fun loadData(silent: Boolean = false) {
        scope.launch {
            if (silent) isRefreshing = true else isLoading = true
            errorMessage = null
            try {
                val all = withContext(Dispatchers.IO) { ensureApi().getQuestionnaires() }
                unfinishedList = all.filter { it.assessment == "allow" }
                finishedList = all.filter { it.finished }
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "研究生评教",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                largeTitle = "研究生评教",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                bottomContent = {
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                        AppSegmentedTabs(
                            tabs = listOf("未评 (${unfinishedList.size})", "已评 (${finishedList.size})"),
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            modifier = Modifier.readableWidth(),
                        )
                    }
                },
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Column(
            Modifier
                .padding(padding.withoutTop(glass))
                .fillMaxSize()
                .glassSource(glass)
        ) {
            BackHandler(enabled = showConfirmDialog.value) { showConfirmDialog.value = false }
            OverlayDialog(
                show = showConfirmDialog.value,
                title = "确认一键好评",
                summary = "将为 ${unfinishedList.size} 门课程全部提交好评（系统不允许全部「优秀」，会有一项自动改为「良好」），确定继续？",
                onDismissRequest = { showConfirmDialog.value = false }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { showConfirmDialog.value = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确定",
                        onClick = {
                            showConfirmDialog.value = false
                            scope.launch {
                                isAutoJudging = true
                                autoJudgeTotal = unfinishedList.size
                                autoJudgeProgress = 0
                                autoJudgeMessage = "正在获取课程类型..."
                                var failCount = 0
                                var lastError = ""
                                try {
                                    val judgeApi = ensureApi()
                                    // 学位课/选修课整张成绩页取一次，所有问卷共用
                                    val degreeCourses = withContext(Dispatchers.IO) { judgeApi.getDegreeCourseNames() }
                                    for ((index, q) in unfinishedList.withIndex()) {
                                        autoJudgeMessage = "正在评教: ${q.kcmc} (${index + 1}/$autoJudgeTotal)"
                                        autoJudgeProgress = index
                                        try {
                                            withContext(Dispatchers.IO) { judgeApi.autoJudge(q, degreeCourses) }
                                        } catch (e: Exception) {
                                            failCount++
                                            lastError = "${q.kcmc}: ${e.message}"
                                        }
                                        autoJudgeProgress = index + 1
                                        delay(300) // 间隔避免被限流
                                    }
                                    autoJudgeMessage = if (failCount == 0) "全部评教完成！"
                                        else "${autoJudgeTotal - failCount}门成功，${failCount}门失败（$lastError）"
                                    loadData()
                                } catch (e: Exception) {
                                    autoJudgeMessage = "评教出错: ${e.message}"
                                } finally {
                                    isAutoJudging = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }

            val autoJudgeBlock: @Composable () -> Unit = {
                Column(
                    Modifier
                        .readableWidth()
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                ) {
                    Button(
                        onClick = { if (!isAutoJudging) showConfirmDialog.value = true },
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .height(44.dp)
                            .align(Alignment.CenterHorizontally),
                        enabled = !isAutoJudging
                    ) {
                        Icon(Icons.Default.ThumbUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isAutoJudging) "评教中..." else "一键全部好评")
                    }
                    if (isAutoJudging || autoJudgeMessage.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        if (isAutoJudging && autoJudgeTotal > 0) {
                            LinearProgressIndicator(
                                progress = autoJudgeProgress.toFloat() / autoJudgeTotal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (autoJudgeMessage.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    autoJudgeMessage,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isAutoJudging) {
                                    IconButton(
                                        onClick = { autoJudgeMessage = "" },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "关闭",
                                            modifier = Modifier.size(14.dp),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PullToRefresh(
                refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                isRefreshing = isRefreshing,
                onRefresh = { loadData(silent = true) },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = scrollBehavior,
                contentPadding = PaddingValues(top = glassTop),
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    isLoading -> LazyColumn(Modifier.fillMaxSize().padding(top = glassTop)) {
                        item {
                            Box(Modifier.fillParentMaxSize()) {
                                LoadingState(
                                    message = "正在加载评教列表...",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    errorMessage != null -> LazyColumn(Modifier.fillMaxSize().padding(top = glassTop)) {
                        item {
                            Box(Modifier.fillParentMaxSize()) {
                                ErrorState(
                                    message = errorMessage!!,
                                    onRetry = { loadData() },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    else -> {
                        AppTabPager(
                            pageCount = 2,
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            modifier = Modifier.fillMaxSize(),
                        ) { tab ->
                            val displayList = if (tab == 0) unfinishedList else finishedList
                            if (displayList.isEmpty()) {
                                LazyColumn(Modifier.fillMaxSize().padding(top = glassTop)) {
                                    item {
                                        Box(Modifier.fillParentMaxSize()) {
                                            EmptyState(
                                                title = if (tab == 0) "暂无待评课程" else "暂无已评课程",
                                                subtitle = if (tab == 0) "本学期所有课程均已完成评教" else "尚未完成任何课程评教",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            } else {
                                com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .overScrollVertical(),
                                    spacing = 10.dp,
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + glassTop, bottom = 12.dp)
                                ) {
                                    if (tab == 0) fullLineItem(key = "auto_judge") { autoJudgeBlock() }
                                    items(displayList, key = { it.key }) { q ->
                                        GraduateQuestionnaireCard(q = q)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraduateQuestionnaireCard(q: GraduateQuestionnaire) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                q.kcmc,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "教师: ${q.jsxm}" + if (q.skls_duty.isNotBlank()) "（${q.skls_duty}）" else "",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (q.termname.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MiuixTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            q.termname,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (q.finished) "✓ 已评" else "待评",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (q.finished) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.primaryVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
