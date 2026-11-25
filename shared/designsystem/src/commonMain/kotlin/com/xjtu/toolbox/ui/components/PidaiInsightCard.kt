package com.xjtu.toolbox.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.AgentInsightHub
import com.xjtu.toolbox.agent.InsightOutcome
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCards
import com.xjtu.toolbox.platform.showBriefMessage
import com.xjtu.toolbox.util.safeStringOrNull
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一块小岱卡的状态：先画缓存，再按需调模型；跑的时候 [progress] 报小岱在做什么。 */
@Stable
class InsightState internal constructor(
    internal var spec: InsightSpec,
    private val scope: CoroutineScope,
) {
    var outcome by mutableStateOf<InsightOutcome?>(null)
        internal set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set

    /** 这次交给小岱的输入（追问时一并带过去）。 */
    var input: String? by mutableStateOf(null)
        internal set

    fun refresh(force: Boolean = true) {
        val data = input ?: return
        if (loading) return
        loading = true
        val started = Clock.System.now().toEpochMilliseconds()
        var step = 0
        var doing = spec.busyHint
        fun show() {
            val sec = (Clock.System.now().toEpochMilliseconds() - started) / 1000
            progress = buildString {
                if (step > 0) append("第 $step 步 · ")
                append("小岱").append(doing)
                if (sec >= 2) append(" · ${sec} 秒")
            }
        }
        show()
        scope.launch {
            // 每秒刷新一次已用时间：慢的时候让人知道它还在干活
            val ticker = launch { while (true) { kotlinx.coroutines.delay(1000); show() } }
            try {
                outcome = AgentInsightHub.insights.run(spec, data, force) { label ->
                    // 工具调用算一步；「思考中 / 整理结果」只换说法不加步数
                    if ("在$label" != doing) {
                        if (!label.startsWith("思考") && !label.startsWith("整理")) step++
                        doing = "在$label"
                        show()
                    }
                }
            } finally {
                ticker.cancel()
                loading = false
            }
        }
    }
}

/**
 * [input] 为 null 表示页面数据还没好，先不动；变了就（在 TTL 允许时用缓存）重算。
 * [auto] 为 false 时只画缓存，等用户点了才调模型（花钱或要用户先说话的卡片用）。
 */
@Composable
fun rememberInsight(spec: InsightSpec, input: String?, auto: Boolean = true): InsightState {
    val scope = rememberCoroutineScope()
    val off by PidaiCards.off.collectAsState()
    val enabled = spec.card !in off
    // 缓存在第一帧就画上：等副作用再读的话卡片先画一帧空壳、下一帧才撑开，把下面已经显示的内容整体顶下去
    val state = remember(spec.id) {
        InsightState(spec, scope).also {
            if (input != null && enabled) {
                it.input = input
                it.outcome = AgentInsightHub.insights.cached(spec, input)
            }
        }
    }
    state.spec = spec
    LaunchedEffect(spec.id, input, enabled) {
        state.input = input
        // 设置里关掉的卡：不读缓存也不调模型，页面上借用它结果的地方（图书馆平面图标座）一并清空
        if (!enabled) state.outcome = null
        if (input == null || !enabled) return@LaunchedEffect
        val hit = AgentInsightHub.insights.cached(spec, input)
        state.outcome = hit
        val fresh = hit != null && Clock.System.now().toEpochMilliseconds() - hit.at < spec.ttlMs
        if (auto && !fresh) state.refresh(force = false)
    }
    return state
}

/**
 * 小岱卡：头部（徽标、标题、多久前、⋯ 菜单）→ [controls]（条件行、输入）→ 结果 → 底栏（追问 + [primary]）。
 * 折叠后标题下只留 [brief] 一句结论。这一端没有小岱时整块不画。
 */
@Composable
fun PidaiInsightCard(
    title: String,
    state: InsightState,
    modifier: Modifier = Modifier,
    /** 没结果也没在跑时的一句说明（缺数据、正在汇总）；平时留空。 */
    hint: String = "",
    /** 还没生成时的按钮（点了才调模型）。 */
    idleAction: String? = null,
    /** 折叠时标题下那句结论。 */
    brief: (JsonObject) -> String? = { it["summary"].safeStringOrNull() },
    /** 底栏追问链接的说法。 */
    askLabel: String = "追问",
    /** 点追问时说的那句；null = 不显示。 */
    askPrompt: ((JsonObject) -> String)? = null,
    controls: (@Composable ColumnScope.() -> Unit)? = null,
    /** 底栏右侧唯一的主按钮。 */
    primary: (@Composable (JsonObject) -> Unit)? = null,
    content: @Composable ColumnScope.(JsonObject) -> Unit,
) {
    val insights = AgentInsightHub.insights
    val off by PidaiCards.off.collectAsState()
    // 关闭：仅本次；不再提示：走设置开关
    var dismissed by rememberSaveable(state.spec.id) { mutableStateOf(false) }
    if (!insights.available || state.spec.card in off || dismissed) return
    val ok = state.outcome as? InsightOutcome.Ok
    val visibilityStore = remember { com.xjtu.toolbox.platform.keyValueStore("pidai_card_visibility") }
    var collapsed by rememberSaveable(state.spec.id) { mutableStateOf(visibilityStore.getBoolean(state.spec.id, false)) }
    fun toggle() {
        collapsed = !collapsed
        visibilityStore.putBoolean(state.spec.id, collapsed)
    }
    val primaryColor = MiuixTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Column(
            Modifier
                // 左上角一层很淡的主色晕，认得出是小岱的卡
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(primaryColor.copy(alpha = 0.09f), Color.Transparent),
                            center = Offset.Zero,
                            radius = size.maxDimension * 0.8f,
                        ),
                    )
                }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (collapsed) 12.dp else 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PidaiBadge(thinking = state.loading)
                Column(
                    Modifier.padding(start = 8.dp).weight(1f)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { toggle() },
                ) {
                    Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                    val line = when {
                        state.loading -> state.progress
                        collapsed -> ok?.let { brief(it.json) }.orEmpty()
                        else -> ""
                    }
                    if (line.isNotBlank()) {
                        AnimatedContent(line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { s ->
                            Text(
                                s,
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (state.loading) primaryColor else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (ok != null && !state.loading && !collapsed) {
                    Text(insightAge(ok.at), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (collapsed) {
                    PidaiIconAction(Icons.Filled.ExpandMore, "展开", onClick = ::toggle)
                }
                PidaiCardMenu(
                    canRefresh = (ok != null || state.outcome is InsightOutcome.Failed) && !state.loading,
                    collapsed = collapsed,
                    onRefresh = { state.refresh() },
                    onToggle = ::toggle,
                    onDismiss = { dismissed = true },
                    onMute = state.spec.card?.let { card ->
                        {
                            PidaiCards.set(card, false)
                            showBriefMessage("已关闭，可在小岱设置中恢复")
                        }
                    },
                )
            }
            if (!collapsed) {
                controls?.invoke(this)
                when (val o = state.outcome) {
                    is InsightOutcome.Ok -> Column(
                        Modifier.alpha(if (state.loading) 0.45f else 1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { content(o.json) }
                    InsightOutcome.Unavailable -> PidaiHint("在小岱页配好 API Key 后，这里由小岱帮你挑重点。")
                    is InsightOutcome.Failed -> if (!state.loading) {
                        PidaiHint("这次没看成：${o.message}")
                        PidaiChip("再试一次", icon = Icons.Filled.Refresh, onClick = { state.refresh() })
                    }
                    null -> if (!state.loading) {
                        if (hint.isNotBlank()) PidaiHint(hint)
                        if (idleAction != null) {
                            PidaiChip(idleAction, icon = Icons.Filled.AutoAwesome, emphasized = true, onClick = { state.refresh(force = false) })
                        }
                    }
                }
                if (state.loading && ok == null) InsightShimmer()
                if (ok != null && !state.loading && (askPrompt != null || primary != null)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PidaiDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (askPrompt != null) {
                                Text(
                                    "$askLabel →",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable(role = Role.Button) {
                                            insights.ask(askPrompt(ok.json), insightSnapshot(title, state.input, ok))
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            primary?.invoke(ok.json)
                        }
                    }
                }
            }
        }
    }
}

/** 右上角 ⋯：重新生成、折叠、本次关闭、不再提示。 */
@Composable
private fun PidaiCardMenu(
    canRefresh: Boolean,
    collapsed: Boolean,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onMute: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    var onScreen by remember { mutableStateOf(false) }
    val tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val icon: @Composable () -> Unit = { Icon(Icons.Filled.MoreHoriz, contentDescription = "更多", tint = tint, modifier = Modifier.size(20.dp)) }
    Box {
        Box(
            Modifier.size(36.dp).graphicsLayer { alpha = if (onScreen) 0f else 1f }
                .clip(CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { icon() }
        AppDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            trigger = icon,
            onVisibleChange = { onScreen = it },
        ) {
            val close = { open = false }
            if (canRefresh) PidaiMenuItem("重新生成", Icons.Filled.Refresh) { close(); onRefresh() }
            PidaiMenuItem(if (collapsed) "展开" else "折叠", if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess) { close(); onToggle() }
            PidaiMenuItem("本次关闭", Icons.Filled.Close) { close(); onDismiss() }
            if (onMute != null) PidaiMenuItem("不再提示", Icons.Filled.VisibilityOff) { close(); onMute() }
        }
    }
}

@Composable
private fun PidaiMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    AppDropdownMenuItem(
        text = { Text(text, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** 带进对话的快照：这张卡看了什么、查到了什么、给了什么，小岱接着聊时直接用，不用再查。 */
private fun insightSnapshot(title: String, input: String?, ok: InsightOutcome.Ok): String = buildString {
    val minutes = ((Clock.System.now().toEpochMilliseconds() - ok.at) / 60_000).coerceAtLeast(0)
    append("用户从「$title」卡片点进来。下面是这张卡的数据、你当时查到的工具结果和给出的结果，")
    append(if (minutes < 1) "刚生成。" else "约 $minutes 分钟前生成。")
    append("这些足够回答时直接用，不要重复调用同样的工具；用户要最新情况或这里没有的信息时再查。\n")
    if (!input.isNullOrBlank()) append("【卡片数据】\n").append(input.trim()).append('\n')
    if (ok.toolLog.isNotBlank()) append("【当时的工具结果】\n").append(ok.toolLog.trim()).append('\n')
    append("【你给出的结果】\n").append(ok.json.toString())
}

/** 小岱徽标：主色渐变圆 + 星光；思考时外圈一道光弧绕着转。外圈位置常留，转起来不挤动标题。 */
@Composable
fun PidaiBadge(thinking: Boolean = false, size: Dp = 26.dp) {
    val primary = MiuixTheme.colorScheme.primary
    val phase = if (thinking) rememberLoopPhase(1200L) else null
    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        if (phase != null) {
            Canvas(Modifier.size(size + 8.dp)) {
                val stroke = 1.6.dp.toPx()
                rotate(phase.value * 360f) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, primary)),
                        startAngle = 0f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** 动作按钮：[emphasized] 实心主色（一张卡只放一个），否则淡主色底。 */
@Composable
fun PidaiChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasized: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val primary = MiuixTheme.colorScheme.primary
    val solid = emphasized || selected
    val fg = if (solid) MiuixTheme.colorScheme.onPrimary else primary
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (solid) primary else primary.copy(alpha = 0.12f))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/** 筛选项：没选是细描边灰字，选中是淡主色底 + ✓，和动作按钮一眼分开。 */
@Composable
fun PidaiFilterChip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val primary = MiuixTheme.colorScheme.primary
    val fg = if (selected) primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier.height(30.dp).clip(shape)
            .then(
                if (selected) Modifier.background(primary.copy(alpha = 0.12f))
                else Modifier.border(0.8.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f), shape),
            )
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val lead = if (selected) Icons.Filled.Check else icon
        if (lead != null) Icon(lead, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = fg, maxLines = 1)
    }
}

/** 一组会折行的筛选项。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PidaiChipFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * 条件一句话：[lead] + 加粗的 [emphasis] + [tail]，例如「二楼南区 · 按 **靠窗 · 安静** 挑」。
 * 给了 [filters] 尾巴就是「改」，点开在下面原地展开筛选，「完成」收起。
 */
@Composable
fun PidaiContextLine(lead: String, emphasis: String = "", tail: String = "", filters: (@Composable ColumnScope.() -> Unit)? = null) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append(lead)
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)) { append(emphasis) }
                    append(tail)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (filters != null) {
                Text(
                    if (editing) "完成" else "改",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { editing = !editing }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (filters != null && editing) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = filters)
    }
}

/** 结果的一句总括。 */
@Composable
fun PidaiSummary(text: String?) {
    if (text.isNullOrBlank() || text == "null") return
    Text(text, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
}

/** 首选：淡主色底的大块，一张卡最多一个。 */
@Composable
fun PidaiHero(
    title: String,
    modifier: Modifier = Modifier,
    label: String = "",
    detail: String = "",
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(primary.copy(alpha = 0.10f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (label.isNotBlank()) Text(label, style = MiuixTheme.textStyles.footnote2, fontWeight = FontWeight.Medium, color = primary)
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        trailing?.invoke()
    }
}

/** 首选里的大字（座位号、价格）。 */
@Composable
fun PidaiHeroText(text: String) {
    Text(text, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary, maxLines = 1)
}

/** 结果列表：同一个底上用细线隔开，不再一条一块。 */
@Composable
fun <T> PidaiList(items: List<T>, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Column {
        items.forEachIndexed { i, item ->
            if (i > 0) PidaiDivider()
            row(item)
        }
    }
}

@Composable
fun PidaiDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)))
}

/** 列表的一行：[overline] 小字归属 → 标题 → [detail] 说明 → [footer]；尾部放图标动作。 */
@Composable
fun PidaiListRow(
    title: String,
    modifier: Modifier = Modifier,
    overline: String = "",
    detail: String = "",
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (overline.isNotBlank()) {
                Text(overline, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            footer?.invoke()
        }
        trailing?.invoke(this)
    }
}

/** 行尾的图标动作；[done] 后变 ✓ 且不能再点。 */
@Composable
fun PidaiIconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, emphasized: Boolean = false, done: Boolean = false) {
    val tint = when {
        done -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
        emphasized -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .clickable(enabled = !done, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (done) Icons.Filled.Check else icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 小岱的提醒：灯泡 + 一句话。 */
@Composable
fun PidaiTip(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = PidaiAmber, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private val PidaiAmber = Color(0xFFE9A23B)

/**
 * 对小岱说一句：圆角浅底的一体输入，[leading] 放内嵌标记（如日期），右侧 ✦ 发送，跑的时候 ✦ 呼吸。
 * 空着且聚焦时在下面浮出 [suggestions]，点一下填进去。
 */
@Composable
fun PidaiComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    busy: Boolean = false,
    suggestions: List<String> = emptyList(),
    leading: (@Composable () -> Unit)? = null,
) {
    val primary = MiuixTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(AppInsetColor)
                .border(1.dp, if (focused) primary.copy(alpha = 0.35f) else Color.Transparent, shape)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f).padding(start = if (leading == null) 11.dp else 0.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!busy) onSubmit() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            val phase = if (busy) rememberLoopPhase() else null
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(primary)
                    .graphicsLayer { alpha = phase?.let { breath(it.value, low = 0.5f) } ?: 1f }
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = "让小岱排", onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "让小岱排", tint = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
        if (focused && value.isBlank() && suggestions.isNotEmpty()) {
            PidaiChipFlow {
                suggestions.forEach { s -> PidaiFilterChip(s, selected = false, onClick = { onValueChange(s) }) }
            }
        }
    }
}

/** 输入框里的内嵌标记：白底主色字 + ▾。 */
@Composable
fun PidaiToken(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(AppCardColor)
            .clickable(role = Role.DropdownList, onClick = onClick).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary, maxLines = 1)
        Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PidaiHint(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/** 加载骨架：三行长短不一的 [SkeletonBlock]。 */
@Composable
private fun InsightShimmer() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1f, 0.86f, 0.62f).forEach { w ->
            SkeletonBlock(Modifier.fillMaxWidth(w).height(14.dp), cornerRadius = 7.dp)
        }
    }
}

private fun insightAge(at: Long): String {
    val min = (Clock.System.now().toEpochMilliseconds() - at) / 60_000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        min < 24 * 60 -> "${min / 60} 小时前"
        else -> "${min / (24 * 60)} 天前"
    }
}
