package com.xjtu.toolbox.termreport

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentUsage
import com.xjtu.toolbox.agent.agentToolLabel
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.attendance.TermCodeMapper
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.card.MealReport
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.card.appCampusCardStore
import com.xjtu.toolbox.card.categorizeSpending
import com.xjtu.toolbox.card.categoryOf
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.inbox.SelfTodo
import com.xjtu.toolbox.inbox.SelfTodoStore
import com.xjtu.toolbox.jwapp.ScoreItem
import com.xjtu.toolbox.jwapp.appJwappScoreCache
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.score.ScoreCalculator
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * 学期总结（#282）：只读本机已有的缓存与计数，不新登录、不上传。
 * 某一块读不到（没打开过那个页面、缓存过期）就是 null，那一页不出现，页面上给出去加载的入口。
 */
data class TermReport(
    val term: KnownTerm,
    val courses: CourseStats?,
    val attendance: AttendanceStats?,
    val grades: GradeStats?,
    val todos: TodoStats?,
    val card: CardStats?,
    val fitness: String?,
    val games: GameStats?,
    val agent: AgentStats?,
    val usage: UsageStats?,
) {
    val termName get() = term.name
    val start get() = term.startDate
    val end get() = term.endDate
    val days get() = (end.toEpochDays() - start.toEpochDays() + 1).toInt()

    /** 本机没缓存到的几块，以及去哪一页加载一次就能补上。 */
    val missing: List<Pair<String, AppRoute>>
        get() = listOfNotNull(
            ("课表" to AppRoute.Schedule).takeIf { courses == null },
            ("考勤" to AppRoute.Attendance).takeIf { attendance == null },
            ("成绩" to AppRoute.JwappScore).takeIf { grades == null },
            ("校园卡" to AppRoute.CampusCard).takeIf { card == null },
            ("体测" to AppRoute.Fitness).takeIf { fitness == null },
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
    /** 最晚上到第几节。 */
    val latestSection: Int,
    /** 单日最多上了几节，以及是星期几（0 = 周一）。 */
    val busiestDay: Pair<Int, Int>,
    val topTeacher: Pair<String, Int>?,
    /** 上课最多的楼（教室号前面那段）和节数。 */
    val topBuilding: Pair<String, Int>?,
    val customEvents: Int,
)

data class AttendanceStats(
    val total: Int,
    val normal: Int,
    val late: Int,
    val absent: Int,
    val leave: Int,
    /** 到场率：正常 + 迟到，占除请假外的全部。 */
    val rate: Float,
    val courses: Int,
    /** 一次迟到缺勤都没有的课。 */
    val perfectCourses: Int,
    val mostLate: Pair<String, Int>?,
    val topPlace: Pair<String, Int>?,
    /** 最长连续正常打卡次数。 */
    val longestStreak: Int,
)

data class GradeStats(
    val count: Int,
    val credits: Double,
    val gpa: Double,
    val average: Double,
    val best: ScoreItem?,
    val failed: Int,
    /** 95 分及以上的门数。 */
    val top95: Int,
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
    val activeDays: Int,
    val monthly: List<Pair<String, Double>>,
    val categories: List<Pair<String, Double>>,
    val meal: MealReport?,
    val priciest: Transaction?,
    val priciestDay: Pair<LocalDate, Double>?,
    /** 花钱最多的星期几（0 = 周一）。 */
    val topWeekday: Pair<Int, Double>?,
    val recharges: Int,
    /** 吃饭跨度最长的一天：日期、第一顿、最后一顿（HH:mm）。 */
    val longestMealDay: Triple<LocalDate, String, String>?,
)

data class GameStats(
    val minutes: Long,
    val days: Int,
    val favorite: String?,
    val longestDay: Pair<LocalDate, Long>?,
    /** 各游戏的历史战绩，如「合成西交大：最高 8192」。 */
    val records: List<String>,
)

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

data class UsageStats(
    val launches: Long,
    val days: Int,
    val topFeatures: List<Pair<String, Long>>,
    /** 凌晨 0–4 点打开的次数。 */
    val lateLaunches: Long,
    val payCodeOpens: Long,
)

/** 校历里见过的学期，记在本机：换学年后校历可能不再列上学期，总结也不能跟着丢。 */
@Serializable
data class KnownTerm(val code: String, val name: String, val start: String, val end: String, val weeks: Int) {
    val startDate get() = LocalDate.parse(start)
    /** 全部教学周过完的那天：校历结束日与「开学 + 周数」取较晚的（常规 18–19 周，小学期 4 周）。 */
    val endDate get() = LocalDate.parse(end)
}

/** [ended]：最近一个所有周都已过完的学期；[current]：今天所在的学期及第几周。 */
data class TermStatus(val ended: KnownTerm?, val current: Pair<KnownTerm, Int>?)

private val termMemory get() = keyValueStore("term_report_terms")

/** 拉校历并和本机记过的合并；拉不到就只用记过的。 */
suspend fun termStatus(today: LocalDate = todayOnCampus()): TermStatus = withContext(Dispatchers.IO) {
    val remembered = runCatching {
        AppJson.decodeFromString<List<KnownTerm>>(termMemory.getString("terms") ?: "[]")
    }.getOrDefault(emptyList())
    val fetched = runCatching { SchoolCalendarApi().terms() }.getOrDefault(emptyList()).mapNotNull { t ->
        val code = TermCodeMapper.termCodeOf(t.yearName, t.termName).ifBlank { return@mapNotNull null }
        val weeksEnd = t.startDate.plus(t.totalWeeks * 7 - 1, DateTimeUnit.DAY)
        KnownTerm(code, t.termName, t.startDate.toString(), maxOf(t.endDate, weeksEnd).toString(), t.totalWeeks)
    }
    val all = (remembered.filter { r -> fetched.none { it.code == r.code } } + fetched).sortedBy { it.start }.takeLast(16)
    if (fetched.isNotEmpty()) termMemory.putString("terms", AppJson.encodeToString(all))
    val current = all.firstOrNull { today >= it.startDate && today <= it.endDate }
    TermStatus(
        ended = all.lastOrNull { it.endDate < today },
        current = current?.let { it to ((today.toEpochDays() - it.startDate.toEpochDays()) / 7 + 1).toInt() },
    )
}

/** 按日期范围逐块读缓存，所以学期结束后过一阵再生成也一样准（成绩出得晚，刷新一下就有）。 */
suspend fun loadTermReport(context: Context, term: KnownTerm): TermReport = withContext(Dispatchers.IO) {
    val account = AccountContext.activeAccountId
    val start = term.startDate
    val end = term.endDate
    TermReport(
        term = term,
        courses = runCatching { courseStats(context, account, term.code, term.weeks) }.getOrNull(),
        attendance = runCatching { attendanceStats(account, term.code) }.getOrNull(),
        grades = runCatching { gradeStats(context, account, term.code) }.getOrNull(),
        todos = runCatching { todoStats(start, end) }.getOrNull(),
        card = runCatching { cardStats(context, account, start, end) }.getOrNull(),
        fitness = runCatching { HomeStats.pushed(context, AppRoute.Fitness) }.getOrNull()
            ?.let { listOfNotNull(it.value.ifBlank { null }, it.detail?.ifBlank { null }).joinToString(" · ") }?.ifBlank { null },
        games = runCatching { gameStats(start, end) }.getOrNull(),
        agent = runCatching { agentStats(start, end) }.getOrNull(),
        usage = runCatching { usageStats(start, end) }.getOrNull(),
    )
}

private fun <K> Map<K, Int>.top(): Pair<K, Int>? = maxByOrNull { it.value }?.toPair()

/** 「主楼A-301」「东3-205」→「主楼A」「东3」：教室号前那段当楼名。 */
private fun buildingOf(location: String): String =
    location.trim().replace(Regex("""[-—\s]*\d{3,4}[A-Za-z]?$"""), "").ifBlank { location.trim() }

private suspend fun courseStats(context: Context, account: String?, code: String, weeks: Int): CourseStats? {
    val items = ScheduleCache.readCourses(DataCache(context, account), code)?.takeIf { it.isNotEmpty() } ?: return null
    val perWeekday = IntArray(7)
    val perDay = HashMap<Int, Int>()
    val teachers = HashMap<String, Int>()
    val buildings = HashMap<String, Int>()
    var total = 0
    var early = 0
    for (c in items) {
        val active = c.getWeeks().filter { it <= weeks }
        val sections = (c.endSection - c.startSection + 1).coerceAtLeast(1)
        total += active.size * sections
        if (c.dayOfWeek in 1..7) perWeekday[c.dayOfWeek - 1] += active.size * sections
        if (c.startSection == 1) early += active.size
        active.forEach { w -> perDay.merge(w * 10 + c.dayOfWeek, sections, Int::plus) }
        c.teacher.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { teachers.merge(it, active.size * sections, Int::plus) }
        if (c.location.isNotBlank()) buildings.merge(buildingOf(c.location), active.size * sections, Int::plus)
    }
    val busiest = perDay.maxByOrNull { it.value }
    val customs = runCatching {
        AppDatabase.getInstance(context).customCourseDao().getByTerm(account.orEmpty(), code).size
    }.getOrDefault(0)
    return CourseStats(
        courses = items.map { it.courseCode.ifBlank { it.courseName } }.distinct().size,
        totalSections = total,
        weeklyAvg = total / weeks.coerceAtLeast(1),
        perWeekday = perWeekday.toList(),
        earlyClasses = early,
        latestSection = items.maxOf { it.endSection },
        busiestDay = ((busiest?.key ?: 1) % 10 - 1).coerceIn(0, 6) to (busiest?.value ?: 0),
        topTeacher = teachers.top(),
        topBuilding = buildings.top(),
        customEvents = customs,
    )
}

private fun attendanceStats(account: String?, code: String): AttendanceStats? {
    val records = (AttendanceRecordStore.load(false, code, account) ?: AttendanceRecordStore.load(true, code, account))
        ?.records?.takeIf { it.isNotEmpty() } ?: return null
    val byStatus = records.groupingBy { it.status }.eachCount()
    val normal = byStatus[WaterType.NORMAL] ?: 0
    val late = byStatus[WaterType.LATE] ?: 0
    val absent = byStatus[WaterType.ABSENCE] ?: 0
    val leave = byStatus[WaterType.LEAVE] ?: 0
    val counted = (normal + late + absent).coerceAtLeast(1)
    val byCourse = records.groupBy { it.courseCode.ifBlank { it.courseName } }
    var streak = 0
    var best = 0
    records.sortedWith(compareBy({ it.date }, { it.startTime })).forEach {
        if (it.status == WaterType.NORMAL) best = maxOf(best, ++streak) else if (it.status != WaterType.LEAVE) streak = 0
    }
    return AttendanceStats(
        total = records.size,
        normal = normal,
        late = late,
        absent = absent,
        leave = leave,
        rate = (normal + late).toFloat() / counted,
        courses = byCourse.size,
        perfectCourses = byCourse.count { (_, l) -> l.size >= 3 && l.all { it.status == WaterType.NORMAL || it.status == WaterType.LEAVE } },
        mostLate = records.filter { it.status == WaterType.LATE }.groupingBy { it.courseName }.eachCount().top(),
        topPlace = records.filter { it.location.isNotBlank() }.groupingBy { it.location }.eachCount().top(),
        longestStreak = best,
    )
}

private suspend fun gradeStats(context: Context, account: String?, code: String): GradeStats? {
    val terms = appJwappScoreCache(context, account).read(Long.MAX_VALUE) ?: return null
    val list = terms.firstOrNull { it.termCode == code }?.scoreList?.takeIf { it.isNotEmpty() } ?: return null
    val info = ScoreCalculator.calculateGpaForCourses(list)
    val previous = terms.filter { it.termCode < code && it.scoreList.isNotEmpty() }.maxByOrNull { it.termCode }
        ?.let { ScoreCalculator.calculateGpaForCourses(it.scoreList).gpa }
    val numeric = list.mapNotNull { it.scoreValue }
    return GradeStats(
        count = list.size,
        credits = list.sumOf { it.coursePoint },
        gpa = info.gpa,
        average = info.averageScore,
        best = list.filter { it.scoreValue != null }.maxByOrNull { it.scoreValue!! },
        failed = list.count { !ScoreCalculator.isPassed(it) },
        top95 = numeric.count { it >= 95 },
        bands = listOf(
            "90+" to numeric.count { it >= 90 },
            "80–89" to numeric.count { it >= 80 && it < 90 },
            "70–79" to numeric.count { it >= 70 && it < 80 },
            "60–69" to numeric.count { it >= 60 && it < 70 },
            "60 以下" to numeric.count { it < 60 },
        ),
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
    val byDay = spent.groupBy { dateOf(it)!! }
    val meals = spent.filter { categoryOf(it) == "餐饮" && it.time.length >= 16 }
    val mealDay = meals.groupBy { dateOf(it)!! }
        .mapValues { (_, l) -> l.minOf { it.time.substring(11, 16) } to l.maxOf { it.time.substring(11, 16) } }
        .maxByOrNull { (_, span) -> minutesOf(span.second) - minutesOf(span.first) }
    return CardStats(
        from = txs.mapNotNull(::dateOf).min(),
        spend = spent.sumOf { -it.amount },
        activeDays = byDay.size,
        monthly = spent.groupBy { it.time.take(7) }.map { (m, l) -> m to l.sumOf { -it.amount } }.sortedBy { it.first },
        categories = categorizeSpending(txs).toList().take(5),
        meal = MealReport.build(txs) { categoryOf(it) == "餐饮" },
        priciest = spent.minByOrNull { it.amount },
        priciestDay = byDay.mapValues { (_, l) -> l.sumOf { -it.amount } }.maxByOrNull { it.value }?.toPair(),
        topWeekday = byDay.entries.groupBy { it.key.dayOfWeek.ordinal }
            .mapValues { (_, days) -> days.sumOf { d -> d.value.sumOf { -it.amount } } }.maxByOrNull { it.value }?.toPair(),
        recharges = txs.count { it.amount > 0 },
        longestMealDay = mealDay?.takeIf { (_, s) -> minutesOf(s.second) - minutesOf(s.first) >= 12 * 60 }
            ?.let { (d, s) -> Triple(d, s.first, s.second) },
    )
}

private fun minutesOf(hhmm: String) = (hhmm.take(2).toIntOrNull() ?: 0) * 60 + (hhmm.drop(3).take(2).toIntOrNull() ?: 0)

private fun gameStats(start: LocalDate, until: LocalDate): GameStats? {
    val rows = GamePlaytime.between(start, until)
    val records = GameStore.titles.mapNotNull { (route, title) ->
        GameStore.summary(route)?.takeIf { !it.startsWith("继续") }?.let { "$title：$it" }
    }
    if (rows.isEmpty() && records.isEmpty()) return null
    val byDay = rows.groupBy { it.first }.mapValues { (_, l) -> l.sumOf { it.third } }
    return GameStats(
        minutes = rows.sumOf { it.third } / 60,
        days = byDay.size,
        favorite = rows.groupBy { it.second }.maxByOrNull { (_, l) -> l.sumOf { it.third } }?.key,
        longestDay = byDay.maxByOrNull { it.value }?.toPair(),
        records = records,
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

private fun usageStats(start: LocalDate, until: LocalDate): UsageStats? {
    val rows = AppUsage.between(start, until)
    val launches = rows.filter { it.second == "launch" }
    if (launches.isEmpty()) return null
    val titles = AppServices.all.associate { it.route.id to it.title } + GameStore.titles
    val opens = rows.filter { it.second.startsWith("open:") }.groupBy { it.second.removePrefix("open:") }
        .mapValues { (_, l) -> l.sumOf { it.third } }
    return UsageStats(
        launches = launches.sumOf { it.third },
        days = launches.map { it.first }.distinct().size,
        topFeatures = opens.filterKeys { it in titles && it != AppRoute.TermReport.id }.entries
            .sortedByDescending { it.value }.take(3).map { titles.getValue(it.key) to it.value },
        lateLaunches = rows.filter { it.second.removePrefix("hour:").toIntOrNull() in 0..4 && it.second.startsWith("hour:") }.sumOf { it.third },
        payCodeOpens = opens[AppRoute.PaymentCode.id] ?: 0,
    )
}
