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
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AccountType
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
    val prompt: String = "",
    val fullReveal: Boolean = false,
    val chatterLineId: String? = null,
    /** 导入皮肤的闲话可同时请求一个动作；缺失或已换皮肤时自动忽略。 */
    val skinActionId: String? = null,
    /**
     * 非空则点击打开此路由，不进屁岱。
     * 气泡在说一件 App 里已经有页面的事（通知原文、加餐券、签到）时走这一支。
     */
    val openRoute: String? = null,
    /**
     * 进屁岱时附给模型的本地快照（不进聊天气泡）。
     * 只说明「用户点的是哪件事」以及当时已知字段；缺的不要编，完整数据仍调工具。
     */
    val eventSnapshot: String? = null,
) {
    companion object {
        const val MAX_CHARS = 24
    }
}

/**
 * 主动提醒的三档：关 / 少 / 标准。
 *
 * 只决定「要不要生成气泡」「要不要闲聊」「全局冷却多长」，不碰磁盘也不碰时间，
 * 所以这几条判断都拆成了下面几个纯函数，能直接对着枚举值单测，不用假 Context。
 */
enum class ProactiveLevel { OFF, LOW, STANDARD }

object ProactiveRules {

    private const val GLOBAL_COOLDOWN_MS = 15 * 60 * 1000L
    private const val RULE_COOLDOWN_MS = 60 * 60 * 1000L

    /** 「少」档下正事提醒之间的全局冷却：从 15 分钟拉到 1 小时，只留真正要紧的事。 */
    private const val LOW_GLOBAL_COOLDOWN_MS = 60 * 60 * 1000L

    /**
     * 闲话冷却。原来是 6 分钟，太密了：气泡只活 8 秒，6 分钟一句在使用期间就是不停地冒。
     * 拉到 25 分钟，一次使用最多撞上一两句。
     */
    private const val CHATTER_COOLDOWN_MS = 25 * 60 * 1000L

    const val CHATTER_ID = "chatter"

    const val FIRST_DELAY_MS = 3_000L
    const val EVAL_INTERVAL_MS = 60_000L
    const val AUTO_DISMISS_MS = 8_000L

    /**
     * 考试提前几天开始提醒，直接引用日程页考试卡片变红的同一个阈值——气泡说「快考试了」
     * 的那一刻，必须和卡片变红的那一刻是同一天，不然用户会觉得两处对不上。
     */
    private val EXAM_AHEAD_DAYS = com.xjtu.toolbox.schedule.ExamCountdown.SOON_DAYS

    private const val LOW_BALANCE = 50.0
    private const val CLASS_AHEAD_MIN = 30L

    private const val PREFS = "pidai_proactive"
    private const val LEVEL_KEY = "level"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前挡位，给设置面板做即时回显；`pick()` 自己每次都直接从磁盘读，不依赖这份缓存。 */
    var proactiveLevel by mutableStateOf(ProactiveLevel.STANDARD)
        private set

    /** 从磁盘读一次并写入上面那份缓存。设置面板打开时调一次即可。 */
    fun loadProactiveLevel(ctx: Context) {
        proactiveLevel = readProactiveLevel(ctx)
    }

    /** 选择之后立即落盘 + 更新缓存。 */
    fun setProactiveLevel(ctx: Context, level: ProactiveLevel) {
        proactiveLevel = level
        prefs(ctx).edit().putString(LEVEL_KEY, level.name).apply()
    }

    private fun readProactiveLevel(ctx: Context): ProactiveLevel =
        prefs(ctx).getString(LEVEL_KEY, null)
            ?.let { raw -> ProactiveLevel.entries.firstOrNull { it.name == raw } }
            ?: ProactiveLevel.STANDARD

    /** 关档完全不生成；少、标准两档都保留「正事」提醒。 */
    fun alertsAllowed(level: ProactiveLevel): Boolean = level != ProactiveLevel.OFF

    /** 只有标准档才闲聊；少档只保留下节课、考试、余额这类正事。 */
    fun chatterAllowed(level: ProactiveLevel): Boolean = level == ProactiveLevel.STANDARD

    /** 少档把正事提醒之间的全局冷却从 15 分钟拉到 1 小时；标准档维持原样。 */
    fun globalCooldownMs(level: ProactiveLevel): Long =
        if (level == ProactiveLevel.LOW) LOW_GLOBAL_COOLDOWN_MS else GLOBAL_COOLDOWN_MS

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
        PidaiSkinActionHost.request(message.skinActionId)
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
        PidaiSkinActionHost.request(message.skinActionId)
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
        latestNoticeLink: String? = null,
        accountType: AccountType? = null,
    ): ProactiveMessage? {
        val level = readProactiveLevel(ctx)
        if (!alertsAllowed(level)) return null
        val now = System.currentTimeMillis()
        val cooldown = globalCooldownMs(level)
        val alert = pickAlert(
            ctx, now, balance, nextCourseName, minutesToClass, newGradeCount, latestNotice,
            libraryPendingAction, examCountdown,
            com.xjtu.toolbox.schedule.ScheduleDiff.pending(ctx),
            com.xjtu.toolbox.home.HomeSignals.attendanceAlert,
            com.xjtu.toolbox.home.HomeSignals.couponAlert,
            latestNoticeLink,
            accountType,
            cooldown,
        )
        if (alert != null) return alert
        if (!chatterAllowed(level)) return null
        if (now - lastAnyAt(ctx) < cooldown) return null
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
        couponAlert: String?,
        latestNoticeLink: String?,
        accountType: AccountType?,
        globalCooldownMs: Long,
    ): ProactiveMessage? {
        if (now - lastAnyAt(ctx) < globalCooldownMs) return null
        val candidates = buildList {
            // 课表变更排最前：调课停课换教室不知道就会白跑一趟，
            // 而学校改课表是不通知的，App 是唯一可能告诉他的地方。
            if (scheduleChange != null) {
                add(ProactiveMessage("schedule_change", scheduleChange, openRoute = Routes.SCHEDULE))
            }
            // 只在异常**新增**时才有值（见 HomeStatsRefresher），所以到这里就直接报。
            // 措辞保持中性——按用户要求，成绩、体测、考勤这类事一律不调侃。
            if (attendanceAlert != null) {
                add(ProactiveMessage("attendance", attendanceAlert, openRoute = Routes.NEW_ATTENDANCE))
            }
            // 考试：新版分级布局没有独立考试页，点日程也落不到那层 sheet。
            // 进屁岱，并把这场考试的缓存字段当快照——对准「点的是哪场」，缺的仍调工具。
            if (examCountdown != null && examCountdown.daysLeft <= EXAM_AHEAD_DAYS) {
                val exam = examCountdown.exam
                add(
                    ProactiveMessage(
                        id = "exam",
                        text = "${exam.courseName}${examCountdown.label}",
                        prompt = "${exam.courseName}什么时候考、在哪考？帮我排一下复习",
                        eventSnapshot = eventFields(
                            "kind" to "exam",
                            "course" to exam.courseName,
                            "code" to exam.courseCode,
                            "date" to exam.examDate,
                            "time" to exam.examTime,
                            "location" to exam.location,
                            "seat" to exam.seatNumber,
                            "daysLeft" to examCountdown.daysLeft.toString(),
                        ),
                    )
                )
            }
            // 图书馆：这两个动作有时限，不做就丢座位。点气泡去图书馆页，别再问一遍状态。
            if (libraryPendingAction != null) {
                add(
                    ProactiveMessage(
                        id = "library",
                        text = "图书馆座位该${libraryPendingAction}了",
                        openRoute = Routes.LIBRARY,
                    )
                )
            }
            // 加餐券排在余额前面：券不领不用就作废，而余额低了随时能充。
            if (couponAlert != null) {
                add(ProactiveMessage("coupon", couponAlert, openRoute = Routes.COUPON))
            }
            if (balance != null && balance < LOW_BALANCE) {
                add(
                    ProactiveMessage(
                        id = "balance",
                        text = "校园卡只剩 ¥${"%.2f".format(balance)} 了，记得充",
                        prompt = "我的校园卡余额还有多少？最近都花在哪了？",
                        eventSnapshot = eventFields(
                            "kind" to "campus_card",
                            "balance" to "%.2f".format(balance),
                            "note" to "余额来自本地缓存，流水和是否最新需查工具",
                        ),
                    )
                )
            }
            if (newGradeCount > 0) {
                add(
                    ProactiveMessage(
                        id = "grade",
                        text = "有 $newGradeCount 门新成绩出了",
                        prompt = "帮我看看新出的成绩",
                        eventSnapshot = eventFields(
                            "kind" to "grades",
                            "newCount" to newGradeCount.toString(),
                            "note" to "快照只有门数，没有科目和分数",
                        ),
                    )
                )
            }
            if (nextCourseName != null && minutesToClass != null && minutesToClass in 0..CLASS_AHEAD_MIN) {
                add(
                    ProactiveMessage(
                        id = "class",
                        text = "${minutesToClass}分钟后上$nextCourseName",
                        openRoute = Routes.SCHEDULE,
                    )
                )
            }
            if (!latestNotice.isNullOrBlank()) {
                add(
                    ProactiveMessage(
                        id = "notice",
                        text = "教务处新通知：$latestNotice",
                        openRoute = if (!latestNoticeLink.isNullOrBlank()) {
                            Routes.browser(latestNoticeLink)
                        } else {
                            Routes.NOTIFICATION
                        },
                    )
                )
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
        val (skinLines, skinMix) = activeSkinChatter()
        val line = ChatterPool.pick(
            java.time.LocalDateTime.now(),
            chatterRecent(ctx),
            null,
            null,
            skinLines,
            skinMix,
        ) ?: return null
        return ProactiveMessage(
            id = CHATTER_ID,
            text = line.text,
            prompt = "",
            fullReveal = true,
            chatterLineId = line.id,
            skinActionId = line.action,
        )
    }

    private fun pickChatter(
        ctx: Context,
        nowMs: Long,
        nextCourseName: String?,
        minutesToClass: Long?,
    ): ProactiveMessage? {
        if (nowMs - lastChatterAt(ctx) < cooldownFor(ctx, CHATTER_ID)) return null
        val (skinLines, skinMix) = activeSkinChatter()
        val line = ChatterPool.pick(
            java.time.LocalDateTime.now(),
            chatterRecent(ctx),
            nextCourseName,
            minutesToClass,
            skinLines,
            skinMix,
        ) ?: return null
        return ProactiveMessage(
            id = CHATTER_ID,
            text = line.text,
            prompt = "",
            fullReveal = true,
            chatterLineId = line.id,
            skinActionId = line.action,
        )
    }

    private fun activeSkinChatter(): Pair<List<ChatterLine>, Double> {
        val skin = PidaiAppearanceHost.activeSkin ?: return emptyList<ChatterLine>() to 0.0
        val persona = skin.persona ?: return emptyList<ChatterLine>() to 0.0
        return persona.chatter.map { line ->
            ChatterLine(
                id = "${skin.manifest.id}:${line.id}",
                text = line.text,
                hours = line.hours,
                months = line.months,
                weekdays = line.weekdays,
                weight = line.weight,
                action = line.action,
            )
        } to persona.chatterMix
    }

    /** 气泡事件快照：只写非空字段，给模型当「点的是哪件事」。 */
    private fun eventFields(vararg fields: Pair<String, String?>): String =
        fields.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { "$k: $it" } }
            .joinToString("\n")
}

/** 气泡尖角朝向。底栏屁岱在气泡正下方 → [Bottom]；侧栏屁岱在气泡左边 → [Start]。 */
enum class BubbleArrowSide { Bottom, Start }

/** 尖角的伸出深度与底宽，外形与布局内边距都按它算，两处必须一致。 */
private val ArrowDepth = 7.dp
private val ArrowBase = 12.dp

/**
 * 气泡外形。[arrowOffset] 为 null 时尖角取气泡**自身**在该轴上的中心
 * （[BubbleArrowSide.Bottom] 取水平中心，[BubbleArrowSide.Start] 取垂直中心）。
 * 挂在底栏正中的气泡必须走这一支：锚点固定在屏幕中线，尖角要随气泡宽度走，
 * 用固定 dp 的话文案一长一短尖角就偏出锚点了。
 */
private class BubbleShape(
    private val side: BubbleArrowSide,
    private val arrowOffset: androidx.compose.ui.unit.Dp?,
) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val arrowH = with(density) { ArrowDepth.toPx() }
        val arrowW = with(density) { ArrowBase.toPx() }
        val r = with(density) { 14.dp.toPx() }
        val offsetPx = arrowOffset?.let { with(density) { it.toPx() } }
        val path = Path().apply {
            when (side) {
                BubbleArrowSide.Bottom -> {
                    val bodyBottom = size.height - arrowH
                    val cx = (offsetPx ?: (size.width / 2f))
                        .coerceIn(arrowW, (size.width - arrowW).coerceAtLeast(arrowW))
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
                BubbleArrowSide.Start -> {
                    val bodyLeft = arrowH
                    val cy = (offsetPx ?: (size.height / 2f))
                        .coerceIn(arrowW, (size.height - arrowW).coerceAtLeast(arrowW))
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = bodyLeft, top = 0f, right = size.width, bottom = size.height,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        )
                    )
                    moveTo(bodyLeft, cy - arrowW / 2)
                    lineTo(0f, cy)
                    lineTo(bodyLeft, cy + arrowW / 2)
                    close()
                }
            }
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
    /** 尖角朝向。默认朝下（底栏屁岱在气泡正下方）。 */
    arrowSide: BubbleArrowSide = BubbleArrowSide.Bottom,
    /** [BubbleArrowSide.Bottom] 时尖角距起始边的距离；null = 气泡自身水平中心。 */
    arrowFromStart: androidx.compose.ui.unit.Dp? = null,
    /** [BubbleArrowSide.Start] 时尖角距顶边的距离；null = 气泡自身垂直中心。 */
    arrowFromTop: androidx.compose.ui.unit.Dp? = null,
    maxWidth: androidx.compose.ui.unit.Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    val arrowOffset = when (arrowSide) {
        BubbleArrowSide.Bottom -> arrowFromStart
        BubbleArrowSide.Start -> arrowFromTop
    }
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
    val pivot = when {
        arrowSide == BubbleArrowSide.Start -> TransformOrigin(0f, 0.5f)
        arrowOffset == null -> TransformOrigin(0.5f, 1f)
        else -> TransformOrigin(0.12f, 1f)
    }
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
            contentAlignment = if (arrowSide == BubbleArrowSide.Start) {
                Alignment.CenterStart
            } else {
                Alignment.BottomCenter
            },
            label = "proactiveBubbleSwap",
        ) { shown ->
        Row(
            Modifier
                .wrapContentWidth()
                .widthIn(max = maxWidth)
                .clip(BubbleShape(arrowSide, arrowOffset))
                .background(MiuixTheme.colorScheme.primary)
                .clickable(onClick = onOpen)
                // 尖角那一侧要多留出它的伸出深度，否则文字会压到三角上。
                // 朝下时 10+7=17dp，与改造前的固定值一致。
                .padding(
                    start = if (arrowSide == BubbleArrowSide.Start) 14.dp + ArrowDepth else 14.dp,
                    end = 8.dp,
                    top = 10.dp,
                    bottom = if (arrowSide == BubbleArrowSide.Bottom) 10.dp + ArrowDepth else 10.dp,
                ),
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
    data class Pending(
        val text: String,
        /** 不进聊天气泡，只进发给模型的 user 消息。 */
        val snapshot: String? = null,
    )

    @Volatile
    private var pending: Pending? = null

    var generation by mutableIntStateOf(0)
        private set

    fun set(text: String, snapshot: String? = null) {
        pending = Pending(text, snapshot)
        generation++
    }

    fun consume(): Pending? {
        val v = pending
        pending = null
        return v
    }
}
