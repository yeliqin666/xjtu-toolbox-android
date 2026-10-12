package com.xjtu.toolbox.schedule

/**
 * 「这门课对应哪本教材」——**纯匹配，不取数**。
 *
 * 从 `:data` 的 `CourseLinks` 里按能力切出来的那一半：它只吃一份教材表与一个课名/课程号，
 * 不碰会话、不碰网络、不碰落盘（教材本身是 `ScheduleCache` 里那一份，按学期整表取回）。
 * 所以它属于共享层 —— 屏（`ScheduleScreen` 的学期栏与课程详情下钻）三端同一份判据。
 *
 * `CourseLinks` 那边留下的（`lmsCourseFor` / `attendanceIndex`）要 `SessionManager`、
 * `:data` 的 `LmsApi` 与 `AttendanceRecordStore`，一步都进不了 commonMain。
 */
object TextbookLinks {

    /**
     * 找这门课的教材。
     *
     * 课程号优先：它是教务系统里的主键，两边一致就是同一门课，不用跟课程名的各种
     * 写法（「大学物理」/「大学物理（一）」/「大学物理I」）较劲。只有拿不到课程号、
     * 或者报表里那一列是空的，才退回按名字模糊匹配。
     *
     * @param courseCode 课表侧的课程号，空串表示不可用
     */
    fun textbooksFor(
        courseName: String,
        all: List<TextbookItem>,
        courseCode: String = "",
    ): List<TextbookItem> {
        val code = courseCode.trim()
        if (code.isNotEmpty()) {
            // 课程号常带班号后缀（`MATH100101-05`），先精确、再取主段。
            val byCode = all.filter { it.courseCode.trim().equals(code, ignoreCase = true) }
                .ifEmpty {
                    val stem = code.substringBefore('-').trim()
                    if (stem.length < 4) emptyList()
                    else all.filter { it.courseCode.trim().substringBefore('-').equals(stem, ignoreCase = true) }
                }
            if (byCode.isNotEmpty()) return byCode.filter { it.hasSubstantiveTextbook }
        }
        val target = courseName.normalizedCourseName()
        if (target.isEmpty()) return emptyList()
        val exact = all.filter { it.courseName.normalizedCourseName() == target }
        if (exact.isNotEmpty()) return exact.filter { it.hasSubstantiveTextbook }
        return all.filter {
            val n = it.courseName.normalizedCourseName()
            n.isNotEmpty() && (n.contains(target) || target.contains(n))
        }.filter { it.hasSubstantiveTextbook }
    }
}

/**
 * 去掉空白和结尾括号后缀，让两个系统里同一门课的名字能对上。
 *
 * 与教材匹配同处一个能力面（都是「名字归一化」），所以跟着 [TextbookLinks] 一起进 `:core`：
 * `:data` 的 `CourseLinks.lmsCourseFor` 也用它，公共可见性就是为那一处留的。
 */
fun String.normalizedCourseName(): String =
    trim().replace(Regex("[\\s　]"), "")
        .replace(Regex("[（(][^）)]*[）)]$"), "")
