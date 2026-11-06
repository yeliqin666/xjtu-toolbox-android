package com.xjtu.toolbox.degree

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun credits(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()

private fun fraction(done: Double, total: Double): Float = if (total > 0) (done / total).toFloat().coerceIn(0f, 1f) else 0f

/**
 * 学业进度。两种模式：
 * - 我的方案：方案树 + 按课程重算的学分，教务报告原值放旁边参考；
 * - 看别的方案（规划转专业）：用成绩对照估算能算多少、还差什么，没有教务报告。
 */
@Composable
fun DegreeScreen(
    source: DegreeSource,
    onBack: () -> Unit,
    /** 让小岱看。没接小岱的端传 null，入口不出现。 */
    onAskAgent: ((AgentForward) -> Unit)? = null,
) {
    val vm: DegreeViewModel = viewModel { DegreeViewModel(source) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()
    var opened by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf<DegreeLooseCourse?>(null) }
    var choosingPlan by remember { mutableStateOf(false) }
    var showUnplaced by remember { mutableStateOf(false) }
    val progress = vm.progress
    val toggle = { id: String -> opened = if (id in opened) opened - id else opened + id }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "学业进度",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    if (onAskAgent != null && progress != null) com.xjtu.toolbox.ui.components.AskAgentAction(
                        summary = if (vm.browsing) "让小岱分析这个培养方案" else "让小岱分析你的学业进度",
                    ) {
                        onAskAgent(if (vm.browsing) askAboutPlan(progress.planName) else ASK_PROGRESS)
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
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + glassTop, bottom = 24.dp),
                        spacing = 12.dp,
                    ) {
                        fullLineItem(key = "hero_${progress.planCode}") {
                            Box(Modifier.enterOnce(0)) {
                                if (vm.browsing) BrowseHero(progress, onChoose = { choosingPlan = true }, onBackToMine = vm::backToMine)
                                else ProgressHero(progress, vm, onChoose = { choosingPlan = true })
                            }
                        }
                        if (progress.unplaced.isNotEmpty()) {
                            fullLineItem(key = "unplaced_${progress.planCode}") {
                                Box(Modifier.enterOnce(1)) {
                                    Unplaced(
                                        progress.unplaced,
                                        open = showUnplaced,
                                        otherPlan = vm.browsing,
                                        onToggle = { showUnplaced = !showUnplaced },
                                        onPick = { picking = it },
                                        onAskAgent = onAskAgent?.let { ask -> { ask(if (vm.browsing) askAboutPlan(progress.planName) else ASK_UNPLACED) } },
                                    )
                                }
                            }
                        }
                        val nodes = progress.nodes
                        items(nodes.size, key = { "${progress.planCode}_${nodes[it].id}" }) { i ->
                            Box(Modifier.enterOnce(i + 2)) { RootCard(nodes[i], opened, toggle, vm::undoConfirm) }
                        }
                        if (progress.warnings.isNotEmpty()) {
                            fullLineItem(key = "warnings") {
                                Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        progress.warnings.joinToString("\n"),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Overlay 系弹窗要挂在 Scaffold 里面才有宿主，放在外面弹不出来
        if (choosingPlan) PlanPicker(vm, onDismiss = { choosingPlan = false })

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
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                vm.confirm(target, node.id)
                                picking = null
                            }.padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Text(node.name, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text("${credits(node.counted)}/${credits(node.required)}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
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

private fun askAboutPlan(name: String) = AgentForward(
    text = "如果我转到「$name」，大概还要补哪些课？",
    snapshot = "用户在学业进度页看别的培养方案，可能在规划转专业。用 get_degree_progress 的 plan 参数查这个方案（名称：$name），说明现有的课能算多少、要补哪些、哪些用不上。转专业政策和学分认定以学院当年文件为准，没有收录就如实说。",
)

@Composable
private fun PlanChooser(name: String, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.SwapHoriz, null, tint = cs.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(2.dp))
        Text("换方案", style = MiuixTheme.textStyles.footnote1, color = cs.primary)
    }
}

@Composable
private fun ProgressHero(p: DegreeProgress, vm: DegreeViewModel, onChoose: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val remaining = (p.required - p.counted).coerceAtLeast(0.0)
    val unmet = p.nodes.flatMap { it.flattened }.count { it.children.isEmpty() && !it.satisfied }
    Surface(color = AppCardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlanChooser(p.planName, onChoose)
            Row(verticalAlignment = Alignment.Bottom) {
                com.xjtu.toolbox.ui.components.RollingNumberText(
                    value = p.counted,
                    format = { credits(it.toDouble()) },
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                Text(" / ${credits(p.required)} 学分", style = MiuixTheme.textStyles.body2, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 4.dp))
            }
            Bar(fraction(p.counted, p.required), height = 8.dp)
            Text(
                if (remaining <= 0.0) "学分够了" + if (unmet > 0) "，还有 $unmet 组没达标" else ""
                else "还差 ${credits(remaining)} 学分" + if (unmet > 0) " · $unmet 组没达标" else "",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.officialTaken?.let { "教务显示 ${credits(it)}" + (p.calculatedAt?.let { at -> " · 计算于 ${at.take(16)}" } ?: "") } ?: "还没读到教务的完成报告",
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                if (vm.recalculating) {
                    CircularProgressIndicator(size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("计算中", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                } else {
                    Text(
                        "重新计算",
                        style = MiuixTheme.textStyles.footnote1,
                        color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = vm::recalculate).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            vm.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = cs.error) }
            InProgressLine(p)
            Text("按课程学分估算，仅供参考，以教务为准", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun BrowseHero(p: DegreeProgress, onChoose: () -> Unit, onBackToMine: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Surface(color = AppCardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlanChooser(p.planName, onChoose)
            Row(verticalAlignment = Alignment.Bottom) {
                com.xjtu.toolbox.ui.components.RollingNumberText(
                    value = p.counted,
                    format = { credits(it.toDouble()) },
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                Text(" / ${credits(p.required)} 学分", style = MiuixTheme.textStyles.body2, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 4.dp))
            }
            Bar(fraction(p.counted, p.required), height = 8.dp)
            val remaining = (p.required - p.counted).coerceAtLeast(0.0)
            Text(
                if (remaining <= 0.0) "转过去学分够了" else "转过去还差 ${credits(remaining)} 学分",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
            )
            InProgressLine(p)
            Text("这不是你现在的方案。按你的成绩估算，课程号或课名对不上的不计入，能否认定以学院为准。", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBackToMine).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Close, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("回到我的方案", style = MiuixTheme.textStyles.body2, color = cs.primary)
            }
        }
    }
}

/** 在修的课不计入上面的学分，单独说一句。 */
@Composable
private fun InProgressLine(p: DegreeProgress) {
    if (p.inProgressCount == 0) return
    Text(
        "在修或待出成绩 ${p.inProgressCount} 门" + (if (p.inProgressCredits > 0) "，${credits(p.inProgressCredits)} 学分" else "") + "，出成绩后计入",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
private fun Bar(fraction: Float, height: androidx.compose.ui.unit.Dp = 5.dp, color: Color = MiuixTheme.colorScheme.primary) {
    val cs = MiuixTheme.colorScheme
    val reveal = com.xjtu.toolbox.ui.components.rememberRevealProgress(key = fraction)
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(cs.onSurface.copy(alpha = 0.08f))) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .graphicsLayer {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        scaleX = reveal.value
                    }
                    .clip(RoundedCornerShape(height / 2)).background(color),
            )
        }
    }
}

@Composable
private fun Unplaced(courses: List<DegreeLooseCourse>, open: Boolean, otherPlan: Boolean, onToggle: () -> Unit, onPick: (DegreeLooseCourse) -> Unit, onAskAgent: (() -> Unit)?) {
    val cs = MiuixTheme.colorScheme
    Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (otherPlan) "${courses.size} 门课在这个方案里没对上" else "${courses.size} 门课没归进方案", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text(if (otherPlan) "转过去可能用不上，也可能可以申请认定" else "可能换过课程号，也可能真是方案外", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                }
                Chevron(open)
            }
            if (open) {
                courses.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = c.passed && !otherPlan) { onPick(c) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                c.inProgress -> DegreeBuilder.IN_PROGRESS_TEXT
                                else -> "${credits(c.credit)} 学分" + (c.score?.let { " · $it" } ?: "") + if (c.passed) "" else " · 未通过"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = when {
                                c.inProgress -> cs.primary
                                c.passed -> cs.onSurfaceVariantSummary
                                else -> cs.error
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (otherPlan) "让小岱帮你看看能不能认定" else "点一门课，手动确认抵哪一组", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    if (onAskAgent != null) {
                        Text(
                            "问小岱",
                            style = MiuixTheme.textStyles.body2,
                            color = cs.primary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAskAgent).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 顶层一组一张卡：标题 + 进度条，子组和课程收在里面。 */
@Composable
private fun RootCard(node: DegreeNode, opened: Set<String>, onToggle: (String) -> Unit, onUndo: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(vertical = 14.dp)) {
            val leaf = node.children.isEmpty()
            Column(
                Modifier.fillMaxWidth().clickable(enabled = leaf) { onToggle(node.id) }.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(node.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Amount(node, emphasize = true)
                    if (leaf) Chevron(node.id in opened)
                }
                if (node.required > 0) Bar(fraction(node.counted, node.required))
                Mismatch(node)
            }
            if (leaf) {
                if (node.id in opened) Column(Modifier.padding(top = 8.dp)) { NodeBody(node, depth = 0, onUndo) }
            } else {
                Spacer(Modifier.height(6.dp))
                node.children.forEach { ChildNode(it, 0, opened, onToggle, onUndo) }
            }
        }
    }
}

@Composable
private fun ChildNode(node: DegreeNode, depth: Int, opened: Set<String>, onToggle: (String) -> Unit, onUndo: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val isOpen = node.id in opened
    val expandable = node.children.isNotEmpty() || node.courses.isNotEmpty() || node.requirementText != null
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = expandable) { onToggle(node.id) }
                .padding(start = 16.dp + 12.dp * depth, end = 16.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(node.satisfied)
                Spacer(Modifier.width(8.dp))
                Text(node.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Amount(node, emphasize = false)
                if (expandable) Chevron(isOpen)
            }
            Mismatch(node)
        }
        if (isOpen) {
            NodeBody(node, depth + 1, onUndo)
            node.children.forEach { ChildNode(it, depth + 1, opened, onToggle, onUndo) }
        }
    }
}

@Composable
private fun NodeBody(node: DegreeNode, depth: Int, onUndo: (String) -> Unit) {
    node.requirementText?.let {
        Text(
            it,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp + 12.dp * depth, end = 16.dp, bottom = 6.dp),
        )
    }
    node.courses.forEach { CourseRow(it, depth, onUndo) }
}

/** 这一组的学分。有要求显示「已修/要求」；要求是 0 的（选修模块）只说修了多少。 */
@Composable
private fun Amount(node: DegreeNode, emphasize: Boolean) {
    val cs = MiuixTheme.colorScheme
    val text = when {
        node.required > 0 -> "${credits(node.counted)}/${credits(node.required)}"
        else -> "已修 ${credits(node.earned)}"
    }
    Text(
        text,
        style = if (emphasize) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.footnote1,
        fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
        color = if (node.satisfied && node.required > 0) cs.primary else cs.onSurfaceVariantSummary,
    )
}

@Composable
private fun Mismatch(node: DegreeNode) {
    if (!node.differsFromOfficial) return
    Text(
        "教务显示 ${credits(node.officialTaken ?: 0.0)}，按课程实算 ${credits(node.counted)}",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.error,
    )
}

@Composable
private fun Chevron(open: Boolean) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "degreeChevron")
    Icon(
        Icons.Default.ExpandMore,
        null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp).size(18.dp).graphicsLayer { rotationZ = rotation },
    )
}

@Composable
private fun StatusDot(done: Boolean) {
    val cs = MiuixTheme.colorScheme
    if (done) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(11.dp))
        }
    } else {
        Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, cs.onSurface.copy(alpha = 0.25f), CircleShape))
    }
}

@Composable
private fun CourseRow(c: DegreeCourse, depth: Int, onUndo: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val passed = c.status == DegreeCourseStatus.PASSED
    val note = when {
        c.userConfirmed -> "已确认抵扣（未经教务确认），点按撤销"
        c.matchedByName -> "按课程名匹配"
        passed -> null
        c.status == DegreeCourseStatus.NOT_TAKEN -> c.plannedTerm?.let { "建议 $it" }
        c.status == DegreeCourseStatus.IN_PROGRESS -> DegreeBuilder.IN_PROGRESS_TEXT
        else -> c.statusText.ifEmpty { null }
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = c.userConfirmed) { onUndo(c.code) }
            .padding(start = 40.dp + 12.dp * depth, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MiuixTheme.textStyles.footnote1, color = if (passed) cs.onSurface else cs.onSurfaceVariantSummary)
            note?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = when (c.status) {
                        DegreeCourseStatus.FAILED -> cs.error
                        DegreeCourseStatus.IN_PROGRESS -> cs.primary
                        else -> cs.onSurfaceVariantSummary
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                if (c.score != null) append(c.score).append(" · ")
                append(credits(c.credit)).append(" 学分")
            },
            style = MiuixTheme.textStyles.footnote1,
            color = cs.onSurfaceVariantSummary,
        )
    }
}

/** 换方案：默认看自己这一级，年级能切，名字能搜。 */
@Composable
private fun PlanPicker(vm: DegreeViewModel, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf(vm.defaultGrade) }
    LaunchedEffect(query, grade) {
        delay(300)
        vm.searchPlans(query, grade)
    }
    OverlayBottomSheet(show = true, title = "换培养方案", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppSearchBar(query = query, onQueryChange = { query = it }, label = "搜专业名，如：计算机", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                vm.gradeChoices.forEach { g ->
                    val active = g == grade
                    Text(
                        "${g}级",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) cs.primary else cs.onSurfaceVariantSummary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { grade = g }.padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (vm.browsing) {
                    PlanRow("我的方案", "回到自己的方案和教务报告") {
                        vm.backToMine()
                        onDismiss()
                    }
                }
                when {
                    vm.planLoading && vm.planResults.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(size = 22.dp) }
                    vm.planError != null -> Text(vm.planError.orEmpty(), style = MiuixTheme.textStyles.footnote1, color = cs.error, modifier = Modifier.padding(8.dp))
                    vm.planResults.isEmpty() -> Text("没有找到方案，换个词试试", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(8.dp))
                    else -> vm.planResults.forEach { plan ->
                        PlanRow(plan.name, plan.college) {
                            vm.browse(plan)
                            onDismiss()
                        }
                    }
                }
                if (vm.planHasMore) {
                    Text("结果较多，只列前 ${vm.planResults.size} 个，搜得更具体一点", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(8.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PlanRow(title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(title, style = MiuixTheme.textStyles.body2, color = cs.onSurface)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
    }
}
