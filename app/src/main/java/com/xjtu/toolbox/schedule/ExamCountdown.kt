package com.xjtu.toolbox.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 考试的时间判断：「下一场考试还有几天」，以及每一场现在处于什么状态。
 *
 * 独立出来是因为消费者很多：日程页的考试卡片和列表、常驻的倒计时横幅、
 * 屁岱的倒计时气泡、今日栏的「接下来」。日期、时间的解析容错和「快考试了」的阈值
 * 都只该有一份，否则会出现气泡说「还有 3 天」、卡片却还没变红这种前后不一。
 */
object ExamCountdown {

    /**
     * 「快考试了」的阈值：距今 1 到 [SOON_DAYS] 天算快了。
     * 屁岱的考试提醒（ProactiveBubble 的 EXAM_AHEAD_DAYS）也引用它，两边在同一天变。
     */
    const val SOON_DAYS = 3

    /** 一场考试此刻所处的状态。决定卡片的颜色、标签和排序，见 plan2 §1.3。 */
    enum class ExamPhase {
        /** 今天考，而且还没到结束时刻（或者结束时刻解析不出来）。 */
        TODAY,

        /** 距今 1 到 [SOON_DAYS] 天。 */
        SOON,

        /** 更远。 */
        LATER,

        /** 日期已过，或者今天的考试已经过了结束时刻。 */
        ENDED,

        /** 日期解析不出来。不猜，单独放。 */
        UNDATED,
    }

    data class Next(val exam: ExamItem, val date: LocalDate, val daysLeft: Int) {
        /** 今天考、明天考单独措辞，别显示成「还有 0 天」。 */
        val label: String
            get() = when (daysLeft) {
                0 -> "今天考试"
                1 -> "明天就考"
                else -> "还有 $daysLeft 天"
            }
    }

    /**
     * 教务的 `examDate` 有 `2026-01-05` 和 `2026年1月5日` 两种写法，都见过，都要认。
     * 认不出的当作没有日期直接丢掉——宁可少提醒一场，也不能把日期算错了去催用户。
     *
     * 月、日必须按一位或两位都接受：以前直接用 `LocalDate.parse`，它只认两位，
     * `2026年1月5日` 换成横杠以后是 `2026-1-5`，解析失败，这场考试就被悄悄当成没有日期，
     * 倒计时和提醒都会漏掉它。
     */
    private val DATE = java.time.format.DateTimeFormatter.ofPattern("uuuu-M-d")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun parseDate(raw: String): LocalDate? = try {
        val s = raw.replace("年", "-").replace("月", "-").replace("日", "").trim()
        LocalDate.parse(s, DATE)
    } catch (_: Exception) {
        null
    }

    /**
     * `examTime` 形如 `14:30-16:30`。连接符见过 `-`、`~`、`～`、`—`、`至`、`到`，
     * 冒号也可能是全角的，都要认。解析不出来返回 null。
     */
    private val TIME_RANGE = Regex("""(\d{1,2})[:：](\d{2})\s*[-~～—–至到]+\s*(\d{1,2})[:：](\d{2})""")
    private val SINGLE_TIME = Regex("""(\d{1,2})[:：](\d{2})""")

    private fun timeOf(h: String, m: String): LocalTime? =
        runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()

    /** 开考时刻；只写了一个时间的也算开考时刻。 */
    fun startTimeOf(exam: ExamItem): LocalTime? {
        TIME_RANGE.find(exam.examTime)?.let { m ->
            return timeOf(m.groupValues[1], m.groupValues[2])
        }
        return SINGLE_TIME.find(exam.examTime)?.let { timeOf(it.groupValues[1], it.groupValues[2]) }
    }

    /** 结束时刻。只有写成时间段才有，单个时间不猜考多久。 */
    fun endTimeOf(exam: ExamItem): LocalTime? =
        TIME_RANGE.find(exam.examTime)?.let { timeOf(it.groupValues[3], it.groupValues[4]) }

    /**
     * 这场考试此刻的状态。
     *
     * 今天的考试只有在**确实解析出了结束时刻、并且已经过了**的时候才算结束；
     * 时间写得乱、解析不出来的，宁可一直标成「今天」到半夜，也不能提前把它压暗成「已结束」。
     */
    fun phaseOf(exam: ExamItem, now: LocalDateTime = LocalDateTime.now()): ExamPhase {
        val date = parseDate(exam.examDate) ?: return ExamPhase.UNDATED
        val today = now.toLocalDate()
        return when {
            date.isBefore(today) -> ExamPhase.ENDED
            date == today -> {
                val end = endTimeOf(exam)
                if (end != null && now.toLocalTime().isAfter(end)) ExamPhase.ENDED else ExamPhase.TODAY
            }
            ChronoUnit.DAYS.between(today, date) <= SOON_DAYS -> ExamPhase.SOON
            else -> ExamPhase.LATER
        }
    }

    /**
     * 直接从磁盘缓存读，不发任何请求。
     *
     * 日程页每次加载都会把考试表写进 `exams_<学期>`，所以这里总能拿到最近一次的结果。
     * 屁岱的倒计时气泡走这条路：为了提醒单独去拉一次教务是不值得的，
     * 而且那会变成一次后台登录。缓存是空的（还没进过日程页）就不提醒，可以接受。
     */
    fun fromCache(ctx: android.content.Context): Next? = try {
        val dc = com.xjtu.toolbox.util.DataCache(ctx)
        val gson = com.google.gson.Gson()
        // 本学期的考试才需要倒计时。不能读 schedule_last_term：那是用户上一次翻到的学期，
        // 翻了一眼去年的课表，倒计时就会拿去年的考试来算。见 ScheduleCache.readCurrentTerm。
        val term = ScheduleCache.readCurrentTerm(dc, gson)
        term?.let { t ->
            dc.get("exams_$t", Long.MAX_VALUE)?.let { json ->
                next(gson.fromJson(json, Array<ExamItem>::class.java).toList().map { it.sanitized() })
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 最近的一场还没考完的考试。全都考完了返回 null。
     *
     * 按 [phaseOf] 判断：今天下午已经考完的那一场会被跳过，横幅和气泡直接指向下一场。
     */
    fun next(exams: List<ExamItem>, now: LocalDateTime): Next? {
        val today = now.toLocalDate()
        return exams.asSequence()
            .filter { phaseOf(it, now) != ExamPhase.ENDED }
            .mapNotNull { e -> parseDate(e.examDate)?.let { e to it } }
            .minByOrNull { it.second }
            ?.let { (e, d) -> Next(e, d, ChronoUnit.DAYS.between(today, d).toInt()) }
    }

    /**
     * 按日期算的旧入口，保留给现有调用方。
     *
     * 传的是今天（包括不传、用默认值）时，用此刻的时间判断，今天已考完的会被跳过；
     * 传的是别的日期时，按那一天的零点算，行为和以前一样：那天的考试都还没考。
     */
    fun next(exams: List<ExamItem>, today: LocalDate = LocalDate.now()): Next? =
        if (today == LocalDate.now()) next(exams, LocalDateTime.now())
        else next(exams, today.atStartOfDay())
}
