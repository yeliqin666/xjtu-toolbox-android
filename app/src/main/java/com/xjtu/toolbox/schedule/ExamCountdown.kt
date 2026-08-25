package com.xjtu.toolbox.schedule

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「下一场考试还有几天」。
 *
 * 独立出来是因为有三个消费者：日程页经典布局的考试栏、分级布局的常驻横幅、
 * 屁岱的倒计时气泡。日期解析的容错逻辑只该有一份。
 */
object ExamCountdown {

    data class Next(val exam: ExamItem, val date: LocalDate, val daysLeft: Int) {
        /** 今天考、明天考单独措辞，别显示成「还有 0 天」。 */
        val label: String
            get() = when (daysLeft) {
                0 -> "今天考试"
                1 -> "明天考试"
                else -> "还有 $daysLeft 天"
            }
    }

    /**
     * 教务的 `examDate` 有 `2026-01-05` 和 `2026年1月5日` 两种写法，都见过，都要认。
     * 认不出的当作没有日期直接丢掉——宁可少提醒一场，也不能把日期算错了去催用户。
     */
    fun parseDate(raw: String): LocalDate? = try {
        val s = raw.replace("年", "-").replace("月", "-").replace("日", "").trim()
        LocalDate.parse(s)
    } catch (_: Exception) {
        null
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
        val term = dc.get("schedule_term_list", Long.MAX_VALUE)
            ?.let { gson.fromJson(it, Array<String>::class.java)?.firstOrNull() }
        term?.let { t ->
            dc.get("exams_$t", Long.MAX_VALUE)?.let { json ->
                next(gson.fromJson(json, Array<ExamItem>::class.java).toList())
            }
        }
    } catch (_: Exception) {
        null
    }

    /** 从今天算起最近的一场还没考的考试。全都考完了返回 null。 */
    fun next(exams: List<ExamItem>, today: LocalDate = LocalDate.now()): Next? =
        exams.asSequence()
            .mapNotNull { e -> parseDate(e.examDate)?.let { e to it } }
            .filter { !it.second.isBefore(today) }
            .minByOrNull { it.second }
            ?.let { (e, d) -> Next(e, d, ChronoUnit.DAYS.between(today, d).toInt()) }
}
