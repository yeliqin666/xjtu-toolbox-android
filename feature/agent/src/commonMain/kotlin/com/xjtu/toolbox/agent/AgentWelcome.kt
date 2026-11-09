package com.xjtu.toolbox.agent

import com.xjtu.toolbox.ui.components.pressScale
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import com.xjtu.toolbox.util.formatFixed
import com.xjtu.toolbox.util.formatTwoDigits
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.util.nowOnCampus

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
/** 首屏要用的本地数据快照（`:app` 从 `DataCache` + 校园卡偏好 + 聊天事实里读，见 `AndroidAgentHost`）。 */
data class WelcomeFacts(
    val chatter: ChatterFacts = ChatterFacts(),
    /** 今天还没开始的下一节课。 */
    val nextToday: CourseItem? = null,
    val todayLeft: Int = 0,
    val tomorrow: List<CourseItem> = emptyList(),
    val exam: ExamCountdown.Next? = null,
    val balance: Float = -1f,
    val todaySpend: Float = -1f,
)


private val TintBlue = Color(0xFF3B82F6)
private val TintTeal = Color(0xFF14B8A6)
private val TintAmber = Color(0xFFF59E0B)
private val TintRose = Color(0xFFF43F5E)
val TintViolet = Color(0xFF8B5CF6)
private val TintGreen = Color(0xFF22C55E)

private fun hhmm(t: LocalTime?): String =
    t?.let { "${formatTwoDigits(it.hour)}:${formatTwoDigits(it.minute)}" } ?: ""

/** 输入框的占位示范问题，每次打开换一句。 */
internal val COMPOSER_HINTS = listOf(
    "明天几点有课？",
    "帮我把周五的实验加进日程",
    "现在去哪自习？",
    "饭卡还剩多少？",
    "这周有考试吗？",
    "问一句…",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentWelcome(
    /** 端装配与端上资源（见 `:core` 的 [AgentHost]）：首屏那张快照由它给。 */
    host: AgentHost,
    greetingName: String,
    configured: Boolean,
    /** 最近一次对话；没有就不显示那一条。 */
    recent: AgentSession?,
    onOpenRecent: (String) -> Unit,
    onAsk: (String) -> Unit,
    onFill: (String) -> Unit,
    onOpenConfig: () -> Unit,
    /** 首屏那个「屁岱形象 + 问候」的槽位。 */
    hero: @Composable (title: String, facts: WelcomeFacts, now: LocalDateTime) -> Unit =
        { title, f, n -> agentWelcomePlainHero(title, f, n) },
) {
    val now = remember { nowOnCampus() }
    val facts by produceState(WelcomeFacts(), host) {
        value = withContext(host.io) { host.welcomeFacts(now) }
    }
    val greeting = com.xjtu.toolbox.home.Greeting.of(now.time)

    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        hero(
            if (greetingName.isBlank()) greeting else "$greeting，$greetingName",
            facts,
            now,
        )
        Spacer(Modifier.height(18.dp))
        if (recent != null) {
            RecentChatRow(recent) { onOpenRecent(recent.id) }
            Spacer(Modifier.height(16.dp))
        }
        if (!configured) {
            SetupCard(onOpenConfig)
        } else {
            val brief = remember(facts) { buildBrief(facts, now) }
            if (brief.isNotEmpty()) {
                SectionTitle("此刻", Icons.Outlined.Schedule)
                BriefCard(brief, onAsk)
                Spacer(Modifier.height(22.dp))
            }
        }
        if (configured) {
            SectionTitle("一句话，办成一串事", Icons.Outlined.AutoAwesome)
            ChainShowcase(host.honored, onAsk)
            Spacer(Modifier.height(22.dp))
        }
        SectionTitle("我能做什么", Icons.Outlined.GridView)
        CapabilityIndex(host.honored, onFill)
    }
}

/** 首屏最上面的一条：接着上次的对话聊。 */
@Composable
private fun RecentChatRow(session: AgentSession, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(source, pressed = 0.98f)
            .clip(RoundedCornerShape(20.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Outlined.History, primary, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { "上次的对话" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("接着聊 · ${formatSessionTime(session.updatedAt)}", fontSize = 12.sp, color = muted, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = muted.copy(alpha = 0.5f), modifier = Modifier.size(15.dp))
    }
}

/** 浅色底的小图标块，首屏各处统一用它。 */
@Composable
private fun IconTile(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.34f)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** 一件要串好几个工具才办得成的事：[steps] 是会用到的那几步，卡上照实写出来。 */
private class Chain(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val steps: List<String>,
    val question: String,
    val needs: AgentCapability? = null,
)

private val CHAINS = listOf(
    Chain(
        Icons.Outlined.EditCalendar, TintBlue,
        "排一份期末复习计划", listOf("考试安排", "课表空档", "写进日程"),
        "看看我的考试安排和这几周的课表空档，排一份复习计划，确认后写进日程",
        AgentCapability.SCHEDULE_EVENT_WRITE,
    ),
    Chain(
        Icons.Outlined.MeetingRoom, TintTeal,
        "下节课前找个地方自习", listOf("下节课地点", "附近空教室", "步行时间"),
        "离下节课还有一会儿，在下节课的楼附近找个现在空着的教室，算好走过去要几分钟",
    ),
    Chain(
        Icons.Outlined.School, TintViolet,
        "找出拉低 GPA 的课", listOf("全部成绩", "学分加权", "对比分析"),
        "把我所有学期的成绩拉出来，算一下哪几门课最拖 GPA，下学期该注意什么",
    ),
    Chain(
        Icons.Outlined.AutoStories, TintAmber,
        "读课件出几道自测题", listOf("思源课件", "读 PDF", "出题讲解"),
        "读一下思源上最新那份课件，总结重点，再出 5 道自测题，答完帮我批改",
    ),
    Chain(
        Icons.Outlined.Alarm, TintRose,
        "每晚帮我盯着通知", listOf("定时任务", "查通知", "推送提醒"),
        "每天晚上 9 点帮我看看教务处和学院有没有新的重要通知，有就推送给我",
    ),
)

/** 横向一排卡：标题 + 用到的几步（小字串成一条链），点了直接发出去。 */
@Composable
private fun ChainShowcase(honored: Set<AgentCapability>, onAsk: (String) -> Unit) {
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val chains = CHAINS.filter { it.needs == null || it.needs in honored }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        chains.forEachIndexed { i, c ->
            val enter = rememberEnter(i + 2)
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Column(
                Modifier
                    .graphicsLayer {
                        alpha = enter.value
                        translationY = (1f - enter.value) * 12.dp.toPx()
                    }
                    .pressScale(source, pressed = 0.96f)
                    .width(176.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(com.xjtu.toolbox.ui.components.AppCardColor)
                    // 右上角一团很淡的同色光，每张卡有自己的颜色，又不抢字
                    .drawBehind {
                        drawCircle(c.tint.copy(alpha = 0.07f), radius = 62.dp.toPx(), center = Offset(size.width, 0f))
                    }
                    .clickable(interactionSource = source, indication = null) { onAsk(c.question) }
                    .padding(14.dp),
            ) {
                IconTile(c.icon, c.tint, 34.dp)
                Spacer(Modifier.height(12.dp))
                Text(c.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 2, minLines = 2, lineHeight = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    c.steps.joinToString(" → "),
                    fontSize = 11.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 「此刻」里的一行：标签、真实数据、点了发出去的问题。 */
private class BriefLine(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val question: String,
    val warn: Boolean = false,
)

/**
 * 按本地缓存拼出此刻最相关的几行，读不到的不出。时段决定最后一行给「吃饭」还是「自习」，
 * 三天内的考试和余额告急排最前。
 */
private fun buildBrief(f: WelcomeFacts, now: LocalDateTime): List<BriefLine> {
    val lines = mutableListOf<BriefLine>()
    when {
        f.chatter.todayHoliday != null -> lines += BriefLine(
            Icons.Outlined.Celebration, "今天", "${f.chatter.todayHoliday}，没课", "放假这几天学校有什么安排？",
        )
        f.nextToday != null -> {
            val c = f.nextToday
            val t = hhmm(XjtuTime.getClassTime(c.startSection)?.start)
            lines += BriefLine(
                Icons.Outlined.EventNote, "下节课",
                listOf(t, c.courseName, c.location).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (f.todayLeft > 1) "（今天还有 ${f.todayLeft} 节）" else "",
                "我下节课是什么，在哪上？怎么过去最快？",
            )
        }
        f.tomorrow.isNotEmpty() -> {
            val first = f.tomorrow.first()
            lines += BriefLine(
                Icons.Outlined.EventNote, "明天",
                "${f.tomorrow.size} 节 · ${hhmm(XjtuTime.getClassTime(first.startSection)?.start)} ${first.courseName}",
                "明天有哪些课？几点开始，要不要定个闹钟？",
            )
        }
    }
    f.exam?.let { e ->
        lines += BriefLine(
            Icons.Outlined.Quiz, "考试", "${e.exam.courseName} ${e.label}",
            "我最近有哪些考试？时间地点座位号都说一下，再帮我排个复习计划",
            warn = e.daysLeft <= 3,
        )
    }
    if (f.balance >= 0f) {
        val spend = if (f.todaySpend > 0f) " · 今天花了 ¥${formatFixed(f.todaySpend, 2)}" else ""
        lines += BriefLine(
            Icons.Outlined.AccountBalanceWallet, "饭卡", "¥${formatFixed(f.balance, 2)}$spend",
            "校园卡余额多少？最近一周都花在哪了？",
            warn = f.balance < 30f,
        )
    }
    f.chatter.nextHoliday?.let { (name, days) ->
        lines += BriefLine(Icons.Outlined.BeachAccess, "放假", "距$name $days 天", "最近的假期怎么放？要调休吗？")
    }
    lines += if (now.hour in 10..13 || now.hour in 16..19) {
        BriefLine(Icons.Outlined.Restaurant, "饭点", "附近哪个食堂开着", "现在去哪个食堂吃饭比较好？")
    } else {
        BriefLine(Icons.Outlined.MeetingRoom, "自习", "空教室和图书馆座位一起看", "现在想找个地方自习，去哪合适？")
    }
    return lines.sortedByDescending { it.warn }
}

/** 「此刻」：一张纸面卡，几行真实数据，点一行就把对应的问题发出去。 */
@Composable
private fun BriefCard(lines: List<BriefLine>, onAsk: (String) -> Unit) {
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val rule = ink.copy(alpha = 0.07f)
    val enter = rememberEnter(1)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 12.dp.toPx()
            }
            .clip(RoundedCornerShape(22.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor),
    ) {
        lines.forEachIndexed { i, line ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.6.dp).background(rule))
            val accent = if (line.warn) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = source, indication = top.yukonga.miuix.kmp.utils.SinkFeedback()) { onAsk(line.question) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(line.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(line.label, fontSize = 13.sp, color = muted, modifier = Modifier.width(44.dp))
                Text(
                    line.value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (line.warn) accent else ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "问小岱",
                    tint = muted.copy(alpha = 0.5f), modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 能力目录里的一项：名字和点了填进输入框的示范问题；[needs] 非空时这一端得声明了那项能力才列出。 */
private class Ability(val label: String, val example: String, val needs: AgentCapability? = null)

private class AbilityGroup(val icon: ImageVector, val tint: Color, val title: String, val hint: String, val items: List<Ability>)

/** 小岱的工具全貌，按「查、办、懂、找」四类列出（与工具表一一对应，加了工具就在这里登记）。 */
private val ABILITIES = listOf(
    AbilityGroup(
        Icons.Outlined.Search, TintBlue, "查", "学校系统里的数",
        listOf(
            Ability("课表", "这周还剩哪些课？"),
            Ability("考试", "我最近有哪些考试？座位号多少？"),
            Ability("成绩", "最近出了哪些成绩？GPA 多少？"),
            Ability("考勤", "这学期我有没有缺勤？"),
            Ability("校园卡", "最近一周饭卡都花在哪了？"),
            Ability("空教室", "主楼现在哪些教室空着？"),
            Ability("图书馆", "兴庆图书馆现在还有空座吗？"),
            Ability("通知", "最近有没有推免相关的通知？"),
            Ability("思源作业", "思源上还有哪些作业没交？"),
            Ability("体测", "我的体测成绩怎么样？"),
        ),
    ),
    AbilityGroup(
        Icons.Outlined.TaskAlt, TintGreen, "办", "替你动手",
        listOf(
            Ability("定闹钟", "明早 7 点叫我起床", AgentCapability.ALARM),
            Ability("加日程", "把这周五下午两点的实验加进日程", AgentCapability.SCHEDULE_EVENT_WRITE),
            Ability("记待办", "记一下：周三前交实验报告"),
            Ability("定时任务", "每天晚上 9 点帮我看看有没有新通知"),
            Ability("存文件", "把大学物理的历年卷存到手机里"),
        ),
    ),
    AbilityGroup(
        Icons.Outlined.Lightbulb, TintAmber, "懂", "讲给你听",
        listOf(
            Ability("讲题", "帮我讲讲这道题：∫ x·eˣ dx 怎么算？"),
            Ability("看图", "我拍了一道题，帮我看看", AgentCapability.IMAGE_INPUT),
            Ability("读课件", "帮我总结一下思源上最新那份课件"),
            Ability("联网搜", "搜一下今年数模竞赛什么时候报名"),
            Ability("读公众号", "仲英学辅最近发了什么？"),
        ),
    ),
    AbilityGroup(
        Icons.Outlined.Place, TintTeal, "找", "校园里的人和地方",
        listOf(
            Ability("地图导航", "从西 2 楼走到梧桐苑要多久？"),
            Ability("食堂", "兴庆校区哪些食堂晚上还开？"),
            Ability("老师", "物理学院有哪些老师做凝聚态？"),
            Ability("黄页", "教务处电话多少？"),
            Ability("问 App", "这个 App 能把课表放到桌面吗？"),
        ),
    ),
)

/** 能力目录：左边一个字的分类，右边一排可点的名字，点了把示范问题填进输入框（不直接发）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityIndex(honored: Set<AgentCapability>, onFill: (String) -> Unit) {
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val enter = rememberEnter(2)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 12.dp.toPx()
            }
            .clip(RoundedCornerShape(22.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        ABILITIES.forEachIndexed { gi, group ->
            val items = group.items.filter { it.needs == null || it.needs in honored }
            if (gi > 0) Box(Modifier.fillMaxWidth().height(0.6.dp).background(ink.copy(alpha = 0.07f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.width(52.dp)) {
                    IconTile(group.icon, group.tint, 30.dp)
                    Spacer(Modifier.height(5.dp))
                    Text(group.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    Text(group.hint, fontSize = 10.sp, lineHeight = 13.sp, color = muted, maxLines = 2, modifier = Modifier.padding(end = 6.dp))
                }
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items.forEach { a ->
                        Text(
                            a.label,
                            fontSize = 13.sp,
                            color = ink,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(ink.copy(alpha = 0.05f))
                                .clickable { onFill(a.example) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 没有屁岱形象的端（桌面 / Web）的问候头：同一个标题 + 同一句闲话，只是没有那颗可以戳的形象。 */
@Composable
fun agentWelcomePlainHero(title: String, facts: WelcomeFacts, now: LocalDateTime) {
    val primary = MiuixTheme.colorScheme.primary
    val line = remember(facts) { plainHeroLine(facts, now) }
    Column(
        Modifier
            .fillMaxWidth()
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
        Text(line, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 问候头第二行那句闲话：`:app` 那份 `pickLine` 的简化版（没有「避开刚说过的那句」那一层）。 */
private fun plainHeroLine(facts: WelcomeFacts, now: LocalDateTime): String =
    ChatterPool.pick(now, emptyList(), facts.nextToday?.courseName, null, facts = facts.chatter)
        ?.takeIf { !it.id.startsWith("app_") }
        ?.text
        ?: "课表、考试、空教室、饭卡，直接问我就行"

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold, color = muted)
    }
}

/** 进场动画：全 App 共用的那一份（见 ui/components/Motion.kt），按序号错开依次浮上来，只播一次。 */
@Composable
fun rememberEnter(index: Int) = com.xjtu.toolbox.ui.components.rememberEnterProgress(index)

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
            .background(primary)
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
