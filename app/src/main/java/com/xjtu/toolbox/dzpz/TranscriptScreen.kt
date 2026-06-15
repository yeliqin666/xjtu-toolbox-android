package com.xjtu.toolbox.dzpz

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 电子成绩单下载页面
 *
 * 流程：加载表单 → 选择类型 → 一键申请 → 自动提交 → 下载 PDF
 * UI 风格遵循项目 Miuix 风格，使用步骤进度条展示处理状态
 */
@Composable
fun TranscriptScreen(
    site: SiteSession,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val api = remember(site) { TranscriptApi(site) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // ── State ──
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var formContext by remember { mutableStateOf<TranscriptApi.FormContext?>(null) }
    var selectedTypeIndex by remember { mutableIntStateOf(0) }

    // 工作流状态
    var workflowState by remember { mutableStateOf(WorkflowState.IDLE) }
    var workflowProgress by remember { mutableStateOf("") }
    var downloadInfo by remember { mutableStateOf<TranscriptApi.DownloadInfo?>(null) }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }

    // ── 加载表单 ──
    fun loadForm(workflowId: Int = TranscriptApi.WORKFLOW_MAP.values.first()) {
        isLoading = true
        errorMessage = null
        workflowState = WorkflowState.IDLE
        downloadInfo = null
        pdfBytes = null
        scope.launch {
            try {
                val ctx = withContext(Dispatchers.IO) {
                    api.loadCreateForm(workflowId)
                }
                formContext = ctx
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.DZPZ, Routes.TRANSCRIPT, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ── 一键申请 ──
    fun startWorkflow() {
        val ctx = formContext ?: return
        val typeOption = ctx.typeOptions.getOrNull(selectedTypeIndex) ?: return

        workflowState = WorkflowState.RUNNING
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Step 1: 联动查询
                    workflowProgress = "正在获取学籍信息..."
                    val linkage = api.getLinkageData(ctx, typeOption.value)

                    // Step 2: 生成成绩单
                    workflowProgress = "正在生成成绩单..."
                    val docId = api.generatePreviewPdf(ctx.workflowId, typeOption.value)

                    // Step 3: 第一次提交
                    workflowProgress = "正在提交申请..."
                    val firstResult = api.submitCreate(ctx, linkage, typeOption.value, docId)

                    // Step 4: 自动转发
                    workflowProgress = "正在处理签章..."
                    val secondResult = api.reloadAndForward(ctx, firstResult, typeOption.value)

                    // Step 5: 获取下载链接
                    workflowProgress = "正在获取下载链接..."
                    val dlInfo = api.getDownloadInfo(secondResult)
                    downloadInfo = dlInfo

                    // Step 6: 自动下载
                    workflowProgress = "正在下载成绩单..."
                    val bytes = api.downloadPdf(dlInfo.downloadUrl)
                    pdfBytes = bytes
                }
                workflowState = WorkflowState.SUCCESS
                workflowProgress = "成绩单已生成"
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.DZPZ, Routes.TRANSCRIPT, onBack)
            } catch (e: Exception) {
                workflowState = WorkflowState.ERROR
                workflowProgress = "申请失败: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { loadForm() }

    // ── UI ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = "电子成绩单",
                largeTitle = "电子成绩单",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> LoadingState(
                message = "正在连接成绩单服务...",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            errorMessage != null -> ErrorState(
                message = errorMessage!!,
                onRetry = { loadForm() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> {
                val ctx = formContext ?: return@Scaffold
                val listState = rememberLazyListState()

                // 成功后自动滚动到底部
                LaunchedEffect(workflowState) {
                    if (workflowState == WorkflowState.SUCCESS) {
                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── 成绩单类型选择 ──
                    item {
                        TranscriptTypeSelector(
                            options = ctx.typeOptions,
                            selectedIndex = selectedTypeIndex,
                            enabled = workflowState != WorkflowState.RUNNING,
                            onSelect = { selectedTypeIndex = it }
                        )
                    }

                    // ── 用户信息卡 ──
                    item {
                        InfoCard(
                            title = "申请信息",
                            items = listOf(
                                "申请日期" to ctx.defaultDate,
                                "所属单位" to "西安交通大学",
                                "份数" to "1"
                            )
                        )
                    }

                    // ── 工作流状态 ──
                    item {
                        WorkflowProgressCard(
                            state = workflowState,
                            progress = workflowProgress,
                            onStart = { startWorkflow() },
                            enabled = workflowState != WorkflowState.RUNNING
                        )
                    }

                    // ── 下载完成区域 ──
                    if (workflowState == WorkflowState.SUCCESS && pdfBytes != null) {
                        item {
                            DownloadSuccessCard(
                                info = downloadInfo!!,
                                pdfBytes = pdfBytes!!,
                                context = context
                            )
                        }
                    }

                    // 底部留白
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

// ══════════════════════════════════════
//  工作流状态
// ══════════════════════════════════════

enum class WorkflowState {
    IDLE,       // 等待用户点击
    RUNNING,    // 正在处理
    SUCCESS,    // 成功
    ERROR       // 失败
}

// ══════════════════════════════════════
//  成绩单类型选择器
// ══════════════════════════════════════

@Composable
private fun TranscriptTypeSelector(
    options: List<TranscriptApi.TranscriptTypeOption>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "选择成绩单类型",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            options.forEachIndexed { index, option ->
                TranscriptTypeSelectorItem(
                    option = option,
                    isSelected = index == selectedIndex,
                    enabled = enabled,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun TranscriptTypeSelectorItem(
    option: TranscriptApi.TranscriptTypeOption,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.defaultColors(
            color = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                   else Color.Transparent
        ),
        cornerRadius = 10.dp,
        onClick = if (enabled) {{ onClick() }} else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                option.name,
                fontSize = 14.sp,
                color = if (isSelected) MiuixTheme.colorScheme.primary
                       else MiuixTheme.colorScheme.onSurface
            )
        }
    }
}

// ══════════════════════════════════════
//  信息卡片
// ══════════════════════════════════════

@Composable
private fun InfoCard(
    title: String,
    items: List<Pair<String, String>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        value,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════
//  工作流进度卡片
// ══════════════════════════════════════

@Composable
private fun WorkflowProgressCard(
    state: WorkflowState,
    progress: String,
    onStart: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                WorkflowState.IDLE -> {
                    // 步骤预览
                    StepsPreview()
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("一键申请成绩单")
                    }
                }
                WorkflowState.RUNNING -> {
                    // 处理中动画
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseAlpha"
                        )
                        Icon(
                            Icons.Default.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.primary.copy(alpha = alpha)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            progress,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                WorkflowState.SUCCESS -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        progress,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4CAF50)
                    )
                }
                WorkflowState.ERROR -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MiuixTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        progress,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重新申请")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsPreview() {
    val steps = listOf(
        Icons.Default.Person to "验证学籍",
        Icons.Default.Description to "生成成绩单",
        Icons.Default.Send to "提交审核",
        Icons.Default.Verified to "签章认证",
        Icons.Default.Download to "下载文件"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (icon, label) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  下载成功卡片
// ══════════════════════════════════════

@Composable
private fun DownloadSuccessCard(
    info: TranscriptApi.DownloadInfo,
    pdfBytes: ByteArray,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 文件信息
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE53935).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "PDF",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE53935)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info.filename,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    if (info.filesize.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            info.filesize,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 操作按钮
            Button(
                onClick = { savePdfToDownloads(context, info.filename, pdfBytes) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("保存到下载")
            }
        }
    }
}

// ══════════════════════════════════════
//  文件操作
// ══════════════════════════════════════

private fun savePdfToDownloads(context: Context, filename: String, bytes: ByteArray) {
    try {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(context, "已保存到下载文件夹", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}


