package com.xjtu.toolbox.judge

import com.xjtu.toolbox.ui.components.FullPageState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 本科评教。 */
@Composable
fun JudgeScreen(site: SiteSession, username: String, onBack: () -> Unit) {
    val appLoginState = LocalAppLoginState.current
    val vm: JudgeViewModel<Questionnaire> = viewModel(key = "judge-${System.identityHashCode(site)}") {
        JudgeViewModel(UndergraduateJudgeSource(site, username))
    }
    LaunchedEffect(vm) { vm.authExpired.collect { appLoginState.handleAuthExpired(AppRoute.Judge, onBack) } }
    JudgeContent("本科评教", vm, onBack)
}

/** 研究生评教：数据来自 gste，填问卷要用的课程信息来自 gmis。 */
@Composable
fun GraduateJudgeScreen(sessionManager: SessionManager, onBack: () -> Unit) {
    val vm: JudgeViewModel<GraduateQuestionnaire> = viewModel { JudgeViewModel(GraduateJudgeSource(sessionManager)) }
    JudgeContent("研究生评教", vm, onBack)
}

@Composable
private fun <Q> JudgeContent(title: String, vm: JudgeViewModel<Q>, onBack: () -> Unit) {
    // 0=未评, 1=已评
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = title,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                // 分段标签不跟着滚：挂在顶栏里和顶栏一起做一整块玻璃，课程卡从它下面滚过去
                bottomContent = {
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                        AppSegmentedTabs(
                            tabs = listOf("未评 (${vm.unfinished.size})", "已评 (${vm.finished.size})"),
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
        // 课程卡分两三列铺开（AdaptiveCardGrid），标签和「一键好评」这种控件限宽居中
        Column(
            Modifier
                .padding(padding.withoutTop(glass))
                .fillMaxSize()
                .glassSource(glass)
        ) {
            BackHandler(enabled = confirming) { confirming = false }
            OverlayDialog(
                show = confirming,
                title = "确认一键好评",
                summary = "将为 ${vm.unfinished.size} 门课程全部提交好评${vm.confirmText}",
                onDismissRequest = { confirming = false }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(text = "取消", onClick = { confirming = false }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确定",
                        onClick = { confirming = false; vm.autoJudgeAll() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }

            PullToRefresh(
                refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                isRefreshing = vm.isRefreshing,
                onRefresh = { vm.load(silent = true) },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = scrollBehavior,
                // 下拉指示器从玻璃顶栏（含标签行）下面出来
                contentPadding = PaddingValues(top = glassTop),
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    vm.isLoading -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) { LoadingState(message = "正在加载评教列表...", modifier = Modifier.fillMaxSize()) }
                    vm.errorMessage != null -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) { ErrorState(message = vm.errorMessage.orEmpty(), onRetry = { vm.load() }, modifier = Modifier.fillMaxSize()) }
                    else -> AppTabPager(
                        pageCount = 2,
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        val list = if (tab == 0) vm.unfinished else vm.finished
                        if (list.isEmpty()) {
                            FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) {
                                EmptyState(
                                    title = if (tab == 0) "暂无待评课程" else "暂无已评课程",
                                    subtitle = if (tab == 0) "本学期所有课程均已完成评教" else "尚未完成任何课程评教",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid(
                                modifier = Modifier.fillMaxSize().overScrollVertical(),
                                spacing = 10.dp,
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + glassTop, bottom = 12.dp)
                            ) {
                                // 一键好评是「未评」列表的第一项，跟着列表滚
                                if (tab == 0) fullLineItem(key = "auto_judge") { AutoJudgeBlock(vm) { confirming = true } }
                                items(list, key = { vm.card(it).key }) { q ->
                                    val card = vm.card(q)
                                    QuestionnaireCard(
                                        card = card,
                                        finished = tab == 1,
                                        undoing = vm.undoingKey == card.key,
                                        onUndo = if (tab == 1 && vm.canUndo) { { vm.undo(q) } } else null,
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

/** 一键好评按钮 + 进度条 + 结果说明。 */
@Composable
private fun <Q> AutoJudgeBlock(vm: JudgeViewModel<Q>, onClick: () -> Unit) {
    Column(Modifier.readableWidth().fillMaxWidth().padding(bottom = 2.dp)) {
        Button(
            onClick = { if (!vm.isAutoJudging) onClick() },
            modifier = Modifier.fillMaxWidth(0.84f).height(44.dp).align(Alignment.CenterHorizontally),
            enabled = !vm.isAutoJudging
        ) {
            Icon(Icons.Default.ThumbUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (vm.isAutoJudging) "评教中..." else "一键全部好评")
        }
        if (!vm.isAutoJudging && vm.autoJudgeMessage.isEmpty()) return@Column
        Spacer(Modifier.height(8.dp))
        if (vm.isAutoJudging && vm.total > 0) {
            LinearProgressIndicator(progress = vm.progress.toFloat() / vm.total, modifier = Modifier.fillMaxWidth())
        }
        if (vm.autoJudgeMessage.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.autoJudgeMessage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                if (!vm.isAutoJudging) {
                    IconButton(onClick = { vm.autoJudgeMessage = "" }, modifier = Modifier.size(20.dp)) {
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

@Composable
private fun QuestionnaireCard(card: JudgeCard, finished: Boolean, undoing: Boolean, onUndo: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.course, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("教师: ${card.teacher}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.tag.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                card.tag,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (finished) "✓ 已评" else "待评",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (finished) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.primaryVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (finished && onUndo != null) {
                Button(
                    onClick = onUndo,
                    enabled = !undoing,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer)
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
