package com.xjtu.toolbox.agent

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
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
 * 屁岱主动提醒。
 *
 * - 全部本地模板，不调模型。
 * - 一次只说一件事。
 * - 闲话只填空：没有可行动提醒时才说，单独冷却，不挡上课/余额。
 * - 闲话全文必须露出来，不许省略号。
 */
data class ProactiveMessage(
    val id: String,
    val text: String,
    val prompt: String,
    val fullReveal: Boolean = false,
    val chatterLineId: String? = null,
) {
    companion object {
        const val MAX_CHARS = 24
    }
}

object ProactiveRules {

    private const val GLOBAL_COOLDOWN_MS = 15 * 60 * 1000L
    private const val RULE_COOLDOWN_MS = 60 * 60 * 1000L

    /**
     * 闲话冷却。原来是 6 分钟，太密了：气泡只活 8 秒，6 分钟一句在使用期间就是不停地冒。
     * 拉到 25 分钟，一次使用最多撞上一两句。
     */
    private const val CHATTER_COOLDOWN_MS = 25 * 60 * 1000L

    const val CHATTER_ID = "chatter"

    const val FIRST_DELAY_MS = 3_000L
    const val EVAL_INTERVAL_MS = 60_000L
    const val AUTO_DISMISS_MS = 8_000L

    /** 考试提前几天开始提醒。三天是"还来得及做点什么"和"提早焦虑"的分界。 */
    private const val EXAM_AHEAD_DAYS = 3

    private const val LOW_BALANCE = 50.0
    private const val CLASS_AHEAD_MIN = 30L

    private const val PREFS = "pidai_proactive"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastShownAt(ctx: Context, id: String): Long = prefs(ctx).getLong("shown_$id", 0L)
    fun lastAnyAt(ctx: Context): Long = prefs(ctx).getLong("shown_any", 0L)
    private fun lastChatterAt(ctx: Context): Long = prefs(ctx).getLong("shown_chatter_at", 0L)

    /**
     * 记下"自动冒过一次"。
     *
     * 关键改动：闲话现在**也**写 `shown_any`。原来只写 `shown_chatter_at`，
     * 于是闲话完全绕开了 15 分钟的全局冷却——正事气泡刚收，闲话立刻能接上，
     * 用户看到的就是它自顾自连着蹦。现在两条线共用同一个全局节流，
     * 「上次说话到现在」不够 15 分钟就一句都不说。
     */
    fun markShown(ctx: Context, id: String) {
        val now = System.currentTimeMillis()
        val e = prefs(ctx).edit().putLong("shown_$id", now).putLong("shown_any", now)
        if (id == CHATTER_ID) e.putLong("shown_chatter_at", now)
        e.apply()
    }

    fun markShown(ctx: Context, message: ProactiveMessage) {
        markShown(ctx, message.id)
        message.chatterLineId?.let { rememberChatterLine(ctx, it) }
    }

    /**
     * 记下"用户戳了一下，它回了一句"。
     *
     * 和 [markShown] 的区别是**不写 `shown_any`**：主动逗它不该把正事气泡憋回去
     * 15 分钟。只推进闲话自己的时间戳，并记住这句话别马上重复。
     */
    fun markTapped(ctx: Context, message: ProactiveMessage) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putLong("shown_${message.id}", now)
            .putLong("shown_chatter_at", now)
            .apply()
        message.chatterLineId?.let { rememberChatterLine(ctx, it) }
    }

    private fun chatterRecent(ctx: Context): List<String> =
        prefs(ctx).getString("chatter_recent", "")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }

    fun rememberChatterLine(ctx: Context, lineId: String) {
        val next = (listOf(lineId) + chatterRecent(ctx)).distinct().take(12)
        prefs(ctx).edit().putString("chatter_recent", next.joinToString(",")).apply()
    }

    fun markDismissed(ctx: Context, id: String) {
        val n = prefs(ctx).getInt("dismiss_$id", 0) + 1
        prefs(ctx).edit().putInt("dismiss_$id", n).apply()
    }

    private fun cooldownFor(ctx: Context, id: String): Long {
        val dismissed = prefs(ctx).getInt("dismiss_$id", 0)
        val factor = (1 shl dismissed.coerceAtMost(3)).toLong()
        val base = when (id) {
            CHATTER_ID -> CHATTER_COOLDOWN_MS
            // 考试倒计时的窗口有三天，用一小时的通用冷却会在这三天里反复念叨同一件事。
            // 半天一次已经足够：这是"别忘了"，不是"马上去做"。
            "exam" -> 12 * 60 * 60 * 1000L
            else -> RULE_COOLDOWN_MS
        }
        return base * factor
    }

    fun markUseful(ctx: Context, id: String) {
        prefs(ctx).edit().putInt("dismiss_$id", 0).apply()
    }

    fun pick(
        ctx: Context,
        balance: Double?,
        nextCourseName: String?,
        minutesToClass: Long?,
        newGradeCount: Int,
        latestNotice: String?,
        libraryPendingAction: String? = null,
        examCountdown: com.xjtu.toolbox.schedule.ExamCountdown.Next? = null,
    ): ProactiveMessage? {
        val now = System.currentTimeMillis()
        val alert = pickAlert(
            ctx, now, balance, nextCourseName, minutesToClass, newGradeCount, latestNotice,
            libraryPendingAction, examCountdown,
            com.xjtu.toolbox.schedule.ScheduleDiff.pending(ctx),
            com.xjtu.toolbox.home.HomeSignals.attendanceAlert,
        )
        if (alert != null) return alert
        if (now - lastAnyAt(ctx) < GLOBAL_COOLDOWN_MS) return null
        return pickChatter(ctx, now, nextCourseName, minutesToClass)
    }

    private fun pickAlert(
        ctx: Context,
        now: Long,
        balance: Double?,
        nextCourseName: String?,
        minutesToClass: Long?,
        newGradeCount: Int,
        latestNotice: String?,
        libraryPendingAction: String?,
        examCountdown: com.xjtu.toolbox.schedule.ExamCountdown.Next?,
        scheduleChange: String?,
        attendanceAlert: String?,
    ): ProactiveMessage? {
        if (now - lastAnyAt(ctx) < GLOBAL_COOLDOWN_MS) return null
        val candidates = buildList {
            // 课表变更排最前：调课停课换教室不知道就会白跑一趟，
            // 而学校改课表是不通知的，App 是唯一可能告诉他的地方。
            if (scheduleChange != null) {
                add(ProactiveMessage("schedule_change", scheduleChange, "我的课表最近有什么变动？"))
            }
            // 只在异常**新增**时才有值（见 HomeStatsRefresher），所以到这里就直接报。
            // 措辞保持中性——按用户要求，成绩、体测、考勤这类事一律不调侃。
            if (attendanceAlert != null) {
                add(ProactiveMessage("attendance", attendanceAlert, "我最近的考勤情况怎么样？"))
            }
            // 考试排在图书馆之后、余额之前：不像座位那样过号就没，但比钱急。
            // 只在三天内提，更早提没有行动意义，只是让人焦虑。
            if (examCountdown != null && examCountdown.daysLeft <= EXAM_AHEAD_DAYS) {
                add(
                    ProactiveMessage(
                        "exam",
                        "${examCountdown.exam.courseName}${examCountdown.label}",
                        "${examCountdown.exam.courseName}什么时候考、在哪考？帮我排一下复习",
                    )
                )
            }
            // 图书馆排在最前：这两个动作**有时限**，不做就丢座位，比余额和成绩都急。
            // 传进来的是 LibraryApi.classifyActionLabel 归一化后的 label（入馆签到 / 中途返回），
            // 直接就是要用户做的事，不用再翻译一次。
            if (libraryPendingAction != null) {
                add(
                    ProactiveMessage(
                        "library",
                        "图书馆座位该${libraryPendingAction}了",
                        "我的图书馆预约现在什么状态？要做什么？",
                    )
                )
            }
            if (balance != null && balance < LOW_BALANCE) {
                add(
                    ProactiveMessage(
                        "balance",
                        "校园卡只剩 ¥${"%.2f".format(balance)} 了，记得充",
                        "我的校园卡余额还有多少？最近都花在哪了？",
                    )
                )
            }
            if (newGradeCount > 0) {
                add(
                    ProactiveMessage(
                        "grade",
                        "有 $newGradeCount 门新成绩出了",
                        "帮我看看新出的成绩",
                    )
                )
            }
            if (nextCourseName != null && minutesToClass != null && minutesToClass in 0..CLASS_AHEAD_MIN) {
                add(
                    ProactiveMessage(
                        "class",
                        "${minutesToClass}分钟后上$nextCourseName",
                        "我今天还有哪些课？在哪上？",
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

    /**
     * 用户**主动点**屁岱时的闲话，和自动冒泡走两套规则。
     *
     * 这里刻意绕开闲话冷却：冷却是给"它自己突然开口"用的，防打扰；
     * 而用户戳它一下却不吭声，看起来就是坏了。但仍然避开最近说过的句子，
     * 免得连点两下讲同一句。
     *
     * 课程信息拿不到（那份状态在首页），情境句会自动落选，不影响其余句子。
     */
    fun pickOnTap(ctx: Context): ProactiveMessage? {
        val line = ChatterPool.pick(
            java.time.LocalDateTime.now(),
            chatterRecent(ctx),
            null,
            null,
        ) ?: return null
        return ProactiveMessage(
            id = CHATTER_ID,
            text = line.text,
            prompt = "",
            fullReveal = true,
            chatterLineId = line.id,
        )
    }

    private fun pickChatter(
        ctx: Context,
        nowMs: Long,
        nextCourseName: String?,
        minutesToClass: Long?,
    ): ProactiveMessage? {
        if (nowMs - lastChatterAt(ctx) < cooldownFor(ctx, CHATTER_ID)) return null
        val line = ChatterPool.pick(
            java.time.LocalDateTime.now(),
            chatterRecent(ctx),
            nextCourseName,
            minutesToClass,
        ) ?: return null
        return ProactiveMessage(
            id = CHATTER_ID,
            text = line.text,
            prompt = "",
            fullReveal = true,
            chatterLineId = line.id,
        )
    }
}

/**
 * 气泡外形。[arrowFromStart] 为 null 时尖角取气泡**自身**水平中心。
 * 挂在底栏正中的气泡必须走这一支：锚点固定在屏幕中线，尖角要随气泡宽度走，
 * 用固定 dp 的话文案一长一短尖角就偏出锚点了。
 */
private class BubbleShape(private val arrowFromStart: androidx.compose.ui.unit.Dp?) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val arrowH = with(density) { 7.dp.toPx() }
        val arrowW = with(density) { 12.dp.toPx() }
        val r = with(density) { 14.dp.toPx() }
        val bodyBottom = size.height - arrowH
        val cx = (arrowFromStart?.let { with(density) { it.toPx() } } ?: (size.width / 2f))
            .coerceIn(arrowW, (size.width - arrowW).coerceAtLeast(arrowW))
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = bodyBottom,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
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

@Composable
fun ProactiveBubbleView(
    message: ProactiveMessage,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onTimeout: () -> Unit,
    arrowFromStart: androidx.compose.ui.unit.Dp? = null,
    maxWidth: androidx.compose.ui.unit.Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    // visible 只管"气泡在不在"，**不跟着文案走**。
    //
    // 之前它 remember(id, text)：换一条闲话时先被重置成 false 再由下面的 effect 置回 true，
    // 但这两次赋值发生在同一帧内，AnimatedVisibility 根本觉察不到变化，
    // 于是连点屁岱时气泡只是原地换字，一点动静没有——它是"活的"这件事就没了。
    //
    // 现在拆成两层：外层 AnimatedVisibility 负责整体出现/消失，
    // 内层 AnimatedContent 负责一条换一条，每次换都从尖角重新弹一遍。
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message.id, message.text) {
        visible = true
        delay(ProactiveRules.AUTO_DISMISS_MS)
        visible = false
        delay(200)
        onTimeout()
    }
    // 缩放锚点跟着尖角走：尖角在哪，气泡就从哪「长出来」。
    val pivot = if (arrowFromStart == null) TransformOrigin(0.5f, 1f) else TransformOrigin(0.12f, 1f)
    AnimatedVisibility(
        visible = visible,
        // 从尖角那一点**弹出来**，而不是淡入。初始缩放压到 0.35 再用欠阻尼 spring 回弹，
        // 观感上就是"从屁岱头顶长出来"；tween 做不出这个过冲，只会像一张图渐显。
        // 淡入要比缩放快得多收尾，否则半透明的放大过程会显得糊。
        enter = fadeIn(animationSpec = tween(90)) +
            scaleIn(
                initialScale = 0.35f,
                transformOrigin = pivot,
                animationSpec = spring(
                    dampingRatio = 0.52f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        // 收回去也回到同一点，别原地淡出。
        exit = fadeOut(animationSpec = tween(140)) +
            scaleOut(
                targetScale = 0.6f,
                transformOrigin = pivot,
                animationSpec = tween(160),
            ),
        modifier = modifier.wrapContentWidth(),
    ) {
        AnimatedContent(
            targetState = message,
            transitionSpec = {
                // 新的一条从尖角弹出来，旧的一条同时缩回尖角。两者叠在一起，
                // 看着就是"它又说了一句"，而不是"文字被替换了"。
                (
                    fadeIn(animationSpec = tween(90)) +
                        scaleIn(
                            initialScale = 0.5f,
                            transformOrigin = pivot,
                            animationSpec = spring(
                                dampingRatio = 0.5f,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    ).togetherWith(
                    fadeOut(animationSpec = tween(90)) +
                        scaleOut(
                            targetScale = 0.7f,
                            transformOrigin = pivot,
                            animationSpec = tween(110),
                        )
                // 尺寸变化不裁剪：长短不一的两条在交叉淡入淡出时不该被对方的框切掉。
                ) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomCenter,
            label = "proactiveBubbleSwap",
        ) { shown ->
        Row(
            Modifier
                .wrapContentWidth()
                .widthIn(max = maxWidth)
                .clip(BubbleShape(arrowFromStart))
                .background(MiuixTheme.colorScheme.primary)
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown.text,
                modifier = Modifier.wrapContentWidth(),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onPrimary,
                maxLines = if (shown.fullReveal) 2 else 1,
                overflow = if (shown.fullReveal) TextOverflow.Clip else TextOverflow.Ellipsis,
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
}

/**
 * 气泡的**产生位**和**展示位**不在同一棵子树：文案要靠余额、下节课、新成绩算出来，
 * 这些状态都在首页 HomeTab 里；而气泡现在挂在底栏屁岱头顶，属于 Scaffold 层。
 * 两边之间只差一个 message，为它把首页那一大坨状态提升到 Scaffold 不划算，
 * 用一个进程内单例中转。
 */
object ProactiveBubbleHost {
    var message by mutableStateOf<ProactiveMessage?>(null)

    /**
     * 自动冒泡的抑制开关。用户正待在屁岱这一页时置 true。
     *
     * 人都已经在跟它聊天了，再让它从底栏探出头说句不相干的闲话，既遮挡输入框也很怪。
     * 只挡自动的那一路；用户主动点按钮逗它照常回应。
     */
    var autoSuppressed by mutableStateOf(false)

    fun clear() {
        message = null
    }
}

object AgentPendingPrompt {
    @Volatile
    private var pending: String? = null

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
