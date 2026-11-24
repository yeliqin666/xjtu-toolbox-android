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
    /** 挑文案、称号的种子：学期 + 账号，同一人每次打开一样，不同人不重样。只在本机用，不写进 HTML。 */
    val seed: String = term.code,
    /** 学期里每天的动静（打卡 + 刷卡 + 打开盒子 + 找小岱），从开学那天排起；画学期日历用。 */
    val daily: List<Int> = emptyList(),
    /** 「关于岱宗盒子」那一页的数据，生成时取最新的一份写进 HTML。 */
    val project: ProjectStory? = null,
    /** 本机第一次装上岱宗盒子的日期。 */
    val installedOn: LocalDate? = null,
    val library: LibraryStats? = null,
    val study: StudyStats? = null,
    val major: MajorChange? = null,
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
    /** 节数最多的几门课。 */
    val topCourses: List<Pair<String, Int>> = emptyList(),
    /** 上午（1–4 节）/ 下午（5–8）/ 晚上（9 节起）各上了多少节。 */
    val periods: List<Int> = emptyList(),
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
    /** 全勤的课名（最多 3 个）。 */
    val perfectNames: List<String> = emptyList(),
    /** 迟到缺勤最多的星期几（0 = 周一）和次数。 */
    val slipWeekday: Pair<Int, Int>? = null,
)

data class GradeStats(
    val count: Int,
    val credits: Double,
    val gpa: Double,
    val average: Double,
    val best: ScoreItem?,
    val failed: Int,
    /** 95 分及以上（含 A+ / 优+）的门数。 */
    val top95: Int,
    /** 90+ / 80–89 / 70–79 / 60–69 / 60 以下 的门数；等级制按等效分归档，通过/不通过不算。 */
    val bands: List<Pair<String, Int>>,
    val previousGpa: Double?,
    /** 分数最高的几门：课名、显示用的分（「A+ · 98」）、等效分。 */
    val top: List<Triple<String, String, Double>> = emptyList(),
    /** 到这学期为止每学期的 GPA（「25秋」→ 3.61），按时间排。 */
    val history: List<Pair<String, Double>> = emptyList(),
    /** 学分最重的一门。 */
    val heaviest: ScoreItem? = null,
)

data class TodoStats(
    val created: Int,
    val done: Int,
    val overdue: Int,
    /** 每月新建几件（"2025-09" → 件数）。 */
    val monthly: List<Pair<String, Int>> = emptyList(),
    /** 小岱代建的件数。 */
    val byAgent: Int = 0,
) {
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
    /** 周一到周日各花了多少。 */
    val perWeekday: List<Double> = emptyList(),
    /** 最长连着几天没刷卡：从哪天起、几天。 */
    val quietStreak: Pair<LocalDate, Int>? = null,
)

data class GameStats(
    val minutes: Long,
    val days: Int,
    val favorite: String?,
    val longestDay: Pair<LocalDate, Long>?,
    /** 各游戏的历史战绩，如「合成西交大：最高 8192」。 */
    val records: List<String>,
    /** 各游戏玩了几分钟，多的在前。 */
    val perGame: List<Pair<String, Long>> = emptyList(),
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
    /** 0–23 点各发了几句。 */
    val hourly: List<Long> = emptyList(),
)

data class UsageStats(
    val launches: Long,
    val days: Int,
    val topFeatures: List<Pair<String, Long>>,
    /** 凌晨 0–4 点打开的次数。 */
    val lateLaunches: Long,
    val payCodeOpens: Long,
    /** 0–23 点各打开几次。 */
    val hourly: List<Long> = emptyList(),
    /** 用过几个功能。 */
    val features: Int = 0,
    /** 打开最多的一天和次数。 */
    val busiestDay: Pair<LocalDate, Long>? = null,
)

/** 转专业：学籍档案里的专业在上学期结束后到本学期结束前变了。[seen] 是本机第一次看到新专业的日子。 */
data class MajorChange(val from: String, val to: String, val fromDept: String, val toDept: String, val seen: LocalDate)

/** 图书馆座位预约，只算本机攒下的、预约日落在本学期的卡。 */
data class LibraryStats(
    val bookings: Int,
    val days: Int,
    /** 去得最多的区域（含馆名）和次数。 */
    val topArea: Pair<String, Int>?,
    /** 坐得最多的座位（区域 + 座位号）和次数。 */
    val topSeat: Pair<String, Int>?,
    val missed: Int,
    val cancelled: Int,
    /** 周一到周日各约了几次。 */
    val perWeekday: List<Int>,
    /** 本机看到的最早一次签到（HH:mm）。 */
    val earliestIn: String?,
    /** 中途离开过几次。 */
    val leaves: Int,
)

/** 思源学堂交作业、学辅资料、空闲教室、考试。 */
data class StudyStats(
    val submitted: Int,
    /** 截止前 3 小时内交的。 */
    val lastMinute: Int,
    /** 交得最多的课和份数。 */
    val topCourse: Pair<String, Int>?,
    val zyxfPreviews: Int,
    val zyxfDownloads: Int,
    /** 学辅里翻得最多的目录（课程或类别）。 */
    val zyxfTop: Pair<String, Int>?,
    val roomQueries: Int,
    val topRoomBuilding: Pair<String, Int>?,
    val exams: Int,
    /** 考试最集中的一天和场数。 */
    val examPeak: Pair<LocalDate, Int>?,
    /** 第一场和最后一场的日期。 */
    val examSpan: Pair<LocalDate, LocalDate>?,
) {
    val isEmpty get() = submitted == 0 && zyxfPreviews + zyxfDownloads == 0 && roomQueries == 0 && exams == 0
}

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
suspend fun knownTerms(): List<KnownTerm> = withContext(Dispatchers.IO) {
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
    all
}

suspend fun termStatus(today: LocalDate = todayOnCampus()): TermStatus {
    val all = knownTerms()
    val current = all.firstOrNull { today >= it.startDate && today <= it.endDate }
    return TermStatus(
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
        card = runCatching { cardStats(context, account, term.code, start, end) }.getOrNull(),
        fitness = runCatching { HomeStats.pushed(context, AppRoute.Fitness) }.getOrNull()
            ?.let { listOfNotNull(it.value.ifBlank { null }, it.detail?.ifBlank { null }).joinToString(" · ") }?.ifBlank { null },
        games = runCatching { gameStats(start, end) }.getOrNull(),
        agent = runCatching { agentStats(start, end) }.getOrNull(),
        usage = runCatching { usageStats(start, end) }.getOrNull(),
        seed = "${term.code}:${account.orEmpty()}",
        daily = runCatching { dailyActivity(context, account, term.code, start, end) }.getOrDefault(emptyList()),
        project = runCatching { loadProjectStory() }.getOrNull(),
        installedOn = installDate(context),
        library = runCatching { libraryStats(account, start, end) }.getOrNull(),
        study = runCatching { studyStats(context, account, term.code, start, end) }.getOrNull()?.takeUnless { it.isEmpty },
        major = runCatching { majorChange(account, term) }.getOrNull(),
    )
}

/** 归到本学期的专业变化：上一学期结束之后、本学期结束之前第一次见到新专业（暑假里转的算进秋季学期）。 */
private fun majorChange(account: String?, term: KnownTerm): MajorChange? {
    val remembered = runCatching {
        AppJson.decodeFromString<List<KnownTerm>>(termMemory.getString("terms") ?: "[]")
    }.getOrDefault(emptyList())
    val after = remembered.filter { it.endDate < term.startDate }.maxOfOrNull { it.endDate }
        ?: term.startDate.plus(-100, DateTimeUnit.DAY)
    return com.xjtu.toolbox.hello.MajorHistory.read(account).zipWithNext().lastOrNull { (_, b) ->
        localOf(b.at).date.let { it > after && it <= term.endDate }
    }?.let { (a, b) -> MajorChange(a.profession, b.profession, a.department, b.department, localOf(b.at).date) }
}

private fun localOf(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(CampusTimeZone)

private fun libraryStats(account: String?, start: LocalDate, until: LocalDate): LibraryStats? {
    val cards = com.xjtu.toolbox.library.LibraryLog.readCards(account)
        .filter { c -> dateOf(c.date)?.let { it in start..until } == true }
    if (cards.isEmpty()) return null
    val marks = com.xjtu.toolbox.library.LibraryLog.readMarks(account)
        .map { it to localOf(it.at) }.filter { (_, t) -> t.date in start..until }
    fun areaName(c: com.xjtu.toolbox.library.LibraryCard) =
        listOf(c.place, c.area).filter { it.isNotBlank() }.distinct().joinToString(" ")
    val weekdays = IntArray(7)
    cards.forEach { c -> dateOf(c.date)?.let { weekdays[it.dayOfWeek.ordinal]++ } }
    return LibraryStats(
        bookings = cards.size,
        days = cards.map { it.date }.distinct().size,
        topArea = cards.map(::areaName).filter { it.isNotBlank() }.groupingBy { it }.eachCount().top(),
        topSeat = cards.filter { it.seat.isNotBlank() }.groupingBy { "${it.area} ${it.seat}".trim() }.eachCount().top()
            ?.takeIf { it.second >= 2 },
        missed = cards.count { s -> listOf("违约", "未签到", "超时", "爽约").any { it in s.status } },
        cancelled = cards.count { "取消" in it.status },
        perWeekday = weekdays.toList(),
        earliestIn = marks.filter { (m, _) -> listOf("使用中", "已签到", "已入馆", "在馆").any { it in m.status } }
            .map { (_, t) -> "%02d:%02d".format(t.hour, t.minute) }.filter { it >= "05:00" }.minOrNull(),
        leaves = marks.count { (m, _) -> "离开" in m.status || "暂离" in m.status },
    )
}

private fun studyStats(context: Context, account: String?, code: String, start: LocalDate, until: LocalDate): StudyStats {
    val subs = com.xjtu.toolbox.util.LmsSubmitLog.all().filter { localOf(it.submittedAt).date in start..until }
    val zyxf = com.xjtu.toolbox.util.ZyxfUsage.between(start, until)
    fun count(key: String) = zyxf.filter { it.second == key }.sumOf { it.third }.toInt()
    val rooms = com.xjtu.toolbox.util.RoomQueries.between(start, until)
    val exams = ScheduleCache.readExams(DataCache(context, account), code).orEmpty()
        .mapNotNull { e -> dateOf(e.examDate)?.let { e to it } }
    val examDays = exams.groupingBy { it.second }.eachCount()
    return StudyStats(
        submitted = subs.size,
        lastMinute = subs.count { s -> s.deadline?.let { it >= s.submittedAt && it - s.submittedAt < 3 * 3600_000L } == true },
        topCourse = subs.filter { it.course.isNotBlank() }.groupingBy { it.course }.eachCount().top()?.takeIf { it.second >= 2 },
        zyxfPreviews = count("preview"),
        zyxfDownloads = count("download"),
        zyxfTop = zyxf.filter { it.second.startsWith("course:") }.groupBy { it.second.removePrefix("course:") }
            .mapValues { (_, l) -> l.sumOf { it.third }.toInt() }.top()?.takeIf { it.second >= 2 },
        roomQueries = rooms.sumOf { it.third }.toInt(),
        topRoomBuilding = rooms.groupBy { it.second }.mapValues { (_, l) -> l.sumOf { it.third }.toInt() }.top(),
        exams = exams.size,
        examPeak = examDays.maxByOrNull { it.value }?.toPair()?.takeIf { it.second >= 2 },
        examSpan = exams.map { it.second }.takeIf { it.size >= 2 }?.let { it.min() to it.max() },
    )
}

/** 每天的动静：考勤打卡、校园卡笔数、打开盒子、找小岱的句数相加。 */
private suspend fun dailyActivity(context: Context, account: String?, code: String, start: LocalDate, until: LocalDate): List<Int> {
    val n = (until.toEpochDays() - start.toEpochDays() + 1).toInt().coerceIn(0, 400)
    val out = IntArray(n)
    fun bump(date: LocalDate?, by: Int = 1) {
        val i = date?.let { (it.toEpochDays() - start.toEpochDays()).toInt() } ?: return
        if (i in 0 until n) out[i] += by
    }
    (AttendanceRecordStore.load(false, code, account) ?: AttendanceRecordStore.load(true, code, account))
        ?.records?.forEach { bump(dateOf(it.date)) }
    (termCardTransactions(context, account, code) ?: appCampusCardStore(context).load(account)?.transactions)
        ?.forEach { bump(dateOf(it.time)) }
    AppUsage.between(start, until).filter { it.second == "launch" }.forEach { bump(it.first, it.third.toInt()) }
    AgentUsage.between(start, until).filter { it.second == "msg" }.forEach { bump(it.first, it.third.toInt()) }
    return if (out.any { it > 0 }) out.toList() else emptyList()
}

/** "2025-09-08 12:03:21" / "2025-09-08" → 日期。 */
private fun dateOf(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

private fun <K> Map<K, Int>.ranked(n: Int): List<Pair<K, Int>> =
    entries.sortedByDescending { it.value }.take(n).map { it.toPair() }

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
    val byCourse = HashMap<String, Int>()
    val periods = IntArray(3)
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
        if (c.courseName.isNotBlank()) byCourse.merge(c.courseName.trim(), active.size * sections, Int::plus)
        for (s in c.startSection..c.endSection.coerceAtLeast(c.startSection)) {
            periods[if (s <= 4) 0 else if (s <= 8) 1 else 2] += active.size
        }
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
        topCourses = byCourse.ranked(4),
        periods = periods.toList(),
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
    val slips = records.filter { it.status == WaterType.LATE || it.status == WaterType.ABSENCE }
    val perfect = byCourse.filter { (_, l) -> l.size >= 3 && l.all { it.status == WaterType.NORMAL || it.status == WaterType.LEAVE } }
    return AttendanceStats(
        total = records.size,
        normal = normal,
        late = late,
        absent = absent,
        leave = leave,
        rate = (normal + late).toFloat() / counted,
        courses = byCourse.size,
        perfectCourses = perfect.size,
        mostLate = records.filter { it.status == WaterType.LATE }.groupingBy { it.courseName }.eachCount().top(),
        topPlace = records.filter { it.location.isNotBlank() }.groupingBy { it.location }.eachCount().top(),
        longestStreak = best,
        perfectNames = perfect.values.sortedByDescending { it.size }.mapNotNull { l -> l.first().courseName.ifBlank { null } }.take(3),
        slipWeekday = slips.mapNotNull { dateOf(it.date)?.dayOfWeek?.ordinal }.groupingBy { it }.eachCount().top(),
    )
}

private suspend fun gradeStats(context: Context, account: String?, code: String): GradeStats? {
    val terms = appJwappScoreCache(context, account).read(Long.MAX_VALUE) ?: return null
    val list = terms.firstOrNull { it.termCode == code }?.scoreList?.takeIf { it.isNotEmpty() } ?: return null
    val info = ScoreCalculator.calculateGpaForCourses(list)
    val previous = terms.filter { it.termCode < code && it.scoreList.isNotEmpty() }.maxByOrNull { it.termCode }
        ?.let { ScoreCalculator.calculateGpaForCourses(it.scoreList).gpa }
    // 分档按「等效分」：数字成绩直接用，等级制（A+ / 优- 等）按绩点折回该档下限分；通过/不通过不分档
    val level = list.associateWith { equivalentScore(it) }
    val numeric = level.values.filterNotNull()
    return GradeStats(
        count = list.size,
        credits = list.sumOf { it.coursePoint },
        gpa = info.gpa,
        average = info.averageScore,
        // 同档时数字分优先（92 比 A 更具体）
        best = list.filter { level[it] != null }.maxWithOrNull(compareBy({ level[it] }, { it.scoreValue ?: 0.0 })),
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
        top = list.mapNotNull { item -> level[item]?.let { Triple(item.courseName, scoreLabel(item), it) } }
            .sortedByDescending { it.third }.take(5),
        history = terms.filter { it.termCode <= code && it.scoreList.isNotEmpty() }.sortedBy { it.termCode }
            .map { termShortName(it.termCode) to ScoreCalculator.calculateGpaForCourses(it.scoreList).gpa }
            .filter { it.second > 0 }.takeLast(8),
        heaviest = list.filter { it.coursePoint > 0 }.maxByOrNull { it.coursePoint },
    )
}

/** 成绩怎么显示：等级制带上教务给的具体分（「A+ · 98」），数字分原样。 */
fun scoreLabel(item: ScoreItem): String {
    val raw = item.score.trim()
    val value = item.scoreValue ?: return raw
    val num = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    return when {
        raw.isEmpty() -> num
        raw.toDoubleOrNull() != null -> raw
        else -> "$raw · $num"
    }
}

/** "2025-2026-1" → 「25秋」，"-2" → 「26春」，"-3" → 「26夏」。 */
fun termShortName(code: String): String {
    val parts = code.split('-')
    val from = parts.getOrNull(0)?.takeLast(2) ?: return code
    val to = parts.getOrNull(1)?.takeLast(2) ?: return code
    return when (parts.getOrNull(2)) {
        "1" -> "${from}秋"
        "2" -> "${to}春"
        "3" -> "${to}夏"
        else -> code
    }
}

/** 绩点 → 该档的下限分（西交 4.3 制）：A+ 95、A 90、A- 85、B+ 81、B 78、B- 75、C+ 72、C 68、C- 64、D 60。 */
private val GPA_FLOOR = listOf(4.3 to 95.0, 4.0 to 90.0, 3.7 to 85.0, 3.3 to 81.0, 3.0 to 78.0, 2.7 to 75.0,
    2.3 to 72.0, 2.0 to 68.0, 1.7 to 64.0, 1.3 to 60.0)

private fun equivalentScore(item: ScoreItem): Double? {
    item.scoreValue?.let { return it }
    val gpa = ScoreCalculator.scoreToGpa(item.score.trim()) ?: return null
    return GPA_FLOOR.firstOrNull { gpa >= it.first - 0.01 }?.second ?: 0.0
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
        monthly = inTerm.mapNotNull { t -> (created(t) ?: due(t))?.toString()?.take(7) }
            .groupingBy { it }.eachCount().toList().sortedBy { it.first },
        byAgent = inTerm.count { it.source == "agent" },
    )
}

private suspend fun cardStats(context: Context, account: String?, code: String, start: LocalDate, until: LocalDate): CardStats? {
    // 优先用生成前补齐的整学期流水；没补过才用校园卡页那份快照（可能只有最近几页）
    val source = termCardTransactions(context, account, code)
        ?: appCampusCardStore(context).load(account)?.transactions
        ?: return null
    fun dateOf(tx: Transaction) = dateOf(tx.time)
    val txs = source.filter { tx -> dateOf(tx)?.let { it in start..until } == true }
    val spent = txs.filter { it.amount < 0 }
    if (spent.isEmpty()) return null
    val byDay = spent.groupBy { dateOf(it)!! }
    // 相邻两个刷卡日之间空了几天；开学前、期末后不算
    val quiet = byDay.keys.sorted().zipWithNext().maxByOrNull { (a, b) -> b.toEpochDays() - a.toEpochDays() }
        ?.let { (a, b) -> a.plus(1, DateTimeUnit.DAY) to (b.toEpochDays() - a.toEpochDays() - 1).toInt() }
        ?.takeIf { it.second >= 4 }
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
        perWeekday = (0..6).map { d -> byDay.filterKeys { it.dayOfWeek.ordinal == d }.values.sumOf { l -> l.sumOf { -it.amount } } },
        quietStreak = quiet,
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
        perGame = rows.groupBy { it.second }.map { (g, l) -> g to l.sumOf { it.third } / 60 }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(4),
    )
}

/** `hour:<0–23>` 计数摊成 24 格。 */
private fun hourly(rows: List<Triple<LocalDate, String, Long>>): List<Long> {
    val out = LongArray(24)
    rows.forEach { (_, key, n) -> key.removePrefix("hour:").takeIf { key.startsWith("hour:") }?.toIntOrNull()?.takeIf { it in 0..23 }?.let { out[it] += n } }
    return out.toList()
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
        hourly = hourly(rows),
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
        lateLaunches = rows.filter { it.second.removePrefix("hour:").toIntOrNull() in 0..3 && it.second.startsWith("hour:") }.sumOf { it.third },
        payCodeOpens = opens[AppRoute.PaymentCode.id] ?: 0,
        hourly = hourly(rows),
        features = opens.keys.count { it in titles },
        busiestDay = launches.groupBy { it.first }.mapValues { (_, l) -> l.sumOf { it.third } }.maxByOrNull { it.value }?.toPair(),
    )
}
