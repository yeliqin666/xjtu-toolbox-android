package com.xjtu.toolbox.dzpz

import androidx.compose.ui.graphics.graphicsLayer
import android.content.ContentValues
import android.content.Context
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
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.lms.LmsDownloadRecord
import com.xjtu.toolbox.lms.LmsDownloadStore
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.data.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.nav.AppRoute

/**
 * 电子成绩单下载页面
 *
 * 流程：加载表单 → 选择类型 → 一键申请 → 自动提交 → 下载 PDF
 * UI 风格遵循项目 Miuix 风格，使用步骤进度条展示处理状态
 */
@Composable
fun TranscriptScreen(
    site: SiteSession,
    onBack: () -> Unit,
    // 现在只有成绩单一种文件，先把参数留出来；P2 接了文件列表页以后，
    // 调用方会传不同的 DzpzDocument 进来。
    document: DzpzDocument = DzpzDocuments.TRANSCRIPT,
) {
    val appLoginState = LocalAppLoginState.current
    val api = remember(site) { TranscriptApi(site) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialStore = remember(context) { CredentialStore(context) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // ── State ──
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var formContext by remember { mutableStateOf<TranscriptApi.FormContext?>(null) }
    var selectedTypeIndex by remember { mutableIntStateOf(0) }

    // 身份：校友身份从账号上判断不出来（本科生/研究生账号体系里没有这一档），
    // 默认值按当前登录账号的类型来，用户可以在页面顶部手动切换成校友身份。
    val defaultIdentity = remember(credentialStore) {
        if (credentialStore.accountType == com.xjtu.toolbox.auth.AccountType.POSTGRADUATE) {
            DzpzIdentity.POSTGRAD
        } else {
            DzpzIdentity.UNDERGRAD
        }
    }
    var selectedIdentity by remember { mutableStateOf(defaultIdentity) }

    // 工作流状态
    var workflowState by remember { mutableStateOf(WorkflowState.IDLE) }
    var workflowProgress by remember { mutableStateOf("") }
    var downloadInfo by remember { mutableStateOf<TranscriptApi.DownloadInfo?>(null) }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }

    // ── 加载表单 ──
    // P1 修的 bug：原来这里的默认参数永远取 WORKFLOW_MAP 的第一个值（在校本科生
    // 29），两个调用点都没传参，所有身份都被当成本科生处理。现在按 document +
    // 选中的身份查 workflowId。
    fun loadForm(identity: DzpzIdentity = selectedIdentity) {
        val workflowId = document.workflowIds[identity] ?: return
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
                appLoginState.handleAuthExpired(AppRoute.Transcript, onBack)
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
                appLoginState.handleAuthExpired(AppRoute.Transcript, onBack)
            } catch (e: Exception) {
                workflowState = WorkflowState.ERROR
                workflowProgress = "申请失败: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { loadForm() }

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    // ── UI ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = "电子成绩单",
                largeTitle = "电子成绩单",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        when {
            isLoading -> LoadingState(
                message = "正在连接成绩单服务...",
                modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).padding(top = glassTop)
            )
            errorMessage != null -> ErrorState(
                message = errorMessage!!,
                onRetry = { loadForm() },
                modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).padding(top = glassTop)
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
                        .padding(padding.withoutTop(glass))
                        .glassSource(glass)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── 身份选择：默认按账号类型，校友身份需要手动切换 ──
                    item {
                        IdentitySelector(
                            selected = selectedIdentity,
                            enabled = workflowState != WorkflowState.RUNNING,
                            onSelect = { identity ->
                                selectedIdentity = identity
                                loadForm(identity)
                            }
                        )
                    }

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
//  身份选择器
// ══════════════════════════════════════

/**
 * 四档身份：在校本科生 / 研究生 / 已毕业本科（校友）/ 研究生校友。默认值按账号
 * 类型来，校友身份判断不出来，需要用户自己切；切换后会用对应的 workflowId
 * 重新调一次 loadForm（见 plan2 §7.2）。
 */
@Composable
private fun IdentitySelector(
    selected: DzpzIdentity,
    enabled: Boolean,
    onSelect: (DzpzIdentity) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "身份",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            DzpzIdentity.entries.forEach { identity ->
                TranscriptTypeSelectorItem(
                    label = identity.label,
                    isSelected = identity == selected,
                    enabled = enabled,
                    onClick = { onSelect(identity) }
                )
            }
        }
    }
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
                    label = option.name,
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
    label: String,
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
                label,
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
            // 必须 fillMaxWidth：IDLE/RUNNING/ERROR 三态都有撑满宽度的按钮或进度条，
            // 唯独 SUCCESS 态只有图标 + 一行字，Column 会缩到内容宽度并贴在卡片左侧，
            // 看起来就是「已生成」没居中。
            Modifier.fillMaxWidth().padding(16.dp),
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
                        // 拿 State 本身，只在 graphicsLayer 里读：闪烁时不每帧重组
                        val alpha = infiniteTransition.animateFloat(
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
                            modifier = Modifier.size(20.dp).graphicsLayer { this.alpha = alpha.value },
                            tint = MiuixTheme.colorScheme.primary
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
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE53935).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFE53935))
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
                            "${info.filesize} · 将保存到 ${LmsDownloadStore.publicDisplayPath()}",
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
            Spacer(Modifier.height(8.dp))
            Text(
                "保存后会出现在「设置 - 下载管理」里，可由系统文件管理器或 PDF 阅读器打开。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
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
            put(MediaStore.Downloads.RELATIVE_PATH, LmsDownloadStore.RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            LmsDownloadStore.add(
                context,
                LmsDownloadRecord(
                    name = filename,
                    mimeType = "application/pdf",
                    uri = uri.toString(),
                    savedAt = System.currentTimeMillis(),
                    category = LmsDownloadStore.CATEGORY_TRANSCRIPT
                )
            )
            Toast.makeText(context, "已保存到下载管理", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}


