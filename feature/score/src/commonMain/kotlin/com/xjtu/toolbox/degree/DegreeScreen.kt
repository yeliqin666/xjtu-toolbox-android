package com.xjtu.toolbox.degree

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.agent.AgentForward
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import com.xjtu.toolbox.ui.components.AppOptionGroup
import com.xjtu.toolbox.ui.components.MenuMorph
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun credits(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()

private fun fraction(done: Double, total: Double): Float = if (total > 0) (done / total).toFloat().coerceIn(0f, 1f) else 0f

/** 这一组（含下级）在修、待出成绩的学分：进度条上画成浅色一段。 */
private val DegreeNode.pendingCredits: Double
    get() = flattened.flatMap { it.courses }.filter { it.status == DegreeCourseStatus.IN_PROGRESS }.sumOf { it.credit }

/** 和教务对不上的地方：各组学分差异 + 解析方案时的诊断。 */
private fun DegreeProgress.reconcileCount(): Int =
    nodes.flatMap { it.flattened }.count { it.differsFromOfficial } + warnings.size

/**
 * 学业进度。两种模式：
 * - 我的方案：方案树 + 按课程重算的学分，教务报告原值放进「对账」；
 * - 看别的方案（规划转专业）：用成绩对照估算能算多少、还差什么，没有教务报告。
 *
 * 版式：首屏总进度（已修 / 在修 / 还差三段）+ 各大类环形概览；下面没达标的组在前，达标的收成一行。
 */
@Composable
fun DegreeScreen(
    source: DegreeSource,
    onBack: () -> Unit,
    /** 让小岱看。没接小岱的端传 null，入口不出现。 */
    onAskAgent: ((AgentForward) -> Unit)? = null,
) {
    val vm: DegreeViewModel = viewModel { DegreeViewModel(source) }
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val glass = rememberPageGlass()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf<PickTarget?>(null) }
    val confirmedActions = ConfirmedActions(
        onChange = { course, nodeId -> picking = PickTarget(course.code, course.name, null, nodeId) },
        onUndo = vm::undoConfirm,
    )
    var choosingPlan by remember { mutableStateOf(false) }
    var showUnplaced by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }
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
                    val unmet = progress.nodes.filter { !it.satisfied }
                    val done = progress.nodes.filter { it.satisfied }
                    val manual = progress.nodes.flatMap { it.flattened }
                        .flatMap { n -> n.courses.filter { it.userConfirmed }.map { n to it } }
                        .takeIf { !vm.browsing }.orEmpty()
                    // 与下面 items 的顺序一致：环形概览点一组时据此滚过去
                    val order = buildList {
                        add("hero")
                        if (manual.isNotEmpty()) add("manual")
                        if (unmet.isNotEmpty()) add("unmetHeader")
                        unmet.forEach { add(it.id) }
                        if (done.isNotEmpty()) add("doneHeader")
                        if (showDone) done.forEach { add(it.id) }
                    }
                    fun jumpTo(node: DegreeNode) {
                        if (node.satisfied) showDone = true
                        scope.launch {
                            delay(16)
                            val index = buildList {
                                addAll(order.takeWhile { it != "doneHeader" })
                                if (done.isNotEmpty()) add("doneHeader")
                                done.forEach { add(it.id) }
                            }.indexOf(node.id)
                            if (index >= 0) gridState.animateScrollToItem(index)
                        }
                    }
                    AdaptiveCardGrid(
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                        state = gridState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + glassTop, bottom = 24.dp),
                        spacing = 12.dp,
                    ) {
                        fullLineItem(key = "hero_${progress.planCode}") {
                            Box(Modifier.enterOnce(0)) {
                                if (vm.browsing) BrowseHero(progress, onChoose = { choosingPlan = true }, onBackToMine = vm::backToMine, onJump = ::jumpTo)
                                else ProgressHero(progress, vm, onChoose = { choosingPlan = true }, onReconcile = { reconciling = true }, onJump = ::jumpTo)
                            }
                        }
                        if (manual.isNotEmpty()) {
                            fullLineItem(key = "manual_${progress.planCode}") {
                                Box(Modifier.enterOnce(1)) { ManualCard(manual, confirmedActions) }
                            }
                        }
                        if (unmet.isNotEmpty()) {
                            fullLineItem(key = "unmetHeader") { SectionHeader("还差的 ${unmet.size} 组") }
                            items(unmet.size, key = { "${progress.planCode}_${unmet[it].id}" }) { i ->
                                Box(Modifier.enterOnce(i + 2)) { RootCard(unmet[i], opened, toggle, confirmedActions) }
                            }
                        }
                        if (done.isNotEmpty()) {
                            fullLineItem(key = "doneHeader") {
                                SectionHeader("已完成 ${done.size} 组", open = showDone, onToggle = { showDone = !showDone })
                            }
                            if (showDone) {
                                items(done.size, key = { "${progress.planCode}_${done[it].id}" }) { i ->
                                    RootCard(done[i], opened, toggle, confirmedActions)
                                }
                            }
                        }
                        if (progress.unplaced.isNotEmpty()) {
                            fullLineItem(key = "unplaced_${progress.planCode}") {
                                Unplaced(
                                    progress.unplaced,
                                    open = showUnplaced,
                                    otherPlan = vm.browsing,
                                    onToggle = { showUnplaced = !showUnplaced },
                                    onPick = { picking = PickTarget(it.code, it.name, it, null) },
                                    onAskAgent = onAskAgent?.let { ask -> { ask(if (vm.browsing) askAboutPlan(progress.planName) else ASK_UNPLACED) } },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Overlay 系弹窗要挂在 Scaffold 里面才有宿主，放在外面弹不出来
        if (choosingPlan) PlanPicker(vm, onDismiss = { choosingPlan = false })
        if (reconciling && progress != null) ReconcileSheet(progress, onDismiss = { reconciling = false })

        val target = picking
        if (target != null && progress != null) {
            GroupPicker(
                target = target,
                roots = progress.nodes,
                onPick = { nodeId ->
                    if (target.loose != null) vm.confirm(target.loose, nodeId) else vm.reassign(target.code, nodeId)
                    picking = null
                },
                onDismiss = { picking = null },
            )
        }
    }
}

private val ASK_PROGRESS = AgentForward(
    text = "看看我的学业进度，还差什么？",
    snapshot = "用户从学业进度页进来。用 get_degree_progress 查，重算结果和教务原值不同时都说清楚，结论以教务为准。",
    label = "学业进度",
)

private val ASK_UNPLACED = AgentForward(
    text = "帮我看看这些没归进培养方案的课，可能抵哪些要求？",
    snapshot = "用户从学业进度页进来。用 get_degree_progress 查 unplaced 和各组要求，按课程名和学分给出可能的对应和理由。这只是建议：请用户在学业进度页自己确认，并向教务核实。",
    label = "学业进度",
)

private fun askAboutPlan(name: String) = AgentForward(
    text = "如果我转到「$name」，大概还要补哪些课？",
    snapshot = "用户在学业进度页看别的培养方案，可能在规划转专业。用 get_degree_progress 的 plan 参数查这个方案（名称：$name），说明现有的课能算多少、要补哪些、哪些用不上。转专业政策和学分认定以学院当年文件为准，没有收录就如实说。",
    label = "培养方案「$name」",
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

/** 首屏共用部分：方案名、总学分、三段条与图例、各大类环形概览。 */
@Composable
private fun HeroCore(p: DegreeProgress, headline: String, onChoose: () -> Unit, onJump: (DegreeNode) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val pending = p.inProgressCredits.coerceAtMost((p.required - p.counted).coerceAtLeast(0.0))
    val remaining = (p.required - p.counted - pending).coerceAtLeast(0.0)
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
        Spacer(Modifier.weight(1f))
        Text(headline, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(bottom = 4.dp))
    }
    SegmentBar(p.counted, pending, p.required, height = 10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Legend(cs.primary, "已修 ${credits(p.counted)}")
        if (pending > 0) Legend(cs.primary.copy(alpha = 0.35f), "在修 ${credits(pending)}")
        if (remaining > 0) Legend(cs.onSurface.copy(alpha = 0.12f), "还差 ${credits(remaining)}")
    }
    val rings = p.nodes.filter { it.required > 0 }
    if (rings.size > 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rings.forEach { RingTile(it) { onJump(it) } }
        }
    }
}

@Composable
private fun ProgressHero(p: DegreeProgress, vm: DegreeViewModel, onChoose: () -> Unit, onReconcile: () -> Unit, onJump: (DegreeNode) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val remaining = (p.required - p.counted).coerceAtLeast(0.0)
    val unmet = p.nodes.count { !it.satisfied }
    Surface(color = AppCardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroCore(
                p,
                headline = when {
                    remaining > 0 -> "还差 ${credits(remaining)}"
                    unmet > 0 -> "$unmet 组没达标"
                    else -> "已修满"
                },
                onChoose = onChoose,
                onJump = onJump,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.officialTaken?.let { "教务显示 ${credits(it)}" + (p.calculatedAt?.let { at -> " · ${at.take(10)}" } ?: "") } ?: "暂无教务报告",
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                val diffs = p.reconcileCount()
                if (diffs > 0) LinkText("对账 · $diffs 处", onReconcile)
                if (vm.recalculating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(size = 14.dp)
                } else {
                    LinkText("重新计算", vm::recalculate)
                }
            }
            vm.notice?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = cs.error) }
        }
    }
}

@Composable
private fun BrowseHero(p: DegreeProgress, onChoose: () -> Unit, onBackToMine: () -> Unit, onJump: (DegreeNode) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val remaining = (p.required - p.counted).coerceAtLeast(0.0)
    Surface(color = AppCardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroCore(p, headline = if (remaining > 0) "转过去还差 ${credits(remaining)}" else "转过去已修满", onChoose = onChoose, onJump = onJump)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("按你现有成绩估算", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                LinkText("回到我的方案", onBackToMine)
            }
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 大类概览：环形进度 + 名字，点一下滚到那一组。 */
@Composable
private fun RingTile(node: DegreeNode, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val f = fraction(node.counted, node.required)
    val pending = fraction(node.counted + node.pendingCredits, node.required)
    val reveal = com.xjtu.toolbox.ui.components.rememberRevealProgress(key = f)
    val track = cs.onSurface.copy(alpha = 0.08f)
    val main = cs.primary
    val soft = cs.primary.copy(alpha = 0.35f)
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, -90f, 360f, false, topLeft, arcSize, style = stroke)
                if (pending > f) drawArc(soft, -90f, 360f * pending * reveal.value, false, topLeft, arcSize, style = stroke)
                if (f > 0f) drawArc(main, -90f, 360f * f * reveal.value, false, topLeft, arcSize, style = stroke)
            }
            if (node.satisfied) {
                Icon(Icons.Default.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            } else {
                Text("${(f * 100).toInt()}%", style = MiuixTheme.textStyles.footnote2, fontWeight = FontWeight.Bold, color = cs.onSurface)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            node.name,
            style = MiuixTheme.textStyles.footnote2,
            color = cs.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 已修一段实色、在修一段浅色、其余是底色。 */
@Composable
private fun SegmentBar(done: Double, pending: Double, total: Double, height: Dp = 5.dp) {
    val cs = MiuixTheme.colorScheme
    val f = fraction(done, total)
    val p = fraction(done + pending, total)
    val reveal = com.xjtu.toolbox.ui.components.rememberRevealProgress(key = f)
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(cs.onSurface.copy(alpha = 0.08f))) {
        listOf(p to cs.primary.copy(alpha = 0.35f), f to cs.primary).forEach { (w, color) ->
            if (w > 0f) {
                Box(
                    Modifier.fillMaxWidth(w).fillMaxHeight()
                        .graphicsLayer {
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            scaleX = reveal.value
                        }
                        .clip(RoundedCornerShape(height / 2)).background(color),
                )
            }
        }
    }
}

/** 分区小标题；传了 [onToggle] 的可以点开收起。 */
@Composable
private fun SectionHeader(text: String, open: Boolean? = null, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
        if (open != null) Chevron(open)
    }
}

@Composable
private fun Unplaced(courses: List<DegreeLooseCourse>, open: Boolean, otherPlan: Boolean, onToggle: () -> Unit, onPick: (DegreeLooseCourse) -> Unit, onAskAgent: (() -> Unit)?) {
    val cs = MiuixTheme.colorScheme
    Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (otherPlan) "${courses.size} 门课在这个方案里没对上" else "${courses.size} 门课没归进方案",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (open && onAskAgent != null) LinkText("问小岱", onAskAgent)
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
                                c.inProgress -> "在修"
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
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 手动抵扣的课由谁改：改抵到别的组 / 撤销。 */
private class ConfirmedActions(val onChange: (DegreeCourse, String) -> Unit, val onUndo: (String) -> Unit)

/** 选组弹窗的对象：没归位的课（[loose]）首次抵扣，或已抵扣的课改组（[current] 是现在那组）。 */
private data class PickTarget(val code: String, val name: String, val loose: DegreeLooseCourse?, val current: String?)

/** 顶层一组一张卡：标题 + 进度条；下一级是卡里的行，再往下收进一块内嵌底色里。 */
@Composable
private fun RootCard(node: DegreeNode, opened: Set<String>, onToggle: (String) -> Unit, actions: ConfirmedActions) {
    Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(vertical = 14.dp)) {
            val leaf = node.children.isEmpty()
            Column(
                Modifier.fillMaxWidth().clickable(enabled = leaf) { onToggle(node.id) }.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(node.name, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Amount(node, emphasize = true)
                    if (leaf) Chevron(node.id in opened)
                }
                if (node.required > 0) SegmentBar(node.counted, node.pendingCredits, node.required, height = 6.dp)
            }
            if (leaf) {
                if (node.id in opened) Inset { NodeBody(node, actions) }
            } else {
                Spacer(Modifier.height(6.dp))
                // 没达标的子组排前面
                node.children.sortedBy { it.satisfied }.forEach { ChildNode(it, opened, onToggle, actions) }
            }
        }
    }
}

/** 展开后的内容垫一层内嵌底色：一眼看出它属于上面那一行。 */
@Composable
private fun Inset(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.xjtu.toolbox.ui.components.AppInsetColor)
            .padding(vertical = 6.dp),
    ) { content() }
}

@Composable
private fun ChildNode(node: DegreeNode, opened: Set<String>, onToggle: (String) -> Unit, actions: ConfirmedActions) {
    val isOpen = node.id in opened
    val expandable = node.children.isNotEmpty() || node.courses.isNotEmpty() || node.requirementText != null
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = expandable) { onToggle(node.id) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(node.satisfied)
                Spacer(Modifier.width(10.dp))
                Text(node.name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Amount(node, emphasize = false)
                if (expandable) Chevron(isOpen)
            }
            if (node.required > 0 && !node.satisfied) {
                Box(Modifier.padding(start = 26.dp)) { SegmentBar(node.counted, node.pendingCredits, node.required, height = 3.dp) }
            }
        }
        if (isOpen) {
            Inset {
                NodeBody(node, actions)
                node.children.sortedBy { it.satisfied }.forEach { ChildNode(it, opened, onToggle, actions) }
            }
        }
    }
}

@Composable
private fun NodeBody(node: DegreeNode, actions: ConfirmedActions) {
    node.requirementText?.let {
        Text(
            it,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
    // 没修完的组：还没修、在修、挂科的课排在已通过的前面
    val courses = if (node.satisfied) node.courses else node.courses.sortedBy { it.status == DegreeCourseStatus.PASSED }
    courses.forEach { CourseRow(it, node.id, actions) }
}

/** 手动抵扣的课集中列在这里，点一门就能改组或撤销。 */
@Composable
private fun ManualCard(items: List<Pair<DegreeNode, DegreeCourse>>, actions: ConfirmedActions) {
    val cs = MiuixTheme.colorScheme
    Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(
                "手动抵扣 ${items.size} 门",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            items.forEach { (node, course) ->
                ConfirmedMenuHost(course, node.id, actions) { open ->
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(course.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface)
                            Text("抵到「${node.name}」", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                        }
                        Text("${credits(course.credit)} 学分", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                        Icon(Icons.Default.MoreVert, contentDescription = "改组或撤销", tint = cs.onSurfaceVariantSummary, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

/** 已抵扣课程的那一行 + 它的弹出菜单（改抵到其他组 / 撤销抵扣）。 */
@Composable
private fun ConfirmedMenuHost(course: DegreeCourse, nodeId: String, actions: ConfirmedActions, row: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        row { open = true }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, morph = MenuMorph.FromRow) {
            AppDropdownMenuItem(
                text = { Text("改抵到其他组", style = MiuixTheme.textStyles.body1) },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) },
                onClick = {
                    open = false
                    actions.onChange(course, nodeId)
                },
            )
            AppDropdownMenuItem(
                text = { Text("撤销抵扣", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Close, null, tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                onClick = {
                    open = false
                    actions.onUndo(course.code)
                },
            )
        }
    }
}

/** 选抵到哪一组：按顶层大类分组列出末级课组，当前那组打勾。 */
@Composable
private fun GroupPicker(target: PickTarget, roots: List<DegreeNode>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    // 抽屉默认白底，里面的课组卡也是白的；垫 surface 灰底才分得出
    OverlayBottomSheet(show = true, title = "「${target.name}」抵哪一组", backgroundColor = cs.surface, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("只记在本机，用来估算进度，教务那边不变", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            roots.forEach { root ->
                val leaves = root.flattened.filter { it.children.isEmpty() }
                if (leaves.isEmpty()) return@forEach
                Text(root.name, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                AppOptionGroup {
                    leaves.forEach { node ->
                        val current = node.id == target.current
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !current) { onPick(node.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                node.name,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                                color = if (current) cs.primary else cs.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (node.required > 0) "${credits(node.counted)}/${credits(node.required)}" else "已修 ${credits(node.earned)}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = cs.onSurfaceVariantSummary,
                            )
                            if (current) Icon(Icons.Default.Check, "当前", tint = cs.primary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 与教务对账：总学分、各组学分不一致的地方和解析方案时的诊断，集中放这里，不在正文里逐组标红。 */
@Composable
private fun ReconcileSheet(p: DegreeProgress, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val diffs = p.nodes.flatMap { it.flattened }.filter { it.differsFromOfficial }
    OverlayBottomSheet(show = true, title = "与教务对账", backgroundColor = cs.surface, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "本页按课程成绩重算；教务的完成报告可能没刷新，以教务为准。" +
                    (p.officialTaken?.let { "\n总学分：教务 ${credits(it)}，重算 ${credits(p.counted)}" } ?: ""),
                style = MiuixTheme.textStyles.footnote1,
                color = cs.onSurfaceVariantSummary,
            )
            if (diffs.isNotEmpty()) {
                AppOptionGroup {
                    diffs.forEach { node ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(node.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                "教务 ${credits(node.officialTaken ?: 0.0)} · 重算 ${credits(node.counted)}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = cs.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            if (p.warnings.isNotEmpty()) {
                Text("方案数据", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                Text(p.warnings.joinToString("\n"), style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 这一组的学分：没达标说差多少，达标显示「已修/要求」，要求是 0 的（选修模块）只说修了多少。 */
@Composable
private fun Amount(node: DegreeNode, emphasize: Boolean) {
    val cs = MiuixTheme.colorScheme
    val style = if (emphasize) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.footnote1
    when {
        node.required <= 0 -> Text("已修 ${credits(node.earned)}", style = style, color = cs.onSurfaceVariantSummary)
        node.satisfied -> Text("${credits(node.counted)}/${credits(node.required)}", style = style, fontWeight = FontWeight.Medium, color = cs.primary)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (emphasize) {
                Text("${credits(node.counted)}/${credits(node.required)}", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                Spacer(Modifier.width(8.dp))
            }
            Text("差 ${credits(node.required - node.counted)}", style = style, fontWeight = FontWeight.Bold, color = cs.onSurface)
        }
    }
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
private fun CourseRow(c: DegreeCourse, nodeId: String, actions: ConfirmedActions) {
    if (c.userConfirmed) {
        ConfirmedMenuHost(c, nodeId, actions) { open -> CourseRowContent(c, Modifier.clickable(onClick = open)) }
    } else {
        CourseRowContent(c, Modifier)
    }
}

@Composable
private fun CourseRowContent(c: DegreeCourse, modifier: Modifier) {
    val cs = MiuixTheme.colorScheme
    val passed = c.status == DegreeCourseStatus.PASSED
    val note = when {
        passed || c.userConfirmed || c.matchedByName -> null
        c.status == DegreeCourseStatus.NOT_TAKEN -> c.plannedTerm?.let { "建议 $it" }
        c.status == DegreeCourseStatus.IN_PROGRESS -> null
        else -> c.statusText.ifEmpty { null }
    }
    Row(
        Modifier.fillMaxWidth().then(modifier).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CourseMark(c.status)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MiuixTheme.textStyles.footnote1, color = if (passed) cs.onSurface else cs.onSurfaceVariantSummary, modifier = Modifier.weight(1f, fill = false))
                when {
                    c.userConfirmed -> Tag("手动")
                    c.matchedByName -> Tag("按课名")
                    c.status == DegreeCourseStatus.IN_PROGRESS -> Tag("在修", cs.primary)
                }
            }
            note?.let {
                Text(it, style = MiuixTheme.textStyles.footnote2, color = if (c.status == DegreeCourseStatus.FAILED) cs.error else cs.onSurfaceVariantSummary)
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

/** 课名后的小标签：手动抵扣、按课名对上、在修。 */
@Composable
private fun Tag(text: String, color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 课程前面的小记号：通过实心勾、在修主题色圆点、挂科红点、没修空心圈。 */
@Composable
private fun CourseMark(status: DegreeCourseStatus) {
    val cs = MiuixTheme.colorScheme
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (status) {
            DegreeCourseStatus.PASSED -> Icon(Icons.Default.Check, null, tint = cs.primary, modifier = Modifier.size(14.dp))
            DegreeCourseStatus.IN_PROGRESS -> Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary))
            DegreeCourseStatus.FAILED -> Box(Modifier.size(7.dp).clip(CircleShape).background(cs.error))
            else -> Box(Modifier.size(8.dp).clip(CircleShape).border(1.2.dp, cs.onSurface.copy(alpha = 0.3f), CircleShape))
        }
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
                    PlanRow("我的方案", "") {
                        vm.backToMine()
                        onDismiss()
                    }
                }
                when {
                    vm.planLoading && vm.planResults.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(size = 22.dp) }
                    vm.planError != null -> Text(vm.planError.orEmpty(), style = MiuixTheme.textStyles.footnote1, color = cs.error, modifier = Modifier.padding(8.dp))
                    vm.planResults.isEmpty() -> Text("没有找到方案", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(8.dp))
                    else -> vm.planResults.forEach { plan ->
                        PlanRow(plan.name, plan.college) {
                            vm.browse(plan)
                            onDismiss()
                        }
                    }
                }
                if (vm.planHasMore) {
                    Text("只列前 ${vm.planResults.size} 个", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.padding(8.dp))
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
