package com.xjtu.toolbox.degree

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.agent.AgentForward
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun credits(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()

/** 学业进度：培养方案树 + 按课程重算的学分，教务报告原值放旁边参考。 */
@Composable
fun DegreeScreen(
    source: DegreeSource,
    onBack: () -> Unit,
    /** 让小岱看没归位的课。没接小岱的端传 null，按钮不出现。 */
    onAskAgent: ((AgentForward) -> Unit)? = null,
) {
    val vm: DegreeViewModel = viewModel { DegreeViewModel(source) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()
    var opened by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf<DegreeLooseCourse?>(null) }
    val progress = vm.progress

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "学业进度",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    if (onAskAgent != null) IconButton(onClick = { onAskAgent(ASK_PROGRESS) }) {
                        Icon(Icons.Default.SmartToy, contentDescription = "问小岱")
                    }
                },
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            when {
                progress == null && vm.isLoading -> SkeletonList(Modifier.fillMaxSize().padding(top = glassTop), rows = 7, rowHeight = 84.dp)
                progress == null -> ErrorState(
                    message = FriendlyError.of(vm.error ?: RuntimeException("没有数据"), "加载培养方案"),
                    onRetry = vm::load,
                    modifier = Modifier.fillMaxSize().padding(top = glassTop),
                )
                else -> AppPullToRefresh(
                    isRefreshing = vm.isLoading,
                    onRefresh = vm::load,
                    scrollBehavior = scrollBehavior,
                    topPadding = glassTop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AdaptiveCardGrid(
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + glassTop, bottom = 16.dp),
                        spacing = 8.dp,
                    ) {
                        fullLineItem(key = "summary") { Summary(progress, vm) }
                        if (progress.unplaced.isNotEmpty()) {
                            fullLineItem(key = "unplaced") {
                                Unplaced(progress.unplaced, onPick = { picking = it }, onAskAgent = onAskAgent?.let { ask -> { ask(ASK_UNPLACED) } })
                            }
                        }
                        progress.nodes.forEach { node ->
                            fullLineItem(key = node.id) {
                                Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.defaultColors(color = AppCardColor)) {
                                    NodeBlock(node, 0, opened, onToggle = { id -> opened = if (id in opened) opened - id else opened + id }, onUndo = vm::undoConfirm)
                                }
                            }
                        }
                        if (progress.warnings.isNotEmpty()) {
                            fullLineItem(key = "warnings") {
                                Text(
                                    progress.warnings.joinToString("\n"),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val target = picking
    if (target != null && progress != null) {
        OverlayBottomSheet(show = true, title = "「${target.name}」抵哪一组", onDismissRequest = { picking = null }) {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "只记在本机，用来估算进度，教务并不知道。真要抵扣请向教务确认。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                progress.nodes.flatMap { it.flattened }.filter { it.children.isEmpty() }.forEach { node ->
                    TextButton(
                        text = "${node.name}（${credits(node.counted)}/${credits(node.required)}）",
                        onClick = {
                            vm.confirm(target, node.id)
                            picking = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private val ASK_PROGRESS = AgentForward(
    text = "看看我的学业进度，还差什么？",
    snapshot = "用户从学业进度页进来。用 get_degree_progress 查，重算结果和教务原值不同时都说清楚，结论以教务为准。",
)

private val ASK_UNPLACED = AgentForward(
    text = "帮我看看这些没归进培养方案的课，可能抵哪些要求？",
    snapshot = "用户从学业进度页进来。用 get_degree_progress 查 unplaced 和各组要求，按课程名和学分给出可能的对应和理由。这只是建议：请用户在学业进度页自己确认，并向教务核实。",
)

@Composable
private fun Summary(p: DegreeProgress, vm: DegreeViewModel) {
    val cs = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = AppCardColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.planName, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${credits(p.counted)} / ${credits(p.required)} 学分", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = cs.onSurface)
            ProgressLine(if (p.required > 0) (p.counted / p.required).toFloat() else 0f)
            p.officialTaken?.let {
                Text("教务报告显示 ${credits(it)}" + (p.calculatedAt?.let { at -> "（计算于 $at）" } ?: ""), style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            } ?: Text("没有读到教务的完成报告，可以点下面重新计算", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            vm.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = cs.error) }
            Text("按课程学分估算，仅供参考，以教务为准。", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.recalculating) {
                    CircularProgressIndicator(size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("教务计算中…", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                } else {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = vm::recalculate).padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = cs.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("让教务重新计算", style = MiuixTheme.textStyles.body2, color = cs.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    val cs = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.onSurface.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.primary))
    }
}

@Composable
private fun Unplaced(courses: List<DegreeLooseCourse>, onPick: (DegreeLooseCourse) -> Unit, onAskAgent: (() -> Unit)?) {
    val cs = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = AppCardColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("没归进方案的课（${courses.size} 门）", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Text("可能换过课程号，也可能真是方案外。点一门课可手动确认抵哪一组。", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            courses.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = c.passed) { onPick(c) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        "${credits(c.credit)} 学分" + (c.score?.let { " · $it" } ?: "") + if (c.passed) "" else " · 未通过",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (c.passed) cs.onSurfaceVariantSummary else cs.error,
                    )
                }
            }
            if (onAskAgent != null) TextButton(text = "让小岱看看能抵哪些", onClick = onAskAgent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NodeBlock(node: DegreeNode, depth: Int, opened: Set<String>, onToggle: (String) -> Unit, onUndo: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val isOpen = node.id in opened
    val expandable = node.children.isNotEmpty() || node.courses.isNotEmpty() || node.requirementText != null
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = expandable) { onToggle(node.id) }
                .padding(start = 16.dp + 12.dp * depth, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.name, style = MiuixTheme.textStyles.body1, color = cs.onSurface)
                if (node.differsFromOfficial) {
                    Text("教务显示 ${credits(node.officialTaken ?: 0.0)}，按课程实算 ${credits(node.counted)}", style = MiuixTheme.textStyles.footnote1, color = cs.error)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (node.required > 0) "${credits(node.counted)}/${credits(node.required)}" else "已修 ${credits(node.earned)}",
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Bold,
                color = if (node.satisfied) cs.primary else cs.onSurfaceVariantSummary,
            )
            if (expandable) Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = cs.onSurfaceVariantSummary)
        }
        if (isOpen) {
            node.requirementText?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(start = 28.dp + 12.dp * depth, end = 16.dp, bottom = 8.dp))
            }
            node.courses.forEach { c -> CourseRow(c, depth, onUndo) }
            node.children.forEach { NodeBlock(it, depth + 1, opened, onToggle, onUndo) }
        }
    }
}

@Composable
private fun CourseRow(c: DegreeCourse, depth: Int, onUndo: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val tail = buildString {
        append(credits(c.credit)).append(" 学分")
        c.score?.let { append(" · ").append(it) }
    }
    val tag = when {
        c.userConfirmed -> "已确认抵扣（未经教务确认）"
        c.matchedByName -> "按课程名匹配"
        c.status == DegreeCourseStatus.PASSED -> null
        else -> c.statusText.ifEmpty { "未修读" }
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = c.userConfirmed) { onUndo(c.code) }
            .padding(start = 28.dp + 12.dp * depth, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MiuixTheme.textStyles.body2, color = if (c.status == DegreeCourseStatus.PASSED) cs.onSurface else cs.onSurfaceVariantSummary)
            tag?.let {
                Text(
                    if (c.userConfirmed) "$it，点按撤销" else it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (c.status == DegreeCourseStatus.FAILED) cs.error else cs.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(tail, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
    }
}
