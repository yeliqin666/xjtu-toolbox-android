package com.xjtu.toolbox.home

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 首页卡片组网格的纯几何计算：吸附、碰撞、挤开后的落位。
 *
 * 这里**没有任何 Compose 代码**（`:shared:model` 没开 Compose 编译器插件），
 * 拖动 / 缩放手势只负责把「手指落在哪一格」算出来交给这里，算法本身可以单测。
 *
 * 坐标系：每个卡片组横向固定 [COLUMNS] 列，纵向不限。一个组件的矩形写作
 * `(x, y, w, h)`，单位都是「格」，`x`/`y` 是左上角，`w`/`h` 是宽高（可以不是整数，
 * 自由拖拽模式就是靠这个）。
 *
 * 相邻两块**贴边不算重叠**：渲染时每块还会各自内缩半个 `Gap`，
 * 所以「格坐标上贴边」恰好落成视觉上的标准缝隙，不需要在判定里再加余量。
 */
object HomeGridMath {

    /** 每个卡片组的列数。改了要同步 `:feature:home` 的排版常量。 */
    const val COLUMNS = 4

    /**
     * 单块最小尺寸（格）：一块就是 1×1 的纯图标方块（§8）。
     *
     * 早先给到 0.7 是想要「半个格子」的过渡手感，但那样最小块既放不下图标也按不住；
     * 现在自由模式也以 1 为最小步进，只允许 1.0 以上的小数（1.5、2.3 这种连续尺寸）。
     */
    const val MIN_SPAN = 1f

    /** 单块最大尺寸（格）：需求写死的 4×4 上限。 */
    const val MAX_SPAN = 4f

    /** 判定重叠时的余量：贴在格线上相邻（或差一丝）不算重叠。 */
    private const val EPSILON = 0.001f

    /** 网格模式取整。 */
    fun snap(value: Float, grid: Boolean): Float = if (grid) value.roundToInt().toFloat() else value

    /** 尺寸夹进 [MIN_SPAN]..[MAX_SPAN]（网格模式下再取整）。 */
    fun clampSpan(value: Float, grid: Boolean): Float =
        snap(value.coerceIn(MIN_SPAN, MAX_SPAN), grid).coerceIn(MIN_SPAN, MAX_SPAN)

    /**
     * 把一块夹回组内：宽高先夹进合法区间，左上角夹进 `0..COLUMNS-w`；
     * `y` 只夹掉负数（组的纵向是不限长的）。
     */
    fun clampToBoard(tile: HomeTile, grid: Boolean): HomeTile {
        val w = clampSpan(tile.w, grid)
        val h = clampSpan(tile.h, grid)
        val x = snap(tile.x, grid).coerceIn(0f, (COLUMNS - w).coerceAtLeast(0f))
        val y = snap(tile.y, grid).coerceAtLeast(0f)
        return tile.copy(x = x, y = y, w = w, h = h)
    }

    /** 两个矩形是否交叠（贴边不算）。 */
    fun overlaps(a: HomeTile, b: HomeTile): Boolean =
        a.x < b.x + b.w - EPSILON &&
            b.x < a.x + a.w - EPSILON &&
            a.y < b.y + b.h - EPSILON &&
            b.y < a.y + a.h - EPSILON

    /** 这个位置放得下 [tile] 吗（不含它自己）。 */
    fun isFree(tile: HomeTile, others: List<HomeTile>): Boolean =
        others.none { it.id != tile.id && overlaps(it, tile) }

    private fun area(tile: HomeTile): Float = tile.w * tile.h

    /**
     * 组内拖拽的落位，返回这一组新的摆放（含 drag 自己）。
     *
     * 规则（需求 §4.3）：
     * - 只挤开**和落点直接重叠**的那几块，不做连锁位移；
     * - 被挤开的块尺寸 ≤ 拖拽块**原尺寸**，就搬到拖拽块原来的位置；
     * - 尺寸更大的，搬到本组最下方；
     * - 原来那个位置只让给一块（多块重叠时按体积从小到大挑，先到先得），其余的直接去最下方，
     *   免得两块挤到同一个坑里再连环挪。
     */
    fun placeInGroup(tiles: List<HomeTile>, moved: HomeTile, grid: Boolean): List<HomeTile> {
        val origin = tiles.firstOrNull { it.id == moved.id }
        val rest = tiles.filter { it.id != moved.id }
        val placed = clampToBoard(moved, grid)
        val hit = rest.filter { overlaps(it, placed) }
        if (hit.isEmpty()) return (rest + placed).sortInReadingOrder()

        // 工作副本里**同时放着拖拽块自己**：被挤开的块算「最下方」时不能压到它身上，
        // 算「原位置还能不能用」时也要把它的新位置算进去
        val work = (rest + placed).toMutableList()
        var originTaken = false
        hit.sortedBy { area(it) }.forEach { victim ->
            val index = work.indexOfFirst { it.id == victim.id }
            if (index < 0) return@forEach
            val others = work.filter { it.id != victim.id }
            val slot = when {
                origin == null -> null
                originTaken -> null
                area(victim) > area(origin) + EPSILON -> null
                !fits(victim, origin) -> null
                others.any { overlaps(it, origin) } -> null
                else -> {
                    originTaken = true
                    victim.copy(x = origin.x, y = origin.y)
                }
            }
            work[index] = slot ?: bottomSlot(others, victim)
        }
        return work.sortInReadingOrder()
    }

    /**
     * 跨组拖拽的落位：把 [moved] 放进目标组 [targetTiles]，返回目标组的新摆放。
     *
     * 硬性约束（需求 §4.4）：被挤开的块**绝不跨组**。原位置在别的组里，所以这里
     * 只有两种去向——本组的空位，或者本组最下方。
     */
    fun placeAcross(targetTiles: List<HomeTile>, moved: HomeTile, grid: Boolean): List<HomeTile> {
        val rest = targetTiles.filter { it.id != moved.id }
        val placed = clampToBoard(moved, grid)
        val hit = rest.filter { overlaps(it, placed) }
        if (hit.isEmpty()) return (rest + placed).sortInReadingOrder()

        val work = (rest + placed).toMutableList()
        hit.sortedBy { area(it) }.forEach { victim ->
            val index = work.indexOfFirst { it.id == victim.id }
            if (index < 0) return@forEach
            val others = work.filter { it.id != victim.id }
            val slot = firstFreeSlot(others, victim.w, victim.h)
                ?.let { (x, y) -> victim.copy(x = x, y = y) }
                ?: bottomSlot(others, victim)
            work[index] = slot
        }
        return work.sortInReadingOrder()
    }

    /** 组内缩放：和拖拽同一套挤开规则，`moved` 已经是新的宽高。 */
    fun resizeInGroup(tiles: List<HomeTile>, moved: HomeTile, grid: Boolean): List<HomeTile> =
        placeInGroup(tiles, moved, grid)

    /** 这一块装得下 [victim] 吗（原位置通常比被挤开的块大，但也可能更窄）。 */
    private fun fits(victim: HomeTile, slot: HomeTile): Boolean =
        victim.w <= slot.w + EPSILON && victim.h <= slot.h + EPSILON

    /**
     * 从上往下、从左往右扫第一个放得下 `w×h` 的空位；放不下返回 null。
     *
     * 扫的步长看块本身：整格尺寸按 1 格扫（网格模式的常态），带小数的按半格扫，
     * 否则自由模式留下的空档永远扫不到。
     */
    fun firstFreeSlot(tiles: List<HomeTile>, w: Float, h: Float): Pair<Float, Float>? {
        val step = if (w % 1f == 0f && h % 1f == 0f) 1f else 0.5f
        val bottom = contentBottom(tiles)
        var y = 0f
        while (y <= bottom + step) {
            var x = 0f
            while (x <= COLUMNS - w + EPSILON) {
                val probe = HomeTile(id = "", routeId = "", x = x, y = y, w = w, h = h)
                if (tiles.none { overlaps(it, probe) }) return x to y
                x += step
            }
            y += step
        }
        return null
    }

    /** 放到本组最下方（整行空出来），避开已有内容。 */
    fun bottomSlot(tiles: List<HomeTile>, tile: HomeTile): HomeTile {
        val others = tiles.filter { it.id != tile.id }
        var candidate = tile.copy(x = 0f, y = contentBottom(others))
        // 最下方那一行可能已经摆了别的东西（左侧留出的空档），继续往下挪到放得下为止
        var guard = 0
        while (others.any { overlaps(it, candidate) } && guard < 128) {
            candidate = candidate.copy(
                y = others.filter { overlaps(it, candidate) }.maxOf { it.y + it.h },
            )
            guard++
        }
        return candidate
    }

    /** 组里内容占到的底部（格）：高度算到这里就够了。 */
    fun contentBottom(tiles: List<HomeTile>): Float =
        tiles.maxOfOrNull { it.y + it.h } ?: 0f

    /**
     * 自由拖拽模式的「边界联动」：被拖的块离某个邻居的边只差 [tolerance] 格时，
     * 直接吸到那条边上——两条边一起动，不会出现差一丝放不进去的情况。
     *
     * 横向只和「上下有交叠」的邻居联动、纵向只和「左右有交叠」的邻居联动，
     * 否则会把块吸到屏幕另一边毫无关系的块旁边。
     */
    fun snapToNeighbors(moved: HomeTile, others: List<HomeTile>, tolerance: Float): HomeTile {
        if (tolerance <= 0f) return moved
        val sameRow = others.filter { verticalOverlap(it, moved) }
        val sameColumn = others.filter { horizontalOverlap(it, moved) }

        val xTargets = sameRow.flatMap { listOf(it.x + it.w, it.x - moved.w) }
        val yTargets = sameColumn.flatMap { listOf(it.y + it.h, it.y - moved.h) }
        val snapX = nearest(moved.x, xTargets, tolerance)
        val snapY = nearest(moved.y, yTargets, tolerance)
        return moved.copy(x = snapX ?: moved.x, y = snapY ?: moved.y)
    }

    private fun nearest(value: Float, candidates: List<Float>, tolerance: Float): Float? =
        candidates.filter { abs(it - value) <= tolerance }.minByOrNull { abs(it - value) }

    private fun verticalOverlap(a: HomeTile, b: HomeTile): Boolean =
        a.y < b.y + b.h - EPSILON && b.y < a.y + a.h - EPSILON

    private fun horizontalOverlap(a: HomeTile, b: HomeTile): Boolean =
        a.x < b.x + b.w - EPSILON && b.x < a.x + a.w - EPSILON

    /**
     * 排序只为了输出稳定（同一次拖拽的结果在任何端都一致）：
     * 先按行、再按列。渲染顺序另说，落盘顺序按这个走，diff 才不会乱跳。
     */
    private fun List<HomeTile>.sortInReadingOrder(): List<HomeTile> =
        sortedWith(compareBy({ it.y }, { it.x }, { it.id }))
}
