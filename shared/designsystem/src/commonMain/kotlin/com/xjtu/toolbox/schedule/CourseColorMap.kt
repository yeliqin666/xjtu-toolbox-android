package com.xjtu.toolbox.schedule

import androidx.compose.ui.graphics.Color

/** 课程默认色与「一批课名 → 颜色」的分配。 */
val COURSE_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
    Color(0xFF00695C), Color(0xFF283593), Color(0xFF558B2F), Color(0xFF8E24AA),
    Color(0xFFD84315),
)

/** 课名的默认色：按课名稳定哈希取色。思源学堂那边只认识单门课，用的就是它。 */
fun defaultCourseColor(courseName: String): Color =
    COURSE_COLORS[(courseName.trim().hashCode() and Int.MAX_VALUE) % COURSE_COLORS.size]

/** 一批课程的「课名 → 颜色」表。 */
fun courseColorMap(names: Collection<String>): Map<String, Color> {
    val n = COURSE_COLORS.size
    val used = BooleanArray(n)
    val out = HashMap<String, Color>()
    for (name in names.distinct().sorted()) {
        val custom = CourseColors.of(name)
        if (custom != null) {
            out[name] = custom
            continue
        }
        val start = (name.trim().hashCode() and Int.MAX_VALUE) % n
        val i = (0 until n).map { (start + it) % n }.firstOrNull { !used[it] } ?: start
        used[i] = true
        out[name] = COURSE_COLORS[i]
    }
    return out
}

fun Map<String, Color>.colorOf(courseName: String): Color = this[courseName] ?: defaultCourseColor(courseName)
