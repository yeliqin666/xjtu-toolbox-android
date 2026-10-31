package com.xjtu.toolbox.dzpz

import androidx.compose.material.icons.automirrored.filled.Send
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAuthExpiry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.DrawCheckmark
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.SuccessGreen
import com.xjtu.toolbox.ui.components.breath
import com.xjtu.toolbox.ui.components.rememberLoopPhase
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.nav.AppRoute

/**
 * 电子成绩单下载页面
 *
 * 流程：加载表单 → 选择学校提供的类型 → 一键申请 → 自动提交 → 下载 PDF
 * UI 风格遵循项目 Miuix 风格，使用步骤进度条展示处理状态
 *
 * ## 两端各一份取数，屏自己不区分（2026-10-22 批6 改写）
 *
 * 能走完七步的端（Android / 桌面 / **serve 模式下的 `:web`**——它在契约 §5.12 里是**两段式**，
 * 七步里有几步是「同一发 HTTP」或空壳，见 `CampusTranscriptApi` 的 KDoc 表格）⇒
 * [TranscriptSource.canRunWorkflow] 为 `true`，画「一键申请成绩单」与那五步的步骤预览。
 *
 * 走不完的端（campus-api 形态的 `:web` —— 那条老路只有建单表单）⇒ `canRunWorkflow` 为 `false`，
 * 这块位置画的是 [TranscriptWorkflowGapCard]（说清缺哪几步、为什么缺）—— 点了必然失败的东西不摆出来。
 *
 * 「把 PDF 存到本端该存的地方」是宿主槽位 [TranscriptSaveSink]（Android = MediaStore / 桌面 = 文件选择框 /
 * 浏览器 = Blob + 下载锚点），本端不给就不画那个按钮。
 */
@Composable
fun TranscriptScreen(
    /** 本端的取数（Android / 桌面 = `:data` 的 `AppTranscriptSource`；serve 模式 = `:core` 的 `CampusTranscriptApi`）。 */
    source: TranscriptSource,
    onBack: () -> Unit,
    // 现在只有成绩单一种文件，先把参数留出来；P2 接了文件列表页以后，
    // 调用方会传不同的 DzpzDocument 进来。
    document: DzpzDocument = DzpzDocuments.TRANSCRIPT,
    /** 「把这份 PDF 存到本端该存的地方」——宿主能力（`:core` 不认识 MediaStore / 文件系统）。 */
    saveSink: TranscriptSaveSink? = null,
) {
    val authExpiry = LocalAuthExpiry.current
    val vm: TranscriptViewModel = viewModel(key = "transcript-${document.id}") {
        TranscriptViewModel(source, document)
    }
    LaunchedEffect(vm) { vm.authExpired.collect { authExpiry.onAuthExpired(AppRoute.Transcript, onBack) } }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    // ── UI ──
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "电子成绩单",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        when {
            vm.isLoading -> LoadingState(
                message = "正在连接成绩单服务...",
                modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).padding(top = glassTop)
            )
            vm.errorMessage != null -> ErrorState(
                message = vm.errorMessage.orEmpty(),
                onRetry = vm::loadForm,
                modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).padding(top = glassTop)
            )
            else -> {
                val ctx = vm.formContext ?: return@Scaffold
                val listState = rememberLazyListState()

                // 成功后自动滚动到底部
                LaunchedEffect(vm.workflowState) {
                    if (vm.workflowState == WorkflowState.SUCCESS) {
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
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("成绩单类型", fontWeight = FontWeight.SemiBold)
                                ctx.typeOptions.filterNot { it.cancelled }.forEach { option ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable(enabled = vm.workflowState != WorkflowState.RUNNING) { vm.selectType(option.value) }.padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = option.value == vm.selectedType, onClick = { vm.selectType(option.value) }, enabled = vm.workflowState != WorkflowState.RUNNING)
                                        Spacer(Modifier.width(12.dp))
                                        Text(option.name, modifier = Modifier.weight(1f))
                                    }
                                }
                                if (ctx.typeOptions.none { !it.cancelled }) Text("学校暂未提供可申请的类型")
                            }
                        }
                    }

                    // ── 工作流状态 ──
                    item {
                        if (source.canRunWorkflow) {
                            WorkflowProgressCard(
                                state = vm.workflowState,
                                progress = vm.workflowProgress,
                                onStart = vm::start,
                                enabled = vm.workflowState != WorkflowState.RUNNING && vm.selectedType != null,
                            )
                        } else {
                            TranscriptWorkflowGapCard()
                        }
                    }

                    // ── 下载完成区域 ──
                    if (vm.workflowState == WorkflowState.SUCCESS && vm.pdfBytes != null) {
                        item {
                            DownloadSuccessCard(
                                info = vm.downloadInfo!!,
                                pdfBytes = vm.pdfBytes!!,
                                sink = saveSink,
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

/** serve 模式下替代 [WorkflowProgressCard] 的那张卡（见屏的 KDoc）：契约§5.5 只端了七步里的第一步，第 2~7 步没有端点——这里把哪几步没有、为什么没有写在屏幕上，而不是画一个点了会失败的按钮。 */
@Composable
private fun TranscriptWorkflowGapCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "暂不支持申请成绩单",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "请使用 App 或桌面版申请。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
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
                        val phase = rememberLoopPhase()
                        Icon(
                            Icons.Default.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).graphicsLayer { alpha = breath(phase.value, low = 0.4f) },
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
                    DrawCheckmark(size = 56.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        progress,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = SuccessGreen
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
        Icons.AutoMirrored.Filled.Send to "提交审核",
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
    info: DownloadInfo,
    pdfBytes: ByteArray,
    /** 见屏的 `saveSink` 槽位。 */
    sink: TranscriptSaveSink?,
) {
    // 取成局部 val：`sink` 是函数参数，下面的 lambda（点击回调）里拿不到 smart cast
    val saver = sink
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
                    // 目标位置与本端存法都是宿主告诉屏的；本端没有保存路径就不画这一行。
                    if (info.filesize.isNotEmpty() && saver != null && saver.locationLabel.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${info.filesize} · 将保存到 ${saver.locationLabel}",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (saver != null) {
                // 操作按钮
                Button(
                    onClick = { saver.save(info.filename, pdfBytes) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(saver.actionLabel)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    saver.hint,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  PDF 落盘端口
// ══════════════════════════════════════

/** 「把这份 PDF 存到本端该存的地方」——宿主能力（`:core` 不认识 MediaStore / 文件系统）。 */
interface TranscriptSaveSink {
    /** 按钮上的字（Android = 「保存到下载」）。 */
    val actionLabel: String
    /** 按钮下面那句说明（怎么打开、存到哪儿）。 */
    val hint: String
    /** 界面上那一行「将保存到 X」的目标位置；空串 = 本端不显示这一行。 */
    val locationLabel: String

    /** 存一份 PDF。失败由实现方自己提示（`:core` 不管弹什么）。 */
    fun save(filename: String, bytes: ByteArray)
}
