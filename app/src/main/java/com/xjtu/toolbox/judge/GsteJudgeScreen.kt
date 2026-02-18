package com.xjtu.toolbox.judge

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.GsteLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 研究生评教界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsteJudgeScreen(
    login: GsteLogin,
    onBack: () -> Unit
) {
    val api = remember { GsteJudgeApi(login) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var questionnaires by remember { mutableStateOf<List<GraduateQuestionnaire>>(emptyList()) }

    // 一键评教状态
    var isAutoJudging by remember { mutableStateOf(false) }
    var autoJudgeProgress by remember { mutableIntStateOf(0) }
    var autoJudgeTotal by remember { mutableIntStateOf(0) }
    var autoJudgeMessage by remember { mutableStateOf("") }

    // 加载问卷列表
    fun loadData() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    questionnaires = api.getQuestionnaires()
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

    // 分类列表
    val allowList = questionnaires.filter { it.ASSESSMENT == "allow" }
    val alreadyList = questionnaires.filter { it.ASSESSMENT == "already" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("研究生评教") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            // 顶部统计
            if (!isLoading && errorMessage == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${allowList.size}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text("待评", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${alreadyList.size}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("已评", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${questionnaires.size}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text("总计", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 一键好评按钮
            if (allowList.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (!isAutoJudging) {
                                scope.launch {
                                    isAutoJudging = true
                                    autoJudgeTotal = allowList.size
                                    autoJudgeProgress = 0
                                    autoJudgeMessage = "正在评教..."

                                    try {
                                        for ((index, q) in allowList.withIndex()) {
                                            autoJudgeMessage =
                                                "正在评教: ${q.KCMC} (${index + 1}/$autoJudgeTotal)"
                                            autoJudgeProgress = index

                                            withContext(Dispatchers.IO) {
                                                // 获取表单 HTML
                                                val html = api.getQuestionnaireHtml(q)
                                                // 解析表单
                                                val (meta, questions) = api.parseFormFromHtml(html)
                                                // 自动填写 (score=3 即"优")
                                                val formData =
                                                    api.autoFill(questions, meta, q, score = 3)
                                                // 提交
                                                api.submitQuestionnaire(q, formData)
                                            }

                                            autoJudgeProgress = index + 1
                                            delay(500) // 间隔避免被限流
                                        }
                                        autoJudgeMessage = "全部评教完成！"
                                        // 刷新列表
                                        loadData()
                                    } catch (e: Exception) {
                                        autoJudgeMessage = "评教出错: ${e.message}"
                                    } finally {
                                        isAutoJudging = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isAutoJudging
                    ) {
                        Icon(Icons.Default.ThumbUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isAutoJudging) "评教中..." else "一键全部好评")
                    }

                    // 进度条
                    if (isAutoJudging || autoJudgeMessage.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        if (isAutoJudging && autoJudgeTotal > 0) {
                            LinearProgressIndicator(
                                progress = { autoJudgeProgress.toFloat() / autoJudgeTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (autoJudgeMessage.isNotEmpty()) {
                            Text(
                                autoJudgeMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // 内容区域
            when {
                isLoading -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在加载评教列表...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                errorMessage != null -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                errorMessage!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(16.dp))
                            FilledTonalButton(onClick = { loadData() }) {
                                Text("重试")
                            }
                        }
                    }
                }
                questionnaires.isEmpty() -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.School,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "暂无评教课程",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        // 待评课程
                        if (allowList.isNotEmpty()) {
                            item {
                                Text(
                                    "待评课程",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            items(allowList, key = { "${it.KCBH}_${it.JSBH}_${it.BJID}" }) { q ->
                                GraduateQuestionnaireCard(q)
                            }
                        }

                        // 已评课程
                        if (alreadyList.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "已评课程",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(alreadyList, key = { "${it.KCBH}_${it.JSBH}_${it.BJID}_done" }) { q ->
                                GraduateQuestionnaireCard(q)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 研究生评教课程卡片
 */
@Composable
private fun GraduateQuestionnaireCard(q: GraduateQuestionnaire) {
    val isFinished = q.ASSESSMENT == "already"
    val statusColor = if (isFinished) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.tertiary
    val statusText = if (isFinished) "✓ 已评" else "待评"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        q.KCMC,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (q.KCYWMC.isNotEmpty() && q.KCYWMC != q.KCMC) {
                        Text(
                            q.KCYWMC,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "教师: ${q.JSXM}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "班级: ${q.BJMC}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(q.SKLS_DUTY, style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.height(24.dp)
                )
                if (q.TERMNAME.isNotEmpty()) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(q.TERMNAME, style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}
