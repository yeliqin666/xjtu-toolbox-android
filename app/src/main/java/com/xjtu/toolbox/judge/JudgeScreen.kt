package com.xjtu.toolbox.judge

import androidx.activity.compose.BackHandler
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本科评教界面
 */
@Composable
fun JudgeScreen(
    site: SiteSession,
    username: String,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val api = remember(site) { JudgeApi(site) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 0=未评, 1=已评
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var unfinishedList by remember { mutableStateOf<List<Questionnaire>>(emptyList()) }
    var finishedList by remember { mutableStateOf<List<Questionnaire>>(emptyList()) }

    // 一键评教状态
    var isAutoJudging by remember { mutableStateOf(false) }
    var autoJudgeProgress by remember { mutableIntStateOf(0) }
    var autoJudgeTotal by remember { mutableIntStateOf(0) }
    var autoJudgeMessage by remember { mutableStateOf("") }
    var undoingKey by remember { mutableStateOf<String?>(null) }

    // 确认对话框状态（提升到顶层，避免条件分支内状态丢失）
    val showConfirmDialog = remember { mutableStateOf(false) }

    // 加载问卷列表
    fun loadData() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    unfinishedList = api.unfinishedQuestionnaires()
                    finishedList = api.finishedQuestionnaires()
                }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JWXT, Routes.JUDGE, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "本科评教",
                color = MiuixTheme.colorScheme.surfaceVariant,
                largeTitle = "本科评教",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // 切换Tab: 未评 / 已评
            Surface(modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.surfaceVariant) {
                TabRowWithContour(
                    tabs = listOf("未评 (${unfinishedList.size})", "已评 (${finishedList.size})"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // 确认对话框（提升至顶层，不受 selectedTab 条件约束）
            BackHandler(enabled = showConfirmDialog.value) { showConfirmDialog.value = false }
            OverlayBottomSheet(
                    show = showConfirmDialog.value,
                    title = "确认一键好评",
                    onDismissRequest = { showConfirmDialog.value = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp)
                    ) {
                        Text("将为 ${unfinishedList.size} 门课程全部提交好评，确定继续？")
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { showConfirmDialog.value = false },
                                modifier = Modifier.weight(1f),
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer)
                            ) { Text("取消", color = MiuixTheme.colorScheme.onSecondaryContainer) }
                            Button(onClick = {
                                showConfirmDialog.value = false
                                scope.launch {
                                    isAutoJudging = true
                                    autoJudgeTotal = unfinishedList.size
                                    autoJudgeProgress = 0
                                    autoJudgeMessage = "正在评教..."
                                    var failCount = 0
                                    var lastError = ""

                                    try {
                                        for ((index, q) in unfinishedList.withIndex()) {
                                            autoJudgeMessage = "正在评教: ${q.KCM} (${index + 1}/$autoJudgeTotal)"
                                            autoJudgeProgress = index

                                            try {
                                                withContext(Dispatchers.IO) {
                                                    val filledData = api.autoFillQuestionnaire(q, username)
                                                    api.submitQuestionnaire(q, filledData)
                                                }
                                            } catch (e: Exception) {
                                                failCount++
                                                lastError = "${q.KCM}: ${e.message}"
                                            }

                                            autoJudgeProgress = index + 1
                                            delay(300) // 间隔避免被限流
                                        }
                                        autoJudgeMessage = if (failCount == 0) "全部评教完成！"
                                            else "${autoJudgeTotal - failCount}门成功，${failCount}门失败（$lastError）"
                                        // 刷新列表
                                        loadData()
                                    } catch (e: Exception) {
                                        autoJudgeMessage = "评教出错: ${e.message}"
                                    } finally {
                                        isAutoJudging = false
                                    }
                                }
                            }, modifier = Modifier.weight(1f)) { Text("确认") }
                        }
                    }
                }

            // 一键好评按钮 + 进度条
            if (selectedTab == 0 && unfinishedList.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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

                    // 进度条 + 结果消息
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

            // 内容区域
            when {
                isLoading -> LoadingState(
                    message = "正在加载评教列表...",
                    modifier = Modifier.fillMaxSize()
                )
                errorMessage != null -> ErrorState(
                    message = errorMessage!!,
                    onRetry = { loadData() },
                    modifier = Modifier.fillMaxSize()
                )
                else -> {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            fadeIn() + slideInHorizontally {
                                if (targetState > initialState) it else -it
                            } togetherWith fadeOut() + slideOutHorizontally {
                                if (targetState > initialState) -it else it
                            }
                        },
                        label = "judgeTab"
                    ) { tab ->
                        val displayList = if (tab == 0) unfinishedList else finishedList
                        if (displayList.isEmpty()) {
                            EmptyState(
                                title = if (tab == 0) "暂无待评课程" else "暂无已评课程",
                                subtitle = if (tab == 0) "本学期所有课程均已完成评教" else "尚未完成任何课程评教",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            LazyColumn(
                                Modifier
                                    .fillMaxSize()
                                    .overScrollVertical()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                items(displayList, key = { "${it.WJDM}_${it.JXBID}_${it.BPR}" }) { q ->
                                    val qKey = "${q.WJDM}_${q.JXBID}_${q.BPR}"
                                    QuestionnaireCard(
                                        q = q,
                                        finished = tab == 1,
                                        undoing = undoingKey == qKey,
                                        onUndo = if (tab == 1) { {
                                            undoingKey = qKey
                                            scope.launch {
                                                try {
                                                    val (success, msg) = withContext(Dispatchers.IO) {
                                                        api.editQuestionnaire(q, username)
                                                    }
                                                    if (success) {
                                                        loadData()
                                                    } else {
                                                        errorMessage = "撤回失败: $msg"
                                                    }
                                                } catch (e: Exception) {
                                                    errorMessage = "撤回失败: ${e.message}"
                                                } finally {
                                                    undoingKey = null
                                                }
                                            }
                                        } } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个评教课程卡片
 */
@Composable
private fun QuestionnaireCard(
    q: Questionnaire,
    finished: Boolean,
    undoing: Boolean = false,
    onUndo: (() -> Unit)? = null
) {
    val typeLabel = when (q.PGLXDM) {
        "01" -> "期末评教"
        "05" -> "过程评教"
        else -> "评教"
    }
    val statusColor = if (finished) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.primaryVariant
    }

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    q.KCM,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "教师: ${q.BPJS}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MiuixTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            typeLabel,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (finished) "✓ 已评" else "待评",
                        style = MiuixTheme.textStyles.footnote1,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (finished && onUndo != null) {
                Button(
                    onClick = onUndo,
                    enabled = !undoing,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        if (undoing) "撤回中..." else "撤回评教",
                        color = MiuixTheme.colorScheme.onSecondaryContainer,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
