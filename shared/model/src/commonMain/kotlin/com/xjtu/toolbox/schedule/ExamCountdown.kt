package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.nowInSystemZone
import com.xjtu.toolbox.util.todayInSystemZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

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
     * 月、日一位两位都要认（`LocalDate.parse` 只认两位，`2026-1-5` 会被当成没日期）；不合法返回 null。
     */
    fun parseDate(raw: String): LocalDate? {
        val s = raw.replace("年", "-").replace("月", "-").replace("日", "").trim()
        val parts = s.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    /**
     * `examTime` 形如 `14:30-16:30`。连接符见过 `-`、`~`、`～`、`—`、`至`、`到`，
     * 冒号也可能是全角的，都要认。解析不出来返回 null。
     */
    private val TIME_RANGE = Regex("""(\d{1,2})[:：](\d{2})\s*[-~～—–至到]+\s*(\d{1,2})[:：](\d{2})""")
    private val SINGLE_TIME = Regex("""(\d{1,2})[:：](\d{2})""")

    private fun timeOf(h: String, m: String): LocalTime? =
        runCatching { LocalTime(h.toInt(), m.toInt()) }.getOrNull()

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
    fun phaseOf(exam: ExamItem, now: LocalDateTime = nowInSystemZone()): ExamPhase {
        val date = parseDate(exam.examDate) ?: return ExamPhase.UNDATED
        val today = now.date
        return when {
            date < today -> ExamPhase.ENDED
            date == today -> {
                val end = endTimeOf(exam)
                if (end != null && now.time > end) ExamPhase.ENDED else ExamPhase.TODAY
            }
            date.toEpochDays() - today.toEpochDays() <= SOON_DAYS -> ExamPhase.SOON
            else -> ExamPhase.LATER
        }
    }

    /**
     * 最近的一场还没考完的考试。全都考完了返回 null。
     *
     * 按 [phaseOf] 判断：今天下午已经考完的那一场会被跳过，横幅和气泡直接指向下一场。
     */
    fun next(exams: List<ExamItem>, now: LocalDateTime): Next? {
        val today = now.date
        return exams.asSequence()
            .filter { phaseOf(it, now) != ExamPhase.ENDED }
            .mapNotNull { e -> parseDate(e.examDate)?.let { e to it } }
            .minByOrNull { it.second }
            ?.let { (e, d) -> Next(e, d, (d.toEpochDays() - today.toEpochDays()).toInt()) }
    }

    /**
     * 按日期算的旧入口，保留给现有调用方。
     *
     * 传的是今天（包括不传、用默认值）时，用此刻的时间判断，今天已考完的会被跳过；
     * 传的是别的日期时，按那一天的零点算，行为和以前一样：那天的考试都还没考。
     */
    fun next(exams: List<ExamItem>, today: LocalDate = todayInSystemZone()): Next? =
        if (today == todayInSystemZone()) next(exams, nowInSystemZone())
        else next(exams, LocalDateTime(today, LocalTime(0, 0)))
}
