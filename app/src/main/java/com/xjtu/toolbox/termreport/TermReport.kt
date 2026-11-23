package com.xjtu.toolbox.termreport

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentUsage
import com.xjtu.toolbox.agent.agentToolLabel
import com.xjtu.toolbox.attendance.TermCodeMapper
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.card.MealReport
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.card.appCampusCardStore
import com.xjtu.toolbox.card.categorizeSpending
import com.xjtu.toolbox.card.categoryOf
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.inbox.SelfTodo
import com.xjtu.toolbox.inbox.SelfTodoStore
import com.xjtu.toolbox.jwapp.ScoreItem
import com.xjtu.toolbox.jwapp.appJwappScoreCache
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.score.ScoreCalculator
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 学期总结（#282）：只读本机已有的缓存与计数，不新登录、不上传。
 * 某一块读不到（没打开过那个页面、缓存过期）就是 null，页面跳过那一页并提示去刷新。
 */
data class TermReport(
    val termName: String,
    val start: LocalDate,
    val end: LocalDate,
    val today: LocalDate,
    val courses: CourseStats?,
    val grades: GradeStats?,
    val todos: TodoStats?,
    val card: CardStats?,
    val fitness: String?,
    val games: GameStats?,
    val agent: AgentStats?,
) {
    val ended get() = today > end
    val until get() = minOf(today, end)
    val days get() = (until.toEpochDays() - start.toEpochDays() + 1).toInt().coerceAtLeast(0)

    val missing: List<String>
        get() = listOfNotNull(
            "课表".takeIf { courses == null }, "成绩".takeIf { grades == null }, "校园卡".takeIf { card == null },
            "体测".takeIf { fitness == null },
        )
}

data class CourseStats(
    val courses: Int,
    val totalSections: Int,
    val weeklyAvg: Int,
    /** 周一到周日整学期各上了多少节。 */
    val perWeekday: List<Int>,
    /** 第 1 节开始的课上了几次。 */
    val earlyClasses: Int,
    val customEvents: Int,
)

data class GradeStats(
    val count: Int,
    val credits: Double,
    val gpa: Double,
    val average: Double,
    val best: ScoreItem?,
    val failed: Int,
    /** 90+ / 80–89 / 70–79 / 60–69 / 60 以下 的门数，只算百分制。 */
    val bands: List<Pair<String, Int>>,
    val previousGpa: Double?,
)

data class TodoStats(val created: Int, val done: Int, val overdue: Int) {
    val rate get() = if (created == 0) 0f else done.toFloat() / created
}

data class CardStats(
    /** 本机流水最早覆盖到哪天（可能晚于开学）。 */
    val from: LocalDate,
    val spend: Double,
    val monthly: List<Pair<String, Double>>,
    val categories: List<Pair<String, Double>>,
    val meal: MealReport?,
    val priciest: Transaction?,
)

data class GameStats(val minutes: Long, val days: Int, val favorite: String?, val longestDay: Pair<LocalDate, Long>?)

data class AgentStats(
    val messages: Long,
    val chats: Long,
    val tools: Long,
    val topTools: List<Pair<String, Long>>,
    val days: Int,
    val peakHour: Int?,
    /** 23 点到凌晨 4 点发的消息数。 */
    val lateNight: Long,
)

/** 取最近一个已开学的学期，逐块读缓存。只有校历要联网（公开接口）。 */
suspend fun loadTermReport(context: Context): TermReport = withContext(Dispatchers.IO) {
    val today = todayOnCampus()
    val term = SchoolCalendarApi().terms().sortedBy { it.startDate }.lastOrNull { it.startDate <= today }
        ?: error("校历里还没有已开学的学期")
    val code = TermCodeMapper.termCodeOf(term.yearName, term.termName)
    val until = minOf(today, term.endDate)
    val account = AccountContext.activeAccountId
    TermReport(
        termName = term.termName,
        start = term.startDate,
        end = term.endDate,
        today = today,
        courses = runCatching { courseStats(context, account, code, term.totalWeeks) }.getOrNull(),
        grades = runCatching { gradeStats(context, account, code) }.getOrNull(),
        todos = runCatching { todoStats(term.startDate, until) }.getOrNull(),
        card = runCatching { cardStats(context, account, term.startDate, until) }.getOrNull(),
        fitness = runCatching { HomeStats.pushed(context, AppRoute.Fitness) }.getOrNull()
            ?.let { listOfNotNull(it.value.ifBlank { null }, it.detail?.ifBlank { null }).joinToString(" · ") }?.ifBlank { null },
        games = gameStats(term.startDate, until),
        agent = agentStats(term.startDate, until),
    )
}

private suspend fun courseStats(context: Context, account: String?, code: String, weeks: Int): CourseStats? {
    val items = ScheduleCache.readCourses(DataCache(context, account), code)?.takeIf { it.isNotEmpty() } ?: return null
    val perWeekday = IntArray(7)
    var total = 0
    var early = 0
    for (c in items) {
        val times = c.getWeeks().count { it <= weeks }
        val sections = (c.endSection - c.startSection + 1).coerceAtLeast(1)
        total += times * sections
        if (c.dayOfWeek in 1..7) perWeekday[c.dayOfWeek - 1] += times * sections
        if (c.startSection == 1) early += times
    }
    val customs = runCatching {
        AppDatabase.getInstance(context).customCourseDao().getByTerm(account.orEmpty(), code).size
    }.getOrDefault(0)
    return CourseStats(
        courses = items.map { it.courseCode.ifBlank { it.courseName } }.distinct().size,
        totalSections = total,
        weeklyAvg = total / weeks.coerceAtLeast(1),
        perWeekday = perWeekday.toList(),
        earlyClasses = early,
        customEvents = customs,
    )
}

private suspend fun gradeStats(context: Context, account: String?, code: String): GradeStats? {
    val terms = appJwappScoreCache(context, account).read(Long.MAX_VALUE) ?: return null
    val list = terms.firstOrNull { it.termCode == code }?.scoreList?.takeIf { it.isNotEmpty() } ?: return null
    val info = ScoreCalculator.calculateGpaForCourses(list)
    val previous = terms.filter { it.termCode < code && it.scoreList.isNotEmpty() }.maxByOrNull { it.termCode }
        ?.let { ScoreCalculator.calculateGpaForCourses(it.scoreList).gpa }
    val numeric = list.mapNotNull { it.scoreValue }
    val bands = listOf(
        "90+" to numeric.count { it >= 90 },
        "80–89" to numeric.count { it >= 80 && it < 90 },
        "70–79" to numeric.count { it >= 70 && it < 80 },
        "60–69" to numeric.count { it >= 60 && it < 70 },
        "60 以下" to numeric.count { it < 60 },
    )
    return GradeStats(
        count = list.size,
        credits = list.sumOf { it.coursePoint },
        gpa = info.gpa,
        average = info.averageScore,
        best = list.filter { it.scoreValue != null }.maxByOrNull { it.scoreValue!! },
        failed = list.count { !ScoreCalculator.isPassed(it) },
        bands = bands,
        previousGpa = previous,
    )
}

private fun todoStats(start: LocalDate, until: LocalDate): TodoStats? {
    fun due(t: SelfTodo) = t.dueDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    fun created(t: SelfTodo) = t.createdTime.takeIf { it > 0 }
        ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(CampusTimeZone).date }
    val inTerm = SelfTodoStore.all().filter { t -> (created(t) ?: due(t))?.let { it in start..until } == true }
    if (inTerm.isEmpty()) return null
    return TodoStats(
        created = inTerm.size,
        done = inTerm.count { it.done },
        overdue = inTerm.count { t -> !t.done && due(t)?.let { it < until } == true },
    )
}

private suspend fun cardStats(context: Context, account: String?, start: LocalDate, until: LocalDate): CardStats? {
    val snapshot = appCampusCardStore(context).load(account) ?: return null
    fun dateOf(tx: Transaction) = runCatching { LocalDate.parse(tx.time.take(10)) }.getOrNull()
    val txs = snapshot.transactions.filter { tx -> dateOf(tx)?.let { it in start..until } == true }
    val spent = txs.filter { it.amount < 0 }
    if (spent.isEmpty()) return null
    return CardStats(
        from = txs.mapNotNull(::dateOf).min(),
        spend = spent.sumOf { -it.amount },
        monthly = spent.groupBy { it.time.take(7) }.map { (m, l) -> m to l.sumOf { -it.amount } }.sortedBy { it.first },
        categories = categorizeSpending(txs).toList().take(5),
        meal = MealReport.build(txs) { categoryOf(it) == "餐饮" },
        priciest = spent.minByOrNull { it.amount },
    )
}

private fun gameStats(start: LocalDate, until: LocalDate): GameStats? {
    val rows = GamePlaytime.between(start, until).takeIf { it.isNotEmpty() } ?: return null
    val byDay = rows.groupBy { it.first }.mapValues { (_, l) -> l.sumOf { it.third } }
    return GameStats(
        minutes = rows.sumOf { it.third } / 60,
        days = byDay.size,
        favorite = rows.groupBy { it.second }.maxByOrNull { (_, l) -> l.sumOf { it.third } }?.key,
        longestDay = byDay.maxByOrNull { it.value }?.toPair(),
    )
}

private fun agentStats(start: LocalDate, until: LocalDate): AgentStats? {
    val rows = AgentUsage.between(start, until)
    fun grouped(prefix: String) = rows.filter { it.second.startsWith(prefix) }
        .groupBy { it.second.removePrefix(prefix) }.mapValues { (_, l) -> l.sumOf { it.third } }
    val messages = rows.filter { it.second == "msg" }.sumOf { it.third }
    if (messages == 0L) return null
    val tools = grouped("tool:")
    val hours = grouped("hour:").mapKeys { it.key.toIntOrNull() ?: -1 }
    return AgentStats(
        messages = messages,
        chats = rows.filter { it.second == "chat" }.sumOf { it.third },
        tools = tools.values.sum(),
        topTools = tools.entries.sortedByDescending { it.value }.take(3).map { agentToolLabel(it.key).removeSuffix("…") to it.value },
        days = rows.filter { it.second == "msg" }.map { it.first }.distinct().size,
        peakHour = hours.maxByOrNull { it.value }?.key?.takeIf { it >= 0 },
        lateNight = hours.filterKeys { it >= 23 || it in 0..3 }.values.sum(),
    )
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun weekdayName(index: Int) = WEEKDAYS.getOrElse(index) { "" }

/** 结尾页的称号：按最突出的一项挑。 */
fun TermReport.title(): String = when {
    (grades?.gpa ?: 0.0) >= 3.8 -> "学霸本霸"
    (courses?.earlyClasses ?: 0) >= 40 -> "早八战士"
    (card?.meal?.lateNights ?: 0) >= 15 -> "夜宵常客"
    (agent?.messages ?: 0) >= 200 -> "小岱的老朋友"
    (todos?.rate ?: 0f) >= 0.85f && (todos?.created ?: 0) >= 10 -> "执行力担当"
    (games?.minutes ?: 0) >= 600 -> "摸鱼大师"
    else -> "稳稳当当"
}

/** 学期之最：只挑算得出来的，最多 6 条。 */
fun TermReport.highlights(): List<String> = buildList {
    courses?.let { c ->
        if (c.earlyClasses > 0) add("早八上了 ${c.earlyClasses} 次")
        c.perWeekday.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { add("${weekdayName(it.index)}的课最满") }
    }
    grades?.best?.let { add("最高分：${it.courseName} ${it.score}") }
    card?.meal?.latest?.let { add("最晚的一顿：${it.time.drop(11).take(5)} · ${it.displayMerchant}") }
    card?.meal?.canteen?.let { add("最常去：${it.first}（${it.second} 次）") }
    agent?.peakHour?.let { add("最常在 $it 点找小岱") }
    games?.favorite?.let { add("最爱玩：$it") }
    todos?.takeIf { it.created >= 5 }?.let { add("待办完成率 ${(it.rate * 100).toInt()}%") }
}.take(6)

/** 给小岱点评用的摘要。 */
fun TermReport.agentSnapshot(): String = buildString {
    append("用户的「$termName」学期总结（$start 至 $until，本机统计，部分数据可能不全）：\n")
    courses?.let { append("课程 ${it.courses} 门，共 ${it.totalSections} 节，周均 ${it.weeklyAvg} 节，早八 ${it.earlyClasses} 次。\n") }
    grades?.let { append("成绩 ${it.count} 门，学分 %.1f，GPA %.2f，均分 %.1f，不及格 ${it.failed} 门%s。\n".format(it.credits, it.gpa, it.average, it.previousGpa?.let { p -> "，上学期 GPA %.2f".format(p) }.orEmpty())) }
    todos?.let { append("自建待办 ${it.created} 条，完成 ${it.done} 条，逾期未完成 ${it.overdue} 条。\n") }
    card?.let { c ->
        append("校园卡自 ${c.from} 起支出 %.0f 元".format(c.spend))
        c.meal?.let { append("，吃了 ${it.meals} 顿，夜宵 ${it.lateNights} 次") }
        append("。\n")
    }
    fitness?.let { append("体测：$it。\n") }
    games?.let { append("小游戏玩了 ${it.minutes} 分钟（${it.days} 天）。\n") }
    agent?.let { append("和小岱聊了 ${it.messages} 条消息、${it.chats} 个对话。\n") }
    append("请先肯定做得好的地方，再给两三条具体、可执行的建议，语气轻松，不要说教，不要渲染焦虑。")
}
