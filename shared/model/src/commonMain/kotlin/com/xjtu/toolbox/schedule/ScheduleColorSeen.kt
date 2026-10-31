package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore

/**
 * 「日程里出现过的课名」这张小表。
 *
 * 收纳里给自建待办挑颜色时，快捷色片要的是**日程里看得见的那些颜色**：用户给课程挑的
 * 自选色，加上没挑过的课按课名哈希算出来的默认色。只列自选色会漏掉后者，而日程里明明
 * 看得见它们 —— 所以把课名记下来，颜色怎么算交给 designsystem 的 `CourseColors`。
 *
 * 记两处，都只在真实数据到手时：
 * - 读到课表缓存时（[ScheduleCache.readCourses]：首页统计、日程页、屁岱都走它）；
 * - 用户加 / 改自建日程时（日程页的 `ScheduleViewModel`；自建日程不在课表缓存里）。
 *
 * 只增不减（上限 [LIMIT] 个课名），跨学期留着 —— 换回去还能接着挑同几个色。
 * 按账号分开存（`course_colors_seen<账号后缀>`），和 `course_colors` 同一个规矩。
 */
object ScheduleColorSeen {

    private const val KEY = "names"

    /** 最多记多少个课名：够盖几个学期，又不至于把这个 store 越写越大。 */
    private const val LIMIT = 200

    private fun store() = keyValueStore("course_colors_seen" + AccountContext.safeSuffix())

    /** 一行一个课名（课名里不会有换行；Web 那份「字符串集合」是按逗号拆的，不能用）。 */
    fun names(): List<String> =
        store().getString(KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** 记下这一批课名；没有新的名字就不写盘。 */
    fun note(courses: Collection<String>) {
        val clean = courses.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        val before = names()
        val fresh = clean.distinct().filter { it !in before }
        if (fresh.isEmpty()) return
        store().putString(KEY, (before + fresh).takeLast(LIMIT).joinToString("\n"))
    }
}
