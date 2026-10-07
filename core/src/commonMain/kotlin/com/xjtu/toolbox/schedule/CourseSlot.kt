package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.ScheduleRow

/**
 * 一节课在「周 × 星期 × 节次」网格里的位置 —— **跨端共享的课表模型**。
 *
 * 与 `:app` 现有 `ScheduleSlot` / `CourseItem` 的关系（照实说明，避免两套模型悄悄分叉）：
 *   - 那边是 Android 侧的历史形状：周次用 `weekBits` 位串、`location` 不区分教室与校区；
 *   - 这边用**展开后的周次列表** + 拆分后的 `room` / `campus`，因为 Web 端没有位串这套历史包袱，
 *     而位串正是交接文档点名要退役的东西（`SKZC` 位宽 16/18 不齐）。
 *   - 第 3 步搬 UI 时 `:app` 会收敛到这个模型上，届时 `weekBits` 退役。
 */
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

/**
 * `ZCMC` 周次文本 → 周集合。口径与 J1900 的 `jwxt2caldav.js::parseZcmc` **逐条对齐**
 * （那份是权威实现，两侧算出来的周次必须一模一样，否则日历与 App 会互相打脸）：
 *
 *   - 范围段：`1-16周`、`2-16周`
 *   - 单双周：`5-7周(单)`、`5-7周(双)`
 *   - **单周段**：`16周`、`1周,8周` —— 过往学期数据里真实存在，旧解析只认范围段会直接 die
 *   - 段间用逗号分隔，允许空格
 *
 * 与权威实现的一处**有意差异**：解析不了的段落**跳过而不是抛异常**。
 * 理由：这里跑在用户面前（Android/Web 同一份），一个畸形段落不该让整张课表白屏；
 * 服务端那份是离线任务，抛异常让人发现才是对的。
 */
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
