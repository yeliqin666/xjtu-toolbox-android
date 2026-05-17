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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbUp
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 研究生评教界面
 */
@Composable
fun GsteJudgeScreen(
    site: SiteSession,
    onBack: () -> Unit
) {
    val api = remember(site) { GsteJudgeApi(site) }
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

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "研究生评教",
                color = MiuixTheme.colorScheme.surfaceVariant,
                largeTitle = "研究生评教",
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
            // 顶部统计
            if (!isLoading && errorMessage == null) {
                top.yukonga.miuix.kmp.basic.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer
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
                                style = MiuixTheme.textStyles.headline1,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.primaryVariant
                            )
                            Text("待评", style = MiuixTheme.textStyles.footnote1)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${alreadyList.size}",
                                style = MiuixTheme.textStyles.headline1,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Text("已评", style = MiuixTheme.textStyles.footnote1)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${questionnaires.size}",
                                style = MiuixTheme.textStyles.headline1,
                                fontWeight = FontWeight.Bold
                            )
                            Text("总计", style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
            }

            // 一键好评按钮
            if (allowList.isNotEmpty()) {
                val showConfirmDialog = remember { mutableStateOf(false) }

                // 确认对话框（研究生评教不可撤销，需更醒目的警告）
                BackHandler(enabled = showConfirmDialog.value) { showConfirmDialog.value = false }
                OverlayBottomSheet(
                    show = showConfirmDialog.value,
                    title = "⚠️ 确认一键好评",
                    onDismissRequest = { showConfirmDialog.value = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp)
                    ) {
                        Text("将为 ${allowList.size} 门课程全部提交好评。\n\n注意：研究生评教提交后不可撤销，请确认后操作。")
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
                                    autoJudgeTotal = allowList.size
                                    autoJudgeProgress = 0
                                    autoJudgeMessage = "正在评教..."
                                    var failCount = 0
                                    var lastError = ""

                                    try {
                                        for ((index, q) in allowList.withIndex()) {
                                            autoJudgeMessage =
                                                "正在评教: ${q.KCMC} (${index + 1}/$autoJudgeTotal)"
                                            autoJudgeProgress = index

                                            try {
                                                withContext(Dispatchers.IO) {
                                                    val html = api.getQuestionnaireHtml(q)
                                                    val (meta, questions) = api.parseFormFromHtml(html)
                                                    val formData =
                                                        api.autoFill(questions, meta, q, score = 3)
                                                    api.submitQuestionnaire(q, formData)
                                                }
                                            } catch (e: Exception) {
                                                failCount++
                                                lastError = "${q.KCMC}: ${e.message}"
                                            }

                                            autoJudgeProgress = index + 1
                                            delay(500) // 间隔避免被限流
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
                            }, modifier = Modifier.weight(1f)) { Text("确认提交") }
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
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

                    // 进度条
                    if (isAutoJudging || autoJudgeMessage.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        if (isAutoJudging && autoJudgeTotal > 0) {
                            LinearProgressIndicator(
                                progress = autoJudgeProgress.toFloat() / autoJudgeTotal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (autoJudgeMessage.isNotEmpty()) {
                            Text(
                                autoJudgeMessage,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                            Text("正在加载评教列表...", style = MiuixTheme.textStyles.body2)
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
                                color = MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.body1
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { loadData() }) {
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
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "暂无评教课程",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .overScrollVertical()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        // 待评课程
                        if (allowList.isNotEmpty()) {
                            item {
                                Text(
                                    "待评课程",
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primaryVariant
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
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary
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
    val statusColor = if (isFinished) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.primaryVariant
    val statusText = if (isFinished) "✓ 已评" else "待评"

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp
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
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (q.KCYWMC.isNotEmpty() && q.KCYWMC != q.KCMC) {
                        Text(
                            q.KCYWMC,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    statusText,
                    style = MiuixTheme.textStyles.body2,
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
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Text(
                    "班级: ${q.BJMC}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSuggestionChip(
                    onClick = {},
                    label = q.SKLS_DUTY,
                    modifier = Modifier.height(24.dp)
                )
                if (q.TERMNAME.isNotEmpty()) {
                    AppSuggestionChip(
                        onClick = {},
                        label = q.TERMNAME,
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}
