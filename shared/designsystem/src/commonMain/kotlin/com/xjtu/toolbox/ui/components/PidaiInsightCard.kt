package com.xjtu.toolbox.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.AgentInsightHub
import com.xjtu.toolbox.agent.InsightOutcome
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCards
import com.xjtu.toolbox.platform.showBriefMessage
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一块屁岱卡的状态：先画缓存，再按需调模型；跑的时候 [progress] 报屁岱在做什么。 */
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

    /** 这次交给屁岱的输入（「接着问屁岱」时一并带过去）。 */
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
                append("屁岱").append(doing)
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
    val state = remember(spec.id) { InsightState(spec, scope) }
    state.spec = spec
    val off by PidaiCards.off.collectAsState()
    val enabled = spec.card !in off
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
 * 屁岱卡：和页面其他卡片同一种底，头部是屁岱徽标 + 标题 + 一行状态（在做什么 / 多久前看的），
 * [controls] 放这张卡的选项，结果下面是「接着问屁岱」（把这张卡的输入和结果一并带进对话）。
 * 这一端没有屁岱时整块不画。
 */
@Composable
fun PidaiInsightCard(
    title: String,
    state: InsightState,
    modifier: Modifier = Modifier,
    /** 没有结果时状态行那句话。 */
    subtitle: String = "",
    /** 点「接着问屁岱」时说的那句；null = 不显示。 */
    askPrompt: ((JsonObject) -> String)? = null,
    /** 还没生成时的引导语和按钮（按钮点了才调模型）。 */
    idleHint: String = "",
    idleAction: String? = null,
    /** 选项（来源、偏好、输入框…），在结果上方常驻。 */
    controls: (@Composable ColumnScope.() -> Unit)? = null,
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 内容左右 16dp；链接自带 6dp，故右侧留 10dp，对齐且热区不越界
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PidaiBadge(thinking = state.loading)
                    Column(Modifier.padding(start = 10.dp).weight(1f).clickable {
                        collapsed = !collapsed
                        visibilityStore.putBoolean(state.spec.id, collapsed)
                    }) {
                        Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                        val status = when {
                            state.loading -> state.progress
                            ok != null -> "屁岱 · ${insightAge(ok.at)}"
                            else -> subtitle
                        }
                        if (status.isNotBlank()) {
                            AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { s ->
                                Text(
                                    s,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (state.loading) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (ok != null || state.outcome is InsightOutcome.Failed) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(AppInsetColor)
                                .clickable(enabled = !state.loading) { state.refresh() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "让屁岱重新看", modifier = Modifier.size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable {
                            collapsed = !collapsed
                            visibilityStore.putBoolean(state.spec.id, collapsed)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (collapsed) "展开精选卡片" else "折叠精选卡片",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (!collapsed) {
                    controls?.invoke(this)
                    when (val o = state.outcome) {
                        is InsightOutcome.Ok -> {
                            Column(Modifier.alpha(if (state.loading) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                content(o.json)
                            }
                            if (askPrompt != null && !state.loading) {
                                PidaiChip("接着问屁岱", icon = Icons.AutoMirrored.Filled.Chat, onClick = {
                                    insights.ask(askPrompt(o.json), insightSnapshot(title, state.input, o))
                                })
                            }
                        }
                        InsightOutcome.Unavailable -> InsightHint("在屁岱页配好 API Key 后，这里由屁岱帮你挑重点。")
                        is InsightOutcome.Failed -> if (!state.loading) InsightHint("这次没看成：${o.message}")
                        null -> if (!state.loading) {
                            if (idleHint.isNotBlank()) InsightHint(idleHint)
                            if (idleAction != null) PidaiChip(idleAction, icon = Icons.Filled.AutoAwesome, emphasized = true, onClick = { state.refresh(force = false) })
                        }
                    }
                    if (state.loading && ok == null) InsightShimmer()
                }
            }
            Row(Modifier.align(Alignment.End).padding(end = 10.dp)) {
                InsightHint("关闭") { dismissed = true }
                state.spec.card?.let { card ->
                    InsightHint("不再提示") {
                        PidaiCards.set(card, false)
                        showBriefMessage("已关闭，可在屁岱设置中恢复")
                    }
                }
            }
        }
    }
}

/** 带进对话的快照：这张卡看了什么、查到了什么、给了什么，屁岱接着聊时直接用，不用再查。 */
private fun insightSnapshot(title: String, input: String?, ok: InsightOutcome.Ok): String = buildString {
    val minutes = ((kotlin.time.Clock.System.now().toEpochMilliseconds() - ok.at) / 60_000).coerceAtLeast(0)
    append("用户从「$title」卡片点进来。下面是这张卡的数据、你当时查到的工具结果和给出的结果，")
    append(if (minutes < 1) "刚生成。" else "约 $minutes 分钟前生成。")
    append("这些足够回答时直接用，不要重复调用同样的工具；用户要最新情况或这里没有的信息时再查。\n")
    if (!input.isNullOrBlank()) append("【卡片数据】\n").append(input.trim()).append('\n')
    if (ok.toolLog.isNotBlank()) append("【当时的工具结果】\n").append(ok.toolLog.trim()).append('\n')
    append("【你给出的结果】\n").append(ok.json.toString())
}

/** 屁岱的小徽标：主色渐变圆 + 星光；思考时慢慢呼吸。 */
@Composable
fun PidaiBadge(thinking: Boolean = false, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val primary = MiuixTheme.colorScheme.primary
    val phase = if (thinking) rememberLoopPhase() else null
    Box(
        Modifier.size(size)
            .graphicsLayer { alpha = phase?.let { breath(it.value, low = 0.55f) } ?: 1f }
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * 卡片里的操作按钮：小胶囊。[emphasized] 是实心主色（主要动作），否则淡主色底；[selected] 给开关类用。
 */
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
    val bg = when {
        emphasized || selected -> primary
        else -> primary.copy(alpha = 0.12f)
    }
    val fg = if (emphasized || selected) MiuixTheme.colorScheme.onPrimary else primary
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg)
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

/** 一排可横滑的选项胶囊（来源、偏好…）。 */
@Composable
fun PidaiChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 卡片里的输入框：圆角浅底 + 右侧发送圆钮。 */
@Composable
fun PidaiInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    enabled: Boolean = true,
) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(AppInsetColor)
            .border(1.dp, primary.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (enabled) onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(primary).alpha(if (enabled) 1f else 0.45f)
                .clickable(enabled = enabled, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "让屁岱排", tint = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun InsightHint(text: String, onClick: (() -> Unit)? = null) {
    val m = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
        .sizeIn(minWidth = 48.dp, minHeight = 32.dp).wrapContentSize().padding(horizontal = 6.dp)
    Text(text, m, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
        min < 1 -> "刚刚看过"
        min < 60 -> "$min 分钟前看过"
        min < 24 * 60 -> "${min / 60} 小时前看过"
        else -> "${min / (24 * 60)} 天前看过"
    }
}
