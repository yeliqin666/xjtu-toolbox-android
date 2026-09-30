package com.xjtu.toolbox.schedule

/**
 * 周视图网格的纵轴布局。
 *
 * 纵轴一行是一节课，或一段时段带：相邻两节间隔一小时以上的「午休」「晚休」，以及有条目落在
 * 第 1 节之前、末节之后时补的「早间」「夜间」。节间十来分钟的课间不占高度，落在里面的时刻贴到
 * 下一行的上沿。第 11 节平时几乎不排课，不画；有条目落到那里才加上。
 *
 * 每一行在两套作息下各有自己的起止（[times]）：冬令第 5 节是 14:00–14:50，夏令是 14:30–15:20。
 * 条目按**它那天**的作息换算真实起止（[CourseItem.clockMinutes]），再按那天作息的行起止落位，
 * 所以节次课总是正好盖住对应的行；5/1、10/1 所在那周两套作息并存，也各自对齐，左轴两套时间都标。
 *
 * 位置用「行刻度」：第 i 行 = `[i, i+1)`，小数部分 = 行内按钟点的比例。
 */
internal class WeekGridLayout(
    val rows: List<GridRow>,
    private val summerTimes: List<Pair<Int, Int>>,
    private val winterTimes: List<Pair<Int, Int>>,
    val slots: List<PlacedSlot>,
) {
    /** 各行在某套作息下的起止（距 00:00 的分钟）。 */
    fun times(summer: Boolean): List<Pair<Int, Int>> = if (summer) summerTimes else winterTimes

    /** 某套作息下某个钟点在纵轴上的位置（行刻度）。 */
    fun positionOf(minute: Int, summer: Boolean): Float = positionOf(times(summer), minute)

    /** 第 [index] 行上有没有条目。 */
    fun isOccupied(index: Int): Boolean = slots.any { it.start < index + 1 && it.end > index }

    /**
     * 第 [index] 行切成几段：被条目占着的（`true`）和空着的（`false`），用行内比例 `[0, 1]` 表示、按先后排。
     * 时段带据此只把占着的部分按时长画，空着的部分收成窄带。
     */
    fun pieces(index: Int): List<Piece> {
        val occupied = slots
            .mapNotNull { s ->
                val from = (s.start - index).coerceAtLeast(0f)
                val to = (s.end - index).coerceAtMost(1f)
                if (to > from) from to to else null
            }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Float, Float>>()) { merged, r ->
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, r.second)
                else merged += r
                merged
            }
        val out = mutableListOf<Piece>()
        var cursor = 0f
        occupied.forEach { (from, to) ->
            if (from > cursor) out += Piece(cursor, from, occupied = false)
            out += Piece(from, to, occupied = true)
            cursor = to
        }
        if (cursor < 1f) out += Piece(cursor, 1f, occupied = false)
        return out
    }

    data class Piece(val from: Float, val to: Float, val occupied: Boolean)
}

internal data class GridRow(
    /** 节次行的节号；时段带为 null。 */
    val section: Int?,
    /** 时段带的名字（午休、晚休、早间、夜间）。 */
    val label: String = "",
)

/** 一条落好位的条目，[start]、[end] 是行刻度。 */
internal data class PlacedSlot(val slot: ScheduleSlot, val start: Float, val end: Float)

/** 条目再短也按这么多分钟画：不至于缩成一条线点不到，冲突判定也按这个长度算，画出来重叠的一定算冲突。 */
internal const val MIN_SLOT_MINUTES = 20

/** 平时画到第几节。第 11 节（夏令 21:40–22:30）几乎不排课，白占一行只会让整周更长。 */
private const val GRID_LAST_SECTION = 10

/** 相邻两节间隔多久以上插一条时段带。 */
private const val REST_GAP_MINUTES = 60

/**
 * @param daySummer 星期几（1–7）那天按哪套作息。
 */
internal fun layoutWeekGrid(
    slots: List<ScheduleSlot>,
    daySummer: (dayOfWeek: Int) -> Boolean,
): WeekGridLayout {
    // 每条按它那天的作息算起止，太短的拉到最短长度（到 24:00 为止，往前补）
    val clocks = slots
        .filter { it.slotDayOfWeek in 1..7 }
        .map { slot ->
            val summer = daySummer(slot.slotDayOfWeek)
            val (rawStart, rawEnd) = slot.clockMinutes(summer)
            val end = maxOf(rawEnd, rawStart + MIN_SLOT_MINUTES).coerceAtMost(CourseItem.MINUTES_PER_DAY)
            Triple(slot, summer, minOf(rawStart, end - MIN_SLOT_MINUTES) to end)
        }
    val summerSections = sectionTimes(summer = true)
    val winterSections = sectionTimes(summer = false)
    fun sectionsOf(summer: Boolean) = if (summer) summerSections else winterSections

    // 第 11 节及以后：有条目在它开始之后才结束，才画
    val lastSection = summerSections.keys.filter { section ->
        section <= GRID_LAST_SECTION ||
            clocks.any { (_, summer, clock) -> clock.second > sectionsOf(summer).getValue(section).first }
    }.max()
    val sections = (summerSections.keys.min()..lastSection).toList()
    val earliest = clocks.minOfOrNull { it.third.first }
    val latest = clocks.maxOfOrNull { it.third.second }
    val hasEarly = clocks.any { (_, summer, clock) -> clock.first < sectionsOf(summer).getValue(sections.first()).first }
    val hasLate = clocks.any { (_, summer, clock) -> clock.second > sectionsOf(summer).getValue(lastSection).second }

    // 行结构两套作息共用（两套作息的午休、晚休都在第 4/5、第 8/9 节之间），起止各算各的
    val rows = mutableListOf<GridRow>()
    val summerTimes = mutableListOf<Pair<Int, Int>>()
    val winterTimes = mutableListOf<Pair<Int, Int>>()
    fun add(row: GridRow, summer: Pair<Int, Int>, winter: Pair<Int, Int>) {
        rows += row; summerTimes += summer; winterTimes += winter
    }
    if (hasEarly) {
        val first = sections.first()
        add(GridRow(null, "早间"), earliest!! to summerSections.getValue(first).first, earliest to winterSections.getValue(first).first)
    }
    sections.forEachIndexed { i, section ->
        if (i > 0) {
            val prev = sections[i - 1]
            val summerGap = summerSections.getValue(prev).second to summerSections.getValue(section).first
            val winterGap = winterSections.getValue(prev).second to winterSections.getValue(section).first
            if (summerGap.second - summerGap.first >= REST_GAP_MINUTES) {
                add(GridRow(null, if (summerGap.second < 16 * 60) "午休" else "晚休"), summerGap, winterGap)
            }
        }
        add(GridRow(section), summerSections.getValue(section), winterSections.getValue(section))
    }
    if (hasLate) {
        val summerEnd = summerSections.getValue(lastSection).second
        val winterEnd = winterSections.getValue(lastSection).second
        add(GridRow(null, "夜间"), summerEnd to maxOf(summerEnd, latest!!), winterEnd to maxOf(winterEnd, latest))
    }

    val placed = clocks.map { (slot, summer, clock) ->
        val times = if (summer) summerTimes else winterTimes
        PlacedSlot(slot, positionOf(times, clock.first), positionOf(times, clock.second))
    }
    return WeekGridLayout(rows, summerTimes, winterTimes, placed)
}

/** 同一天里时间上重叠（传递闭包）的一组条目，网格上画成一个可左右翻的格子。 */
internal data class ConflictGroup(
    val slots: List<PlacedSlot>,
    val dayOfWeek: Int,
    val start: Float,
    val end: Float,
)

internal fun buildConflictGroups(slots: List<PlacedSlot>): List<ConflictGroup> =
    slots.groupBy { it.slot.slotDayOfWeek }.flatMap { (day, daySlots) ->
        // 按开始排好，一路合并：和当前组的下沿重叠就并进去，否则另起一组
        val groups = mutableListOf<MutableList<PlacedSlot>>()
        var groupEnd = Float.NEGATIVE_INFINITY
        daySlots.sortedWith(compareBy({ it.start }, { it.end })).forEach { s ->
            if (groups.isNotEmpty() && s.start < groupEnd) {
                groups.last() += s
                groupEnd = maxOf(groupEnd, s.end)
            } else {
                groups += mutableListOf(s)
                groupEnd = s.end
            }
        }
        groups.map { g -> ConflictGroup(g, day, g.minOf { it.start }, g.maxOf { it.end }) }
    }

/** 起止钟点。网格只接 [CourseItem]；别的实现按节次查作息表。 */
private fun ScheduleSlot.clockMinutes(summer: Boolean): Pair<Int, Int> =
    (this as? CourseItem ?: CourseItem(startSection = slotStartSection, endSection = slotEndSection))
        .clockMinutes(summer)

private fun sectionTimes(summer: Boolean): Map<Int, Pair<Int, Int>> =
    XjtuTime.getAllTimes(summer).associate { (section, t) ->
        section to (t.start.hour * 60 + t.start.minute to t.end.hour * 60 + t.end.minute)
    }

private fun positionOf(times: List<Pair<Int, Int>>, minute: Int): Float {
    times.forEachIndexed { i, (start, end) ->
        // 落在这一行之前的空档（课间、第 1 节之前）：贴到这一行上沿
        if (minute < start) return i.toFloat()
        if (minute <= end) return i + (minute - start).toFloat() / (end - start).coerceAtLeast(1)
    }
    return times.size.toFloat()
}
