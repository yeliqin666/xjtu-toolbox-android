package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.ScheduleRow

/** 一节课在「周 × 星期 × 节次」网格里的位置 —— 跨端共享的课表模型。 */
data class CourseSlot(
    val courseName: String = "",
    val teacher: String = "",
    /** 教室，来自上游 `JASMC`，形如 `外文楼A-612`。 */
    val room: String = "",
    /** 校区。上游课表行里没有独立字段，留空由调用方决定要不要补。 */
    val campus: String = "",
    /** 星期，1=周一 … 7=周日（上游 `SKXQ`）。 */
    val dayOfWeek: Int = 0,
    /** 起止节次（上游 `KSJC` / `JSJC`），1..11。 */
    val startSection: Int = 0,
    val endSection: Int = 0,
    /** 展开后的周次（升序去重），由 [parseWeeksText] 从 `ZCMC` 文本得到。 */
    val weeks: List<Int> = emptyList(),
) : ScheduleSlot {
    // 直接满足共享周视图布局（[layoutWeekGrid]）的入参接口：`slotLocation` 只给教室
    //（校区是展示用的第二信息，布局不关心）。:app 的 `CourseItem` 早就实现了同一个接口。
    override val slotName get() = courseName
    override val slotLocation get() = room
    override val slotDayOfWeek get() = dayOfWeek
    override val slotStartSection get() = startSection
    override val slotEndSection get() = endSection
}

/** `ZCMC` 周次文本 → 周集合。 */
fun parseWeeksText(zcmc: String?): List<Int> {
    if (zcmc.isNullOrBlank()) return emptyList()
    val out = mutableSetOf<Int>()
    val seg = Regex("^(\\d+)(?:-(\\d+))?周(?:\\(([单双])\\))?$")
    for (part in zcmc.replace(Regex("\\s+"), "").split(',')) {
        if (part.isEmpty()) continue
        val m = seg.find(part) ?: continue
        val start = m.groupValues[1].toIntOrNull() ?: continue
        val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
        val parity = m.groupValues[3]
        for (w in start..end) {
            if (parity == "单" && w % 2 == 0) continue
            if (parity == "双" && w % 2 == 1) continue
            out.add(w)
        }
    }
    return out.sorted()
}

/** campus-api 的原始 47 列里的一行 → 共享课表模型。 */
fun ScheduleRow.toCourseSlot(): CourseSlot = CourseSlot(
    courseName = courseName.orEmpty(),
    teacher = teacher.orEmpty(),
    room = classroom.orEmpty(),
    campus = "",
    dayOfWeek = dayOfWeek?.toIntOrNull() ?: 0,
    startSection = startSection?.toIntOrNull() ?: 0,
    endSection = endSection?.toIntOrNull() ?: 0,
    weeks = parseWeeksText(weeksText),
)
