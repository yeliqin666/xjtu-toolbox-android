package com.xjtu.toolbox.home

import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import kotlinx.datetime.LocalDate

/**
 * 首页那几格「**只从课表族缓存本地推算**」的状态：教学周、下一场考试、本学期课数与教材册数。
 *
 * ## 为什么它在 `:core`
 *
 * 这一段原来内联在 `:app` 的 `home/HomeStats.kt` 的 `collect` 里，与它相邻的那几段
 * （校园卡余额、各功能页推送的摘要）读的是 `DataCache` 的 `home_stat_*` 键与 `CampusCardCache`
 * ——那两样是 §5.1 那座桥，留在 `:app`。**而这一段读的三样全在共享层**：课表族缓存是
 * [ScheduleCache]（`:core` 的模型 + 宿主给的 [ScheduleCacheStore]）、考试表与教材表是同一份缓存里的
 * 另外两个键、时间用的也是 `:core` 的口径。所以它一次写清楚，各端同一份 ——
 * 桌面端的首页（`AppRoute.Main`）与 Android 的首页读的是同一段判定。
 *
 * ## 判定与键名逐字未动
 *
 * 四条与搬迁前一一对应（键仍然是 `AppRoute` 那三个 id 加考试那一个 [EXAM_KEY]）：
 *  - 教学周那一格**不锚到周一**（`(今天 - 开学) / 7 + 1`），与搬迁前同一个算式 ——
 *    Hero 上那个「第 N 周」（[HomeHero.weekNumber]，走 `TermWeeks.weekOf`，锚周一的）是另一条判据，
 *    两条都保持原样，这次搬家不改任何一个数；
 *  - 考试只认**今天及以后**最近的一场，键是 [EXAM_KEY]（Hero 的考试格读它，不挤占「日程」那一格）；
 *  - 日程那一格只给学期概况（「N 门课」），下节课在 Hero 里；
 *  - 教材为 0 本时**不写这一格**（目录里那一项退回纯入口，不显示占位符）。
 *
 * 唯一的形式调整：`java.time.LocalDate` → `kotlinx-datetime.LocalDate`，「今天」可由调用方递
 * （默认本机时区）—— 与 `HomeHero` / `TermWeeks` 同一条搬迁口径。
 *
 * 拿不到学期代码时返回空表（搬迁前那一句 `if (termCode.isNullOrBlank()) return`），
 * 表示「这一端算不出来」，而不是「什么都没有」。
 */
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
