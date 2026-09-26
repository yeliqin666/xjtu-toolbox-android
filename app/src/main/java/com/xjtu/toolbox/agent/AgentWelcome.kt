package com.xjtu.toolbox.agent

import com.xjtu.toolbox.ui.components.pressScale
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.HolidayApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 屁岱的首屏（还没开始聊的时候）。三层：
 *
 * 1. 屁岱本体 + 一句看场合的问候（复用闲话池：今天几节课、明早有没有早八、快放假了……）；
 * 2. 两列「问题卡片」：每张带一行**从本地缓存读出来的真实预览**，点一下直接发出去，
 *    哪几张排前面跟着时段走（早上课表、饭点饭卡和食堂、晚上明天的课）；
 * 3. 「我还能」能力标签：点了把一句示范问题填进输入框，不直接发，给人改的余地。
 *
 * 没配模型时第 2 层换成一张引导卡，先让人知道配好之后能干什么。
 * 数据只读磁盘缓存，不联网：首屏要秒开，读不到的卡片直接不出，不放空壳。
 */

/** 首屏要用的本地数据快照。 */
internal data class WelcomeFacts(
    val chatter: ChatterFacts = ChatterFacts(),
    /** 今天还没开始的下一节课。 */
    val nextToday: CourseItem? = null,
    val todayLeft: Int = 0,
    val tomorrow: List<CourseItem> = emptyList(),
    val exam: ExamCountdown.Next? = null,
    val balance: Float = -1f,
    val todaySpend: Float = -1f,
)

internal object WelcomeFactsLoader {

    fun load(ctx: Context, now: LocalDateTime = LocalDateTime.now()): WelcomeFacts = runCatching {
        val today = now.toLocalDate()
        val cache = DataCache(ctx)
        val holidays = HolidayApi.peekCached(ctx)
        val schedule = ScheduleCache.readCurrentTermSchedule(cache)
        val courses: List<CourseItem> = schedule?.courses.orEmpty()
        val start = schedule?.start
        fun on(date: LocalDate): List<CourseItem> {
            if (start == null || holidays.containsKey(date)) return emptyList()
            val week = TermWeeks.weekOf(start, date)
            return courses.filter { it.dayOfWeek == date.dayOfWeek.value && it.isInWeek(week) }
                .distinctBy { Triple(it.courseName, it.startSection, it.location) }
                .sortedBy { it.startSection }
        }
        val nowTime = now.toLocalTime()
        val todayCourses = on(today)
        val upcoming = todayCourses.filter { c ->
            XjtuTime.getClassTime(c.startSection)?.start?.isAfter(nowTime) ?: false
        }
        val prefs = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(ctx)
        WelcomeFacts(
            chatter = ChatterFactsLoader.load(ctx, today),
            nextToday = upcoming.firstOrNull(),
            todayLeft = upcoming.size,
            tomorrow = on(today.plusDays(1)),
            exam = ExamCountdown.fromCache(ctx),
            balance = prefs.getFloat("card_balance_cache", -1f),
            todaySpend = prefs.getFloat("card_today_spend_cache", -1f),
        )
    }.getOrDefault(WelcomeFacts())
}

private class Suggestion(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val preview: String,
    val question: String,
    val warn: Boolean = false,
)

private val TintBlue = Color(0xFF3B82F6)
private val TintTeal = Color(0xFF14B8A6)
private val TintAmber = Color(0xFFF59E0B)
private val TintRose = Color(0xFFF43F5E)
private val TintViolet = Color(0xFF8B5CF6)
private val TintGreen = Color(0xFF22C55E)

private fun hhmm(t: LocalTime?): String = t?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""

/** 按时段排好的问题卡片，最多 4 张（两列排两行正好）。 */
private fun buildSuggestions(f: WelcomeFacts, now: LocalDateTime): List<Suggestion> {
    val hour = now.hour
    val cards = mutableMapOf<String, Suggestion>()

    f.nextToday?.let { c ->
        val t = hhmm(XjtuTime.getClassTime(c.startSection)?.start)
        cards["class"] = Suggestion(
            Icons.Outlined.EventNote, TintBlue, "下节课在哪",
            listOf(t, c.courseName, c.location).filter { it.isNotBlank() }.joinToString(" · "),
            "我下节课是什么，在哪上？",
        )
    }
    if (f.tomorrow.isNotEmpty()) {
        val first = f.tomorrow.first()
        cards["tomorrow"] = Suggestion(
            Icons.Outlined.EventNote, TintBlue, "明天有什么课",
            "${f.tomorrow.size} 节 · ${hhmm(XjtuTime.getClassTime(first.startSection)?.start)} ${first.courseName}",
            "明天有哪些课？几点开始？",
        )
    } else if (f.nextToday == null) {
        cards["tomorrow"] = Suggestion(
            Icons.Outlined.EventNote, TintBlue, "这周还有什么课",
            "按天列出剩下的课",
            "这周还剩哪些课？",
        )
    }
    f.exam?.let { e ->
        cards["exam"] = Suggestion(
            Icons.Outlined.Quiz, TintRose, "最近的考试",
            "${e.label} · ${e.exam.courseName}",
            "我最近有哪些考试？时间地点座位号都说一下",
            warn = e.daysLeft <= 3,
        )
    }
    if (f.balance >= 0f) {
        val spend = if (f.todaySpend > 0f) " · 今日 ¥${"%.2f".format(f.todaySpend)}" else ""
        cards["card"] = Suggestion(
            Icons.Outlined.AccountBalanceWallet, TintAmber, "饭卡还剩多少",
            "¥${"%.2f".format(f.balance)}$spend",
            "校园卡余额多少？最近都花在哪了？",
            warn = f.balance < 30f,
        )
    }
    cards["study"] = Suggestion(
        Icons.Outlined.MeetingRoom, TintTeal, "现在去哪自习",
        "空教室 + 图书馆座位一起看",
        "现在想找个地方自习，去哪合适？",
    )
    cards["meal"] = Suggestion(
        Icons.Outlined.Restaurant, TintGreen, "今天吃什么",
        "食堂、楼层和营业时间",
        "现在去哪个食堂吃饭比较好？",
    )
    cards["grade"] = Suggestion(
        Icons.Outlined.School, TintViolet, "最近出了什么成绩",
        "新出的分和 GPA",
        "最近出了哪些成绩？现在 GPA 多少？",
    )

    val order = when (hour) {
        in 5..10 -> listOf("class", "exam", "tomorrow", "study", "card", "grade")
        in 11..13 -> listOf("meal", "class", "card", "exam", "study", "tomorrow")
        in 14..16 -> listOf("class", "study", "exam", "card", "tomorrow", "grade")
        in 17..19 -> listOf("meal", "class", "tomorrow", "card", "exam", "study")
        else -> listOf("tomorrow", "exam", "grade", "study", "card", "meal")
    }
    // 三天内的考试和余额告急优先，不管什么时段
    val urgent = listOf("exam", "card").filter { cards[it]?.warn == true }
    return (urgent + order + cards.keys).distinct().mapNotNull { cards[it] }.take(4)
}

private class Capability(val icon: ImageVector, val label: String, val example: String)

private val CAPABILITIES = listOf(
    Capability(Icons.Outlined.Alarm, "设闹钟", "明早 7 点叫我起床"),
    Capability(Icons.Outlined.EditCalendar, "加进日程", "把这周五下午两点的实验加进日程"),
    Capability(Icons.Outlined.Functions, "讲题", "帮我讲讲这道题：∫ x·eˣ dx 怎么算？"),
    Capability(Icons.Outlined.PersonSearch, "查老师", "物理学院有哪些老师做凝聚态？"),
    Capability(Icons.Outlined.AutoStories, "搜资料", "资料站有没有大学物理的历年卷？"),
    Capability(Icons.Outlined.HelpOutline, "问 App", "这个 App 能把课表放到桌面吗？"),
)

/** 输入框的占位示范问题，每次打开换一句。 */
internal val COMPOSER_HINTS = listOf(
    "明天几点有课？",
    "帮我把周五的实验加进日程",
    "现在去哪自习？",
    "饭卡还剩多少？",
    "这周有考试吗？",
    "问一句…",
)

private class Playful(val icon: ImageVector, val text: String)

/** 「随便聊聊」：不查数据、纯陪聊的问题，点了直接发。 */
private val PLAYFUL = listOf(
    Playful(Icons.Outlined.Lightbulb, "讲个交大冷知识"),
    Playful(Icons.Outlined.FavoriteBorder, "给我打打气"),
    Playful(Icons.Outlined.WbSunny, "今天适合干点啥"),
    Playful(Icons.Outlined.Bedtime, "睡不着，陪我聊会儿"),
)

private val CapTints = listOf(TintAmber, TintBlue, TintViolet, TintTeal, TintGreen, TintRose)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentWelcome(
    greetingName: String,
    configured: Boolean,
    onAsk: (String) -> Unit,
    onFill: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    val context = LocalContext.current
    val now = remember { LocalDateTime.now() }
    val facts by produceState(WelcomeFacts(), context) {
        value = withContext(Dispatchers.IO) { WelcomeFactsLoader.load(context, now) }
    }
    val greeting = com.xjtu.toolbox.home.Greeting.of(now.toLocalTime())
    val primary = MiuixTheme.colorScheme.primary

    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // 极光：几团不同颜色的柔光叠在一起，静止不动（不给玻璃顶栏添负担）
                fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r),
                        radius = r,
                        center = Offset(cx, cy),
                    )
                }
                val w = size.width
                blob(primary.copy(alpha = 0.16f), w * 0.12f, 70.dp.toPx(), 150.dp.toPx())
                blob(TintTeal.copy(alpha = 0.10f), w * 0.92f, 40.dp.toPx(), 140.dp.toPx())
                blob(TintRose.copy(alpha = 0.07f), w * 0.7f, 260.dp.toPx(), 170.dp.toPx())
                blob(TintViolet.copy(alpha = 0.07f), w * 0.15f, 420.dp.toPx(), 160.dp.toPx())
            }
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        Hero(
            title = if (greetingName.isBlank()) greeting else "$greeting，$greetingName",
            facts = facts,
            now = now,
        )
        TodayStrip(facts)
        Spacer(Modifier.height(18.dp))
        if (!configured) {
            SetupCard(onOpenConfig)
        } else {
            SectionTitle("问我点什么")
            val cards = remember(facts) { buildSuggestions(facts, now) }
            cards.chunked(2).forEachIndexed { row, pair ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEachIndexed { col, s ->
                        SuggestionCard(s, index = row * 2 + col, modifier = Modifier.weight(1f)) { onAsk(s.question) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("我还能")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CAPABILITIES.forEachIndexed { i, cap ->
                CapabilityChip(cap, tint = CapTints[i % CapTints.size], index = i + 4) { onFill(cap.example) }
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle("随便聊聊")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PLAYFUL.forEachIndexed { i, p ->
                PlayfulChip(p, index = i + 10) { onAsk(p.text) }
            }
        }
    }
}

/** 挑一句闲话当问候的第二行；避开推广句和带动作的句子，也避开刚说过的那句。 */
private fun pickLine(facts: WelcomeFacts, now: LocalDateTime, avoid: String?): String? {
    repeat(6) {
        val l = ChatterPool.pick(now, emptyList(), facts.nextToday?.courseName, null, facts = facts.chatter)
            ?.takeIf { !it.id.startsWith("app_") && it.action == null }
            ?.text
        if (l != null && l != avoid) return l
    }
    return null
}

@Composable
private fun Hero(title: String, facts: WelcomeFacts, now: LocalDateTime) {
    val primary = MiuixTheme.colorScheme.primary
    val look = pidaiNavAppearance()
    val host = ProactiveBubbleHost
    var line by remember(facts) {
        mutableStateOf(host.heroLine ?: pickLine(facts, now, null) ?: "课表、考试、空教室、饭卡，直接问我就行")
    }
    // 进来先眨个眼打招呼，之后回到待命；点它一下换一句话、翻个跟头
    var beat by remember { mutableStateOf(PidaiBeat.IDLE) }
    var pokes by remember { mutableStateOf(0) }
    LaunchedEffect(pokes) {
        delay(1600)
        beat = PidaiBeat.REST
    }
    // 已经在屁岱页又点了底栏屁岱：那句闲话由这里说，不从底栏冒泡
    val seenPokes = remember { intArrayOf(host.heroPokes) }
    LaunchedEffect(host.heroPokes) {
        if (host.heroPokes == seenPokes[0]) return@LaunchedEffect
        seenPokes[0] = host.heroPokes
        host.heroLine?.let { line = it }
        beat = PidaiBeat.TAP
        pokes++
    }
    // 轻轻上下浮动：约 30 帧/秒推进相位，页面不可见就停（和底栏屁岱同一档开销）
    val visible = com.xjtu.toolbox.ui.components.LocalPageVisible.current
    val floatPhase = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (visible) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                floatPhase.floatValue = ((System.nanoTime() - start) / 1_000_000_000.0 * 1.6).toFloat()
                delay(33)
            }
        }
    }
    // 打字机：新的一句逐字出现
    var shown by remember(line) { mutableStateOf(0) }
    LaunchedEffect(line) {
        shown = 0
        while (shown < line.length) {
            delay(38)
            shown++
        }
    }

    val enter = rememberEnter(0)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 12.dp.toPx()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .clickable {
                    pokes++
                    beat = PidaiBeat.TAP
                    pickLine(facts, LocalDateTime.now(), line)?.let { line = it }
                },
            contentAlignment = Alignment.Center,
        ) {
            BloubBotIcon(
                beat = beat,
                ink = look.ink,
                paper = MiuixTheme.colorScheme.surface,
                shape = look.shape,
                skin = look.skin,
                modifier = Modifier
                    .size(72.dp)
                    .graphicsLayer { translationY = -4.dp.toPx() + kotlin.math.sin(floatPhase.floatValue) * 4.dp.toPx() },
            )
        }
        Spacer(Modifier.width(12.dp))
        // 对话气泡：左边一个小尖角指向屁岱
        Column(
            Modifier
                .weight(1f)
                .drawBehind {
                    val r = 7.dp.toPx()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height / 2f - r)
                        lineTo(-r, size.height / 2f)
                        lineTo(0f, size.height / 2f + r)
                        close()
                    }
                    drawPath(path, primary.copy(alpha = 0.12f))
                }
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(primary.copy(alpha = 0.12f), TintViolet.copy(alpha = 0.08f)),
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box {
                // 先用整句占住高度，逐字出现时气泡不跟着一跳一跳地长
                Text(line, style = MiuixTheme.textStyles.body2, color = Color.Transparent)
                Text(
                    line.take(shown),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 今日速览：一排小胶囊，把课、考试、余额、假期这些「此刻最相关的数」摆出来。读不到的不出。 */
@Composable
private fun TodayStrip(f: WelcomeFacts) {
    val items = buildList {
        when {
            f.chatter.todayHoliday != null -> add(Triple(Icons.Outlined.Celebration, "${f.chatter.todayHoliday}", TintRose))
            f.todayLeft > 0 -> add(Triple(Icons.Outlined.EventNote, "今天还有 ${f.todayLeft} 节", TintBlue))
            f.tomorrow.isNotEmpty() -> add(Triple(Icons.Outlined.EventNote, "明天 ${f.tomorrow.size} 节", TintBlue))
        }
        f.exam?.let { add(Triple(Icons.Outlined.Quiz, "${it.exam.courseName} ${it.label}", if (it.daysLeft <= 3) TintRose else TintViolet)) }
        f.chatter.nextHoliday?.let { (name, days) -> add(Triple(Icons.Outlined.BeachAccess, "距$name $days 天", TintTeal)) }
    }
    // 只有一颗时孤零零挂着反而像多余的装饰，至少两项才出
    if (items.size < 2) return
    val enter = rememberEnter(1)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .graphicsLayer { alpha = enter.value }
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (icon, text, tint) ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

/** 进场动画：全 App 共用的那一份（见 ui/components/Motion.kt），按序号错开依次浮上来，只播一次。 */
@Composable
private fun rememberEnter(index: Int) = com.xjtu.toolbox.ui.components.rememberEnterProgress(index)

@Composable
private fun SuggestionCard(s: Suggestion, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val enter = rememberEnter(index + 2)
    val accent = if (s.warn) MiuixTheme.colorScheme.error else s.tint
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 16.dp.toPx()
            }
            .pressScale(source, pressed = 0.95f)
            .heightIn(min = 116.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.15f), com.xjtu.toolbox.ui.components.AppCardColor),
                    start = Offset.Zero,
                    end = Offset(600f, 600f),
                )
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        // 右下角一枚放大、很淡的同款图标当水印
        Icon(
            s.icon,
            contentDescription = null,
            tint = accent.copy(alpha = 0.10f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 4.dp)
                .size(64.dp)
                .graphicsLayer { rotationZ = -12f },
        )
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(s.icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                s.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                s.preview,
                style = MiuixTheme.textStyles.footnote1,
                color = if (s.warn) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CapabilityChip(cap: Capability, tint: Color, index: Int, onClick: () -> Unit) {
    val enter = rememberEnter(index)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .graphicsLayer { alpha = enter.value }
            .pressScale(source, pressed = 0.95f)
            .clip(RoundedCornerShape(14.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(cap.icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(cap.label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlayfulChip(p: Playful, index: Int, onClick: () -> Unit) {
    val enter = rememberEnter(index)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val primary = MiuixTheme.colorScheme.primary
    Row(
        Modifier
            .graphicsLayer { alpha = enter.value }
            .pressScale(source, pressed = 0.95f)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(primary.copy(alpha = 0.10f), TintRose.copy(alpha = 0.08f))))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(p.icon, contentDescription = null, tint = primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(p.text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}

/** 没配模型：一张主色引导卡 + 一小段示范对话，先让人知道配好之后能干什么。 */
@Composable
private fun SetupCard(onOpenConfig: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    val enter = rememberEnter(2)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 16.dp.toPx()
            }
            .pressScale(source, pressed = 0.95f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(primary, TintViolet.copy(alpha = 0.85f))))
            .drawBehind {
                // 右上角两圈半透明的光环当装饰
                drawCircle(Color.White.copy(alpha = 0.10f), radius = 90.dp.toPx(), center = Offset(size.width, 0f))
                drawCircle(Color.White.copy(alpha = 0.07f), radius = 140.dp.toPx(), center = Offset(size.width, 0f))
            }
            .clickable(interactionSource = source, indication = null, onClick = onOpenConfig)
            .padding(18.dp),
    ) {
        Text("先接一个模型", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            "填好 API Key 就能开聊，推荐 DeepSeek",
            style = MiuixTheme.textStyles.body2,
            color = Color.White.copy(alpha = 0.88f),
        )
        Spacer(Modifier.height(14.dp))
        DemoBubble("明早几点有课？", mine = true)
        Spacer(Modifier.height(6.dp))
        DemoBubble("明早 8:00 有课，在主楼。要我 7 点叫你起床吗？", mine = false)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("去配置", fontWeight = FontWeight.SemiBold, color = primary, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DemoBubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            style = MiuixTheme.textStyles.footnote1,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = if (mine) 0.26f else 0.16f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
