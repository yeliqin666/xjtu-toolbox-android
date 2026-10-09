package com.xjtu.toolbox.schedule

/**
 * 自定义日程的冲突判定。
 *
 * 以前只看「同学期 + 同星期 + 节次有交集」，不看周次：第 4 周周四 14–18 点的实验
 * 和第 8 周同一时段的实验被当成冲突，旧的被直接删掉。实验课、答疑这种一周一条单独加的
 * 日程几乎必中。现在三样都要重叠才算：周次有交集、同星期、时间有交集。
 *
 * 从 :app 搬进 commonMain 时只改了入参类型：原来直接吃 Room 实体 `CustomCourseEntity`
 * （那是 Android 的落库形状，必须留在 :app），现在吃模型 [CourseItem]——调用方用
 * `entity.toCourseItem()` 转一下。**学期相同与否也改成显式入参**：`termCode` 只存在于实体上，
 * 与其搬一个半拉子模型过来，不如让调用方把「同学期」这个已经查过的条件传进来（没有默认值，
 * 免得漏传时把跨学期的两条也判成冲突）。逻辑本身一行未动。
 */
object CustomCourseConflicts {

    /**
     * [a] 与 [b] 是否真的撞车。
     *
     * @param sameTerm 两条是否属于同一学期。**没有默认值**：实体上才有 `termCode`，
     *   调用方（DAO 查询）本来就先按学期筛过，把结论传进来比再搬一个模型干净。
     */
    fun conflicts(a: CourseItem, b: CourseItem, sameTerm: Boolean): Boolean =
        sameTerm &&
            a.dayOfWeek == b.dayOfWeek &&
            sharedWeeks(a.weekBits, b.weekBits).isNotEmpty() &&
            timeOverlaps(a, b)

    /** 两个周次位图（第 1 位 = 第 1 周）都为 1 的周，升序。长度不同时按短的比。 */
    fun sharedWeeks(a: String, b: String): List<Int> =
        (0 until minOf(a.length, b.length))
            .filter { a[it] == '1' && b[it] == '1' }
            .map { it + 1 }

    /**
     * 按真实起止比（[CourseItem.clockMinutes]，和周视图、桌面卡片同一个算法：给了钟点用钟点，
     * 老日程按「8 点起每小时一节」还原）。首尾相接不算重叠：14:00 结束和 14:00 开始可以并存。
     * 自建日程的起止与冬夏作息无关，传哪套都一样。
     */
    fun timeOverlaps(a: CourseItem, b: CourseItem): Boolean {
        val (aStart, aEnd) = a.clockMinutes(summer = true)
        val (bStart, bEnd) = b.clockMinutes(summer = true)
        return aStart < bEnd && bStart < aEnd
    }

    /** 冲突提示里用的周次描述：连续的周合并成区间，如「第 3–5、8 周」。 */
    fun describeWeeks(weeks: List<Int>): String =
        if (weeks.isEmpty()) "" else "第 ${TermWeeks.formatRanges(weeks, sep = "、", dash = "–")} 周"
}
