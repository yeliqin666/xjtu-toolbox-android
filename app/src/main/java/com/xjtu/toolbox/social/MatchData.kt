package com.xjtu.toolbox.social

import android.content.Context
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.hello.HelloProfile
import com.xjtu.toolbox.hello.HelloProfileStore
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.data.DataCache

/**
 * 匹配交友的取数层。
 *
 * 这个功能一条请求都不发，只把别的页面已经落盘的缓存捡起来。所以能比什么，
 * 完全取决于用户逛过哪些页面——没进过校园卡页就没有食堂数据，界面会直说，
 * 而不是拿一份空数据去凑一个维度出来。
 *
 * 全部读缓存意味着**离线可用**，也意味着数据可能有点旧。对"课表合不合得来"
 * 这种问题，上周的课表和今天的课表答案一样，不值得为它触发一次教务登录。
 */
object MatchData {

    /** 往期课程最多带这么多门。四年下来两百门课全塞进分享码，那段码就没法粘了。 */
    private const val MAX_PAST_COURSES = 120

    data class Local(
        /** 拿来比的那个学期（默认本学期，用户可以换成历史学期）。作息、同课、偶遇都从这里来。 */
        val term: String? = null,
        /** 真正的本学期，界面据此标「本学期」。 */
        val currentTerm: String? = null,
        /** 本地有课表缓存、可以拿来比的学期，本学期排最前。 */
        val availableTerms: List<String> = emptyList(),
        /** 学期码 → 给人看的名字（「2025-2026 学年第一学期」）。 */
        val termNames: Map<String, String> = emptyMap(),
        /**
         * [term] 那个学期的课。只有教务课表缓存里的课：用户自己在日程页添加的课存在单独的库里，
         * 这里从来不读——那往往是私人安排，不该跟着码发给别人。
         */
        val courses: List<CourseItem> = emptyList(),
        /** 往期学期的课程号。只有号没有名——名字会让分享码大一倍，而"一起上过几门"不需要名字。 */
        val pastCourseCodes: Set<String> = emptySet(),
        /** 缓存里除当前学期外还有几个学期。用来在界面上说清这一维覆盖多久。 */
        val pastTermCount: Int = 0,
        val textbooks: List<String> = emptyList(),
        val exams: List<ExamItem> = emptyList(),
        /** 小时 → 该小时的食堂消费笔数。 */
        val diningHourCounts: Map<Int, Int> = emptyMap(),
        /** 常去的食堂，按次数降序。只有名字，没有金额。 */
        val canteens: List<String> = emptyList(),
        val profile: HelloProfile? = null,
    ) {
        /**
         * 有没有任何一维拿得出数据。
         *
         * 全空时分享码里只剩一个名字：码本身能出、也能扫，但对方算不出任何东西。
         * 界面靠这个字段提前说清"先去别的页面转一圈"，而不是让人对着一张
         * 扫完什么都没有的码猜是哪里坏了。
         */
        val hasAnything: Boolean
            get() = courses.isNotEmpty() || pastCourseCodes.isNotEmpty() ||
                textbooks.isNotEmpty() || exams.isNotEmpty() ||
                diningHourCounts.isNotEmpty() || canteens.isNotEmpty() || profile != null
    }

    /**
     * @param term 要拿来比的学期；null 表示本学期。
     */
    fun read(ctx: Context, term: String? = null): Local {
        val dc = DataCache(ctx)
        val terms = ScheduleCache.readTermList(dc)
        // 本学期读「当前学期」，不读上次翻到的学期：看一眼去年的课表，这里就会把去年当成本学期
        val thisTerm = ScheduleCache.readCurrentTerm(dc)
        val current = term ?: thisTerm
        // 能选的学期：本地有课表缓存的那些，本学期排最前
        val available = (listOfNotNull(thisTerm) + terms).distinct()
            .filter { it == thisTerm || readCourses(dc, it).isNotEmpty() }
        val nameMap = com.xjtu.toolbox.schedule.ScheduleTermStore.read(dc)
        val termNames = available.associateWith {
            com.xjtu.toolbox.schedule.ScheduleTermStore.display(it, emptyMap(), nameMap)
        }

        val courses = current?.let { readCourses(dc, it) }.orEmpty()
        // 往期只取课程号。逛过几个学期就有几个学期，没逛过的学期缓存里根本没有。
        val past = LinkedHashSet<String>()
        var pastTerms = 0
        for (t in terms.filter { it != current }) {
            val list = readCourses(dc, t)
            if (list.isEmpty()) continue
            pastTerms++
            list.forEach { c ->
                if (past.size < MAX_PAST_COURSES) c.courseCode.trim().takeIf { s -> s.isNotEmpty() }
                    ?.let(past::add)
            }
        }
        // 当前学期的课不算"往期"，否则同课那一维会跟它自己重复一遍。
        past.removeAll(courses.map { it.courseCode.trim() }.toSet())

        val textbooks = current?.let { ScheduleCache.readTextbooks(dc, it, Long.MAX_VALUE) }.orEmpty()
            .filter { it.hasSubstantiveTextbook }
            .map { it.textbookName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val exams = current?.let { ScheduleCache.readExams(dc, it) }.orEmpty()

        return Local(
            term = current,
            currentTerm = thisTerm,
            availableTerms = available,
            termNames = termNames,
            courses = courses,
            pastCourseCodes = past,
            pastTermCount = pastTerms,
            textbooks = textbooks,
            exams = exams,
            diningHourCounts = runCatching { DiningHabit.readCachedHourCounts(ctx) }
                .getOrDefault(emptyMap()),
            canteens = runCatching { DiningHabit.readCachedCanteens(ctx) }
                .getOrDefault(emptyList()),
            profile = runCatching { HelloProfileStore.cached(ctx) }.getOrNull(),
        )
    }

    private fun readCourses(dc: DataCache, term: String): List<CourseItem> = ScheduleCache.readCourses(dc, term).orEmpty()
}
