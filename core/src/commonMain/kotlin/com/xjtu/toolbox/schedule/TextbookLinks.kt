package com.xjtu.toolbox.schedule

/** 「这门课对应哪本教材」——纯匹配，不取数。 */
object TextbookLinks {

    /** 找这门课的教材。 */
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

/** 去掉空白和结尾括号后缀，让两个系统里同一门课的名字能对上。 */
fun String.normalizedCourseName(): String =
    trim().replace(Regex("[\\s　]"), "")
        .replace(Regex("[（(][^）)]*[）)]$"), "")
