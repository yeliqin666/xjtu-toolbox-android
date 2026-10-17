package com.xjtu.toolbox.home

import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import kotlinx.datetime.LocalDate

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

        // ── 考试：最近一场 + 倒计时 ──
        ScheduleCache.readExams(cache, termCode)?.let { exams ->
            exams.mapNotNull { e ->
                val day = runCatching { LocalDate.parse(e.examDate.take(10)) }.getOrNull()
                    ?: return@mapNotNull null
                if (day < today) null else e to day
            }.minByOrNull { it.second }?.let { (e, day) ->
                val days = day.toEpochDays() - today.toEpochDays()
                // 单独一项：首页 Hero 的考试倒计时读它，不挤占「日程」那一格
                out[EXAM_KEY] = HomeStat(
                    e.courseName.ifBlank { "考试" },
                    buildString {
                        append(if (days == 0L) "就在今天" else "还有 $days 天")
                        if (e.location.isNotBlank()) append(" · ${e.location}")
                    },
                )
            }
        }

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
