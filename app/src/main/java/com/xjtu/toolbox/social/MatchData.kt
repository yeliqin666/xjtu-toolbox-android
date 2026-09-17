package com.xjtu.toolbox.social

import android.content.Context
import com.google.gson.Gson
import com.xjtu.toolbox.hello.HelloProfile
import com.xjtu.toolbox.hello.HelloProfileStore
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.TextbookItem
import com.xjtu.toolbox.util.DataCache

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
        /** 当前学期的课。作息、同课、偶遇都从这里来。 */
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
    )

    fun read(ctx: Context): Local {
        val dc = DataCache(ctx)
        val gson = Gson()
        val terms = runCatching {
            dc.get("schedule_term_list", Long.MAX_VALUE)
                ?.let { gson.fromJson(it, Array<String>::class.java)?.toList() }
                .orEmpty()
        }.getOrDefault(emptyList())
        val current = terms.firstOrNull()

        val courses = current?.let { readCourses(dc, gson, it) }.orEmpty()
        // 往期只取课程号。逛过几个学期就有几个学期，没逛过的学期缓存里根本没有。
        val past = LinkedHashSet<String>()
        var pastTerms = 0
        for (t in terms.drop(1)) {
            val list = readCourses(dc, gson, t)
            if (list.isEmpty()) continue
            pastTerms++
            list.forEach { c ->
                if (past.size < MAX_PAST_COURSES) c.courseCode.trim().takeIf { s -> s.isNotEmpty() }
                    ?.let(past::add)
            }
        }
        // 当前学期的课不算"往期"，否则同课那一维会跟它自己重复一遍。
        past.removeAll(courses.map { it.courseCode.trim() }.toSet())

        val textbooks = current?.let { t ->
            runCatching {
                dc.get("schedule_textbooks_$t", Long.MAX_VALUE)?.let { json ->
                    gson.fromJson(json, Array<TextbookItem>::class.java)
                        .filter { it.hasSubstantiveTextbook }
                        .map { it.textbookName.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                }
            }.getOrNull()
        }.orEmpty()

        val exams = current?.let { t ->
            runCatching {
                dc.get("exams_$t", Long.MAX_VALUE)?.let { json ->
                    gson.fromJson(json, Array<ExamItem>::class.java).toList()
                }
            }.getOrNull()
        }.orEmpty()

        return Local(
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

    private fun readCourses(dc: DataCache, gson: Gson, term: String): List<CourseItem> =
        runCatching {
            dc.get("schedule_$term", Long.MAX_VALUE)?.let { json ->
                gson.fromJson(json, Array<CourseItem>::class.java).toList()
            }
        }.getOrNull().orEmpty()
}
