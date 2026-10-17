package com.xjtu.toolbox.library

import kotlin.math.exp
import kotlin.math.hypot

/**
 * 座位好不好坐。学校只给座位矩形和状态，没有墙和走廊的数据，按座位之间的相对位置推：
 * - 某一侧再也没有座位：靠边，多半是墙或窗，没人从那边经过；
 * - 某一侧隔着一道明显的缝、缝那边还有座位：那道缝是过道，人来人往；
 * - 周围已经坐着的人越多越吵，按距离衰减，暂离的算半个。
 *
 * 长度都以座位的中位边长为单位，各区域图的像素比例不同也能比。
 */
object SeatComfort {

    data class Comfort(
        /** 0..1，越大越好坐。 */
        val score: Float,
        /** 靠边的侧数（0..4）。 */
        val wallSides: Int,
        /** 临过道的侧数（0..4）。 */
        val aisleSides: Int,
        /** 周围的人气，按距离衰减后的人数。 */
        val crowd: Float,
    ) {
        val label: String
            get() = buildList {
                when {
                    wallSides >= 2 -> add("角落")
                    wallSides == 1 -> add("靠边")
                }
                if (aisleSides > 0) add("临过道")
                add(
                    when {
                        crowd < 0.3f -> "周围没人"
                        crowd < 1.2f -> "周围人少"
                        crowd < 2.5f -> "周围有人"
                        else -> "周围挺满"
                    }
                )
            }.joinToString("、")
    }

    /** 缝宽超过这么多个座位边长就当过道。 */
    private const val AISLE_GAP = 0.9f
    /** 侧向找邻座时，垂直方向允许错开的量。 */
    private const val LANE_SLACK = 0.3f
    /** 人气的衰减距离。 */
    private const val CROWD_DECAY = 2.2f
    private const val CROWD_RANGE = 6f

    fun of(seats: List<PlanSeat>): Map<String, Comfort> {
        val valid = seats.filter { it.width > 0f && it.height > 0f }
        if (valid.isEmpty()) return emptyMap()
        val unit = valid.map { minOf(it.width, it.height) }.sorted()[valid.size / 2]
        return valid.associate { s -> s.seatId to comfortOf(s, valid, unit) }
    }

    /** 分数最高的几个空座，同分取靠前的。 */
    fun best(seats: List<PlanSeat>, comfort: Map<String, Comfort>, count: Int = 3): List<PlanSeat> =
        seats.filter { it.available && it.seatId in comfort }
            .sortedByDescending { comfort.getValue(it.seatId).score }
            .take(count)

    private fun occupiedWeight(status: Int) = when (status) {
        PlanSeat.BOOKED, PlanSeat.INSIDE -> 1f
        PlanSeat.LEAVE -> 0.5f
        else -> 0f
    }

    private fun comfortOf(s: PlanSeat, all: List<PlanSeat>, unit: Float): Comfort {
        var walls = 0
        var aisles = 0
        for (dir in 0 until 4) {
            val gap = gapToward(s, all, dir, unit)
            when {
                gap == null -> walls++
                gap > AISLE_GAP -> aisles++
            }
        }
        val cx = s.left + s.width / 2
        val cy = s.top + s.height / 2
        var crowd = 0f
        for (o in all) {
            if (o === s) continue
            val w = occupiedWeight(o.status)
            if (w == 0f) continue
            val d = hypot(o.left + o.width / 2 - cx, o.top + o.height / 2 - cy) / unit
            if (d <= CROWD_RANGE) crowd += w * exp(-d / CROWD_DECAY)
        }
        val score = 0.55f +
            0.17f * minOf(walls, 2) -
            0.14f * minOf(aisles, 2) -
            0.42f * (1f - exp(-crowd / 1.6f))
        return Comfort(score.coerceIn(0f, 1f), walls, aisles, crowd)
    }

    /**
     * [dir] 方向（0 左 1 右 2 上 3 下）上离 [s] 最近那个座位隔着的缝，以 [unit] 计；那边没有座位返回 null。
     * 只看和 [s] 在同一条「道」上的：垂直方向的投影要重叠。
     */
    private fun gapToward(s: PlanSeat, all: List<PlanSeat>, dir: Int, unit: Float): Float? {
        val slack = LANE_SLACK * unit
        var best: Float? = null
        for (o in all) {
            if (o === s) continue
            val gap = when (dir) {
                0, 1 -> {
                    if (o.top >= s.bottom + slack || o.bottom <= s.top - slack) continue
                    if (dir == 0) s.left - o.right else o.left - s.right
                }
                else -> {
                    if (o.left >= s.right + slack || o.right <= s.left - slack) continue
                    if (dir == 2) s.top - o.bottom else o.top - s.bottom
                }
            }
            // 在反方向或者和自己叠着的不算
            if (gap < -slack) continue
            val g = gap.coerceAtLeast(0f) / unit
            if (best == null || g < best) best = g
        }
        return best
    }
}
