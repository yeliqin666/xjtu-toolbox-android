package com.xjtu.toolbox.schedule

import androidx.compose.ui.graphics.Color

/**
 * 课程默认色与「一批课名 → 颜色」的分配。
 *
 * 从 :app 的 `ScheduleComponents.kt` 搬进 commonMain：这一簇是**纯逻辑**（色板 + 课名稳定哈希 +
 * 撞色顺延），只读一次用户自定义色（[CourseColors.of]），跟 UI 没有关系。搬过来之后
 * 「课表 / 思源学堂 / 桌面小部件 / Web 端」四处用的是同一份色板与同一套哈希 —— 这正是搬它的理由，
 * 而 :app 的同名声明已删。
 *
 * 搬迁时逻辑一行未动，只有可见性从 :app 的顶层默认（public）保持 public。
 */
val COURSE_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
    Color(0xFF00695C), Color(0xFF283593), Color(0xFF558B2F), Color(0xFF8E24AA),
    Color(0xFFD84315),
)

/** 课名的默认色：按课名稳定哈希取色。思源学堂那边只认识单门课，用的就是它。 */
fun defaultCourseColor(courseName: String): Color =
    COURSE_COLORS[(courseName.trim().hashCode() and Int.MAX_VALUE) % COURSE_COLORS.size]

/**
 * 一批课程的「课名 → 颜色」表。用户改过的颜色优先；其余从各自哈希位置起取默认色，
 * 撞了就顺延到下一个空位，本批内不重复（超过 [COURSE_COLORS] 的数量才会重复）。
 * 没撞色时与 [defaultCourseColor] 一致，所以课表和思源学堂里同一门课通常同色。
 */
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
