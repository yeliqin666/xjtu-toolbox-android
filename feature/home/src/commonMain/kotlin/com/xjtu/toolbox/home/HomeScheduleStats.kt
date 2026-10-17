package com.xjtu.toolbox.home

import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** 首页那几格「只从课表族缓存本地推算」的状态：教学周、下一场考试、本学期课数与教材册数。 */
object HomeScheduleStats {

    fun of(
        cache: ScheduleCacheStore,
        termCode: String?,
        today: LocalDate = com.xjtu.toolbox.util.todayInSystemZone(),
    ): Map<String, HomeStat> {
        if (termCode.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, HomeStat>()

        // ── 校历 / 教学周：由学期开始日期本地推算，不需要网络 ──
        runCatching {
            ScheduleCache.readStartDate(cache, termCode)?.let { start ->
                val week = ((today.toEpochDays() - start.toEpochDays()) / 7 + 1).toInt()
                if (week in 1..30) {
                    out[AppRoute.SchoolCalendar.id] = HomeStat("第 $week 周", "$termCode 学期")
                }
            }
        }

        // ── 考试：最近一场 + 倒计时，7 天内扎堆时写场数 ──
        // 单独一项：首页 Hero 的考试格读它，不挤占「日程」那一格
        val now = if (today == com.xjtu.toolbox.util.todayInSystemZone()) com.xjtu.toolbox.util.nowInSystemZone()
        else LocalDateTime(today, LocalTime(0, 0))
        ScheduleCache.readExams(cache, termCode)
            ?.let { examStat(it, now) }
            ?.let { out[EXAM_KEY] = it }

        // ── 日程：本学期有多少门课。下节课和考试都在 Hero 里，这一格只给学期概况，
        // 否则假期/短学期里它永远是空的，看着像功能坏了。
        runCatching {
            val courses = ScheduleCache.readCourses(cache, termCode)
            if (!courses.isNullOrEmpty()) {
                out[AppRoute.Schedule.id] = HomeStat("${courses.size} 门课", "$termCode 学期")
            }
        }

        // ── 教材：本学期册数 ──
        val books = ScheduleCache.readTextbooks(cache, termCode, Long.MAX_VALUE)?.size ?: 0
        if (books > 0) out[AppRoute.Jiaocai.id] = HomeStat("$books 本", "本学期教材")

        return out
    }
}

/** 7 天内有两场以上时，考试格改说「近 7 天 N 场」。 */
private const val EXAM_CLUSTER_DAYS = 7

/**
 * 考试格的内容。[HomeStat.value] 是最近那场的课名；[HomeStat.detail] 第一段是格子的小标题
 * （「还有 3 天」，考试扎堆时是「近 7 天 3 场」），后面几段是时间、考场、座号，进考场前一眼看全。
 * 是否考完、日期怎么解析都按 [ExamCountdown]，和倒计时横幅同一时刻变。
 */
internal fun examStat(exams: List<ExamItem>, now: LocalDateTime): HomeStat? {
    val cd = ExamCountdown
    val next = cd.next(exams, now) ?: return null
    val today = now.date
    val soon = exams.count { e ->
        cd.phaseOf(e, now) != ExamCountdown.ExamPhase.ENDED &&
            cd.parseDate(e.examDate)?.let { (it.toEpochDays() - today.toEpochDays()).toInt() in 0 until EXAM_CLUSTER_DAYS } == true
    }
    val e = next.exam
    val clustered = soon >= 2
    val parts = listOfNotNull(
        if (clustered) "近 $EXAM_CLUSTER_DAYS 天 $soon 场" else next.label,
        next.label.takeIf { clustered },
        cd.startTimeOf(e)?.let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" },
        e.location.takeIf { it.isNotBlank() },
        e.seatNumber.takeIf { it.isNotBlank() }?.let { "座 $it" },
    )
    return HomeStat(e.courseName.ifBlank { "考试" }, parts.joinToString(" · "))
}