package com.xjtu.toolbox.agent

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 屁岱的主动提醒。
 *
 * 设计原则（与 PLAN-PIDAI-PROACTIVE.md 一致）：
 * - **全部不调用 AI**：文案是本地模板，成本与延迟为零，也不可能胡说；
 *   但呈现上是屁岱在说话，点进去时这条提醒会作为**真实的开场消息**进入对话，
 *   不是假装——用户顺着追问时才真正调模型。
 * - **一次只说一件事**：多条同时满足按优先级取一条，排队冒泡是灾难。
 * - **数据全部来自本地缓存**：不为提醒额外发请求，抓取由 HomeStatsRefresher 统一负责，
 *   一次抓取两处消费。
 */
data class ProactiveMessage(
    /** 规则 id，用于冷却与"关掉几次"统计 */
    val id: String,
    /** 气泡文案，硬性 [MAX_CHARS] 字上限 */
    val text: String,
    /** 点击后送进对话的开场白 */
    val prompt: String,
) {
    companion object {
        const val MAX_CHARS = 24
    }
}

object ProactiveRules {

    /**
     * 冷却：同一条提醒多久内不重复。
     *
     * **当前是测试配置，非常激进**（见 [TESTING]）。上线前必须调回注释里的正式值，
     * 否则会把用户烦走——这正是方案里反复强调的风险点。
     */
    private const val TESTING = false

    /** 全局：两次冒泡的最小间隔。正式值 2 小时。 */
    private val GLOBAL_COOLDOWN_MS = if (TESTING) 20_000L else 2 * 60 * 60 * 1000L

    /** 单条规则的冷却。正式值 12 小时。 */
    private val RULE_COOLDOWN_MS = if (TESTING) 30_000L else 12 * 60 * 60 * 1000L

    /** 冷启动后延迟多久才冒第一个。正式值 3 秒。 */
    val FIRST_DELAY_MS = if (TESTING) 1_500L else 3_000L

    /** 多久重新评估一次是否该冒泡。正式值 60 秒。 */
    val EVAL_INTERVAL_MS = if (TESTING) 10_000L else 60_000L

    /** 无操作多久自动消失。 */
    const val AUTO_DISMISS_MS = 8_000L

    /**
     * 余额提醒阈值。测试期调到 1000 以便用真实数据触发（真机余额 ¥146.49，
     * 按正式阈值 50 永远不会冒泡，也就无从验证气泡本身）。上线前改回 50。
     */
    private val LOW_BALANCE = if (TESTING) 1000.0 else 50.0

    /** 上课提醒的提前量。测试期放宽到 12 小时，短学期没有临近课程时也能触发。 */
    private val CLASS_AHEAD_MIN = if (TESTING) 720L else 30L

    private const val PREFS = "pidai_proactive"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastShownAt(ctx: Context, id: String): Long = prefs(ctx).getLong("shown_$id", 0L)
    fun lastAnyAt(ctx: Context): Long = prefs(ctx).getLong("shown_any", 0L)

    fun markShown(ctx: Context, id: String) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong("shown_$id", now).putLong("shown_any", now).apply()
    }

    /** 用户手动关掉一条提醒。只累计次数，用于放缓频率——**不会禁用**，见 [cooldownFor]。 */
    fun markDismissed(ctx: Context, id: String) {
        val n = prefs(ctx).getInt("dismiss_$id", 0) + 1
        prefs(ctx).edit().putInt("dismiss_$id", n).apply()
    }

    /**
     * 被关掉后**放缓**该规则，而不是禁用它。
     *
     * 关掉往往只表示"我看到了"，不表示"别再提"——比如余额提醒，用户看完随手关掉，
     * 但下周余额还是低的时候他仍然想知道。所以每关一次冷却翻倍（上限 8 倍），
     * 既能让烦人的提醒迅速稀释，又不会因为几次顺手关闭就永久失去一类提醒。
     * 用户真不想要，设置里有开关。
     */
    private fun cooldownFor(ctx: Context, id: String): Long {
        val dismissed = prefs(ctx).getInt("dismiss_$id", 0)
        val factor = (1 shl dismissed.coerceAtMost(3)).toLong()   // 1,2,4,8
        return RULE_COOLDOWN_MS * factor
    }

    /** 用户点开过（说明这条有用），清零关闭计数、恢复正常频率。 */
    fun markUseful(ctx: Context, id: String) {
        prefs(ctx).edit().putInt("dismiss_${id}", 0).apply()
    }

    /**
     * 挑出当前最该说的一件事。没有就返回 null（**没事就一句都不说**，
     * 不做"今天没课哦"这种无行动价值的寒暄）。
     *
     * @param nextCourseName 下一节课名，null 表示近期无课
     * @param minutesToClass 距上课分钟数
     */
    fun pick(
        ctx: Context,
        balance: Double?,
        nextCourseName: String?,
        minutesToClass: Long?,
        newGradeCount: Int,
        latestNotice: String?,
    ): ProactiveMessage? {
        val now = System.currentTimeMillis()
        if (now - lastAnyAt(ctx) < GLOBAL_COOLDOWN_MS) return null

        // 优先级：余额 > 成绩 > 上课 > 通知。同时满足只说最重要的一条。
        val candidates = buildList {
            if (balance != null && balance < LOW_BALANCE) {
                add(
                    ProactiveMessage(
                        "balance",
                        "校园卡只剩 ¥${"%.2f".format(balance)} 了，记得充",
                        "我的校园卡余额还有多少？最近都花在哪了？"
                    )
                )
            }
            if (newGradeCount > 0) {
                add(
                    ProactiveMessage(
                        "grade",
                        "有 $newGradeCount 门新成绩出了",
                        "帮我看看新出的成绩"
                    )
                )
            }
            if (nextCourseName != null && minutesToClass != null && minutesToClass in 0..CLASS_AHEAD_MIN) {
                add(
                    ProactiveMessage(
                        "class",
                        "${minutesToClass}分钟后上$nextCourseName",
                        "我今天还有哪些课？在哪上？"
                    )
                )
            }
            if (!latestNotice.isNullOrBlank()) {
                add(ProactiveMessage("notice", "教务处新通知：$latestNotice", "教务处最近有什么通知？"))
            }
        }

        return candidates.firstOrNull { m ->
            now - lastShownAt(ctx, m.id) >= cooldownFor(ctx, m.id)
        }?.let { it.copy(text = it.text.take(ProactiveMessage.MAX_CHARS)) }
    }
}

/**
 * 带尖角的气泡形状。
 *
 * 尖角位置用**绝对距离**（距气泡左边缘多少 dp）而不是百分比：气泡宽度随文案长短变化，
 * 用百分比的话文案一长尖角就偏走了；而它要对准的那个图标位置是固定的。
 */
private class BubbleShape(private val arrowFromStart: androidx.compose.ui.unit.Dp) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val arrowH = with(density) { 7.dp.toPx() }
        val arrowW = with(density) { 12.dp.toPx() }
        val r = with(density) { 14.dp.toPx() }
        val bodyBottom = size.height - arrowH
        val cx = with(density) { arrowFromStart.toPx() }
            .coerceIn(r + arrowW, size.width - r - arrowW)
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = bodyBottom,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
            )
            moveTo(cx - arrowW / 2, bodyBottom)
            lineTo(cx, size.height)
            lineTo(cx + arrowW / 2, bodyBottom)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 气泡本体。调用方负责定位（通常绝对定位在屁岱入口正上方）。
 *
 * @param onOpen 点击气泡：进入屁岱对话，并把 [ProactiveMessage.prompt] 作为开场消息
 * @param onDismiss 手动关闭
 */
@Composable
fun ProactiveBubbleView(
    message: ProactiveMessage,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onTimeout: () -> Unit,
    /** 尖角距气泡左边缘的距离，由调用方按锚点图标的实际位置算出 */
    arrowFromStart: androidx.compose.ui.unit.Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    var visible by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) {
        visible = true
        delay(ProactiveRules.AUTO_DISMISS_MS)
        visible = false
        delay(200)
        // 自动淡出走 onTimeout 而**不是** onDismiss：用户没点 ×，只是没理它，
        // 这不构成"不想要"。混用的话每次超时都记一次拒绝，退避倍数飞涨，
        // 混用会让退避倍数快速涨到上限，几分钟后就不再冒泡。
        onTimeout()
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .widthIn(max = 260.dp)
                .clip(BubbleShape(arrowFromStart))
                .background(MiuixTheme.colorScheme.primary)
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message.text,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭提醒",
                    tint = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * 主动提醒点开后要送进对话的开场白。
 *
 * 用一个进程内的临时槽传递，而不是给导航加参数：路由是纯字符串的，塞长文本要编码转义，
 * 且这条内容只在"从气泡跳进去"的一瞬间有意义，没有持久化价值。
 *
 * **取用即清空**（[consume]），避免下次进屁岱又莫名其妙自己发一条。
 */
object AgentPendingPrompt {
    @Volatile
    private var pending: String? = null

    /** Compose 观察此值，才能在 Agent 已打开时收到第二次深链 / 搜索。 */
    var generation by mutableIntStateOf(0)
        private set

    fun set(text: String) {
        pending = text
        generation++
    }

    fun consume(): String? {
        val v = pending
        pending = null
        return v
    }
}
