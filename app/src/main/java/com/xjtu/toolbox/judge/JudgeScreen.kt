package com.xjtu.toolbox.judge

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.JwxtLogin
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JudgeScreen(
    login: JwxtLogin,
    username: String,
    onBack: () -> Unit
) {
    val api = remember { JudgeApi(login) }
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

    // 确认对话框状态（提升到顶层，避免条件分支内状态丢失）
    var showConfirmDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本科评教") },
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
        ) {
            // 切换Tab: 未评 / 已评
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 0) { Icon(Icons.Default.RateReview, null, Modifier.size(18.dp)) } }
                ) { Text("未评 (${unfinishedList.size})") }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 1) { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) } }
                ) { Text("已评 (${finishedList.size})") }
            }

            // 确认对话框（提升至顶层，不受 selectedTab 条件约束）
            if (showConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showConfirmDialog = false },
                        title = { Text("确认一键好评") },
                        text = { Text("将为 ${unfinishedList.size} 门课程全部提交好评，确定继续？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showConfirmDialog = false
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
                            }) { Text("确认") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDialog = false }) { Text("取消") }
                        }
                    )
            }

            // 一键好评按钮 + 进度条
            if (selectedTab == 0 && unfinishedList.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    FilledTonalButton(
                        onClick = { if (!isAutoJudging) showConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth(),
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
                                progress = { autoJudgeProgress.toFloat() / autoJudgeTotal },
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                items(displayList, key = { "${it.WJDM}_${it.JXBID}_${it.BPR}" }) { q ->
                                    QuestionnaireCard(
                                        q = q,
                                        finished = tab == 1,
                                        onUndo = if (tab == 1) { {
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
private fun QuestionnaireCard(q: Questionnaire, finished: Boolean, onUndo: (() -> Unit)? = null) {
    val typeLabel = when (q.PGLXDM) {
        "01" -> "期末评教"
        "05" -> "过程评教"
        else -> "评教"
    }
    val statusColor = if (finished) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "教师: ${q.BPJS}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (finished) "✓ 已评" else "待评",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (finished && onUndo != null) {
                OutlinedButton(
                    onClick = onUndo,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("撤回", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
