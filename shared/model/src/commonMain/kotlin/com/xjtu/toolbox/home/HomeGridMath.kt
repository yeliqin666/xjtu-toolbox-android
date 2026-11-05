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

    /**
     * 组内拖拽的落位，返回这一组新的摆放（含 drag 自己）。
     *
     * 网格模式是**流式重排**（需求 §4.3）：只动「和落点直接重叠」的那几块——把它们按原
     * 阅读顺序依次从布局里摘除，各自用 [firstFreeSlot] 重新插到**离自己最近**的空位。
     * 「交换」是这个规则的特例而不是分支：被挤走的块重插时，拖拽块留下的原槽位恰好是
     * 空位，就近落进去就是交换。没有任何块会被「扔到最下方」，每一步位移都最小、可预期，
     * 同一落点反复计算结果不变（预览幂等）。
     *
     * [maxRows] 是**这一组的行数预算**：拖动期间传起手快照的行数，落位就会被夹在预算里，
     * 组的高度因此整场拖拽恒定。这是「落点不飘」的物理保证——命中区间由快照算出，渲染
     * 也由快照的高度排布，两者永远同一套；组一变高，下面的组就被推下去，而命中还按快照
     * 的旧位置算，手指与格子的对应关系当场失效（旧实现振荡的根源）。预算不够时宁可让
     * 个别块临时溢出（罕见，松手压缩后自愈），也不允许它们重叠。传 null = 不限行数
     * （松手那一次，以及单测）。
     *
     * 自由模式（网格关闭）是**联动压缩**（需求 §26）：被压到的邻居优先往某一侧收拢边界
     * 让出空间，收拢到最小尺寸还不够才落本组最下方。
     */
    fun placeInGroup(tiles: List<HomeTile>, moved: HomeTile, grid: Boolean, maxRows: Float? = null): List<HomeTile> {
        val rest = tiles.filter { it.id != moved.id }
        val placed = clampToRows(clampToBoard(moved, grid), maxRows)
        val hit = rest.filter { overlaps(it, placed) }
        if (hit.isEmpty()) return (rest + placed).sortInReadingOrder()

        // 自由模式：被压到的邻居优先「联动压缩」——往某一侧收拢边界让出空间，而不是直接
        // 挤走；收拢到最小尺寸还是摆不下，才把它挤到本组最下方（需求 §26 补充）
        if (!grid) {
            val work = rest.toMutableList()
            hit.forEachReadingOrder { victim ->
                val index = work.indexOfFirst { it.id == victim.id }
                if (index < 0) return@forEachReadingOrder
                val others = work.filter { it.id != victim.id } + placed
                work[index] = compressToAvoid(victim, placed, others) ?: bottomSlot(others, victim)
            }
            return (work + placed).sortInReadingOrder()
        }

        // 网格模式：工作副本里**始终放着拖拽块自己**，被挤开的块找空位时不能压到它身上
        val work = (rest + placed).toMutableList()
        work.reflow(hit, maxRows)
        return work.sortInReadingOrder()
    }

    /**
     * 跨组拖拽的落位：把 [moved] 放进目标组 [targetTiles]，返回目标组的新摆放。
     *
     * 硬性约束（需求 §4.4）：被挤开的块**绝不跨组**。原位置在别的组里，所以这里
     * 只有两种去向——本组的空位，或者本组最下方（空位实在没有时的兜底）。
     * [maxRows] 同 [placeInGroup]：拖动期间传目标组的预算行数，组高才恒定。
     */
    fun placeAcross(targetTiles: List<HomeTile>, moved: HomeTile, grid: Boolean, maxRows: Float? = null): List<HomeTile> {
        val rest = targetTiles.filter { it.id != moved.id }
        val placed = clampToRows(clampToBoard(moved, grid), maxRows)
        val hit = rest.filter { overlaps(it, placed) }
        if (hit.isEmpty()) return (rest + placed).sortInReadingOrder()

        val work = (rest + placed).toMutableList()
        work.reflow(hit, maxRows)
        return work.sortInReadingOrder()
    }

    /**
     * 把一块夹进行数预算：`y + h <= maxRows`（`y >= 0` 由 [clampToBoard] 保证）。
     *
     * 预算比这一块还矮（往一个 1 行的组里拖 2 行高的块）时夹无可夹，原样返回——
     * 那种组本来也装不下，让它临时长高，比把块硬压成 1 行或干脆丢掉都诚实。
     */
    private fun clampToRows(tile: HomeTile, maxRows: Float?): HomeTile {
        if (maxRows == null || tile.h >= maxRows) return tile
        val y = tile.y.coerceAtMost(maxRows - tile.h)
        return if (y == tile.y) tile else tile.copy(y = y)
    }

    /**
     * 流式重排：把 [hit] 里被直接压到的块按**原阅读顺序**依次摘除，各自重插到
     * 最近的空位（[firstFreeSlot] 在 [maxRows] 预算内扫不到才落最下方兜底）。
     * 先重插的块先占位，后面的块自然避开它——连锁反应以「一个空位传一个块」的方式收敛，
     * 不会雪崩。
     */
    private fun MutableList<HomeTile>.reflow(hit: List<HomeTile>, maxRows: Float?) {
        hit.forEachReadingOrder { victim ->
            val index = indexOfFirst { it.id == victim.id }
            if (index < 0) return@forEachReadingOrder
            removeAt(index)
            val slot = firstFreeSlot(this, victim.w, victim.h, maxRows)
            add(if (slot != null) victim.copy(x = slot.first, y = slot.second) else bottomSlot(this, victim))
        }
    }

    private inline fun List<HomeTile>.forEachReadingOrder(block: (HomeTile) -> Unit) =
        sortedWith(compareBy({ it.y }, { it.x }, { it.id })).forEach(block)

    /** 组内缩放：和拖拽同一套挤开规则，`moved` 已经是新的宽高。[maxRows] 同 [placeInGroup]。 */
    fun resizeInGroup(tiles: List<HomeTile>, moved: HomeTile, grid: Boolean, maxRows: Float? = null): List<HomeTile> =
        placeInGroup(tiles, moved, grid, maxRows)

    /**
     * 自由模式的「联动压缩」：被 [placed] 压到的 [victim] 不直接挤走，而是往某一侧收拢
     * 边界让出空间。四种缩法里挑「面积损失最小」且缩完不与 [placed]、不与 [others] 重叠、
     * 不小于最小尺寸的那一版；找不到（被完全盖住、或一缩就和别人撞）就返回 null，
     * 调用方把它挤到本组最下方（需求 §26：到最小不能再小时才允许挤掉）。
     */
    private fun compressToAvoid(victim: HomeTile, placed: HomeTile, others: List<HomeTile>): HomeTile? {
        val candidates = mutableListOf<HomeTile>()
        // placed 从左侧插进 victim：victim 左边界收拢到 placed 右边界
        if (placed.x + placed.w > victim.x && placed.x + placed.w < victim.x + victim.w) {
            val nx = placed.x + placed.w
            candidates += victim.copy(x = nx, w = victim.x + victim.w - nx)
        }
        // placed 从右侧插进 victim：victim 右边界收拢到 placed 左边界
        if (placed.x > victim.x && placed.x < victim.x + victim.w) {
            candidates += victim.copy(w = placed.x - victim.x)
        }
        // placed 从上侧插进 victim：victim 上边界收拢到 placed 下边界
        if (placed.y + placed.h > victim.y && placed.y + placed.h < victim.y + victim.h) {
            val ny = placed.y + placed.h
            candidates += victim.copy(y = ny, h = victim.y + victim.h - ny)
        }
        // placed 从下侧插进 victim：victim 下边界收拢到 placed 上边界
        if (placed.y > victim.y && placed.y < victim.y + victim.h) {
            candidates += victim.copy(h = placed.y - victim.y)
        }
        return candidates
            .filter { it.w >= MIN_SPAN - EPSILON && it.h >= MIN_SPAN - EPSILON }
            .filter { cand -> !overlaps(cand, placed) && others.none { overlaps(it, cand) } }
            .minByOrNull { victim.area - it.area }
    }

    /**
     * 把「全空行」删掉：拖走 / 缩小之后中间空出一整行时，下面的块整体上移补上
     * （需求 §4.3 补充：后面所有卡片动画跟上来）。只服务网格模式——自由模式允许留空隙。
     * 所有块统一下移，相对顺序与相对位置不变。
     *
     * **一块也要压**：以前这里有个 `size < 2` 的短路，结果「两块一组、把上面那块拖走」之后
     * 剩下的那块孤零零停在第二行，上面那行永远空着（看着就是「空行没被收掉」）。
     */
    fun compactEmptyRows(tiles: List<HomeTile>): List<HomeTile> {
        if (tiles.isEmpty()) return tiles
        // 末行下标 = 内容底 − 1：`contentBottom` 是「底边」，直接当行号会多算一行幽灵行
        // （那一行永远算空，于是「没有空行」也判成有、每帧白建一份新 List）
        val rows = contentBottom(tiles).toInt()
        if (rows < 1) return tiles
        // 每个整数行有没有块占据（块可能跨行，整段都算占）
        val occupied = BooleanArray(rows)
        tiles.forEach { t ->
            val from = t.y.toInt().coerceAtLeast(0)
            val to = (t.y + t.h).toInt()
            for (y in from until to) if (y < rows) occupied[y] = true
        }
        // 每行之上有多少个全空行，就是要上移的格数
        var shift = 0
        val shiftAt = IntArray(rows)
        for (y in 0 until rows) {
            if (!occupied[y]) shift++
            shiftAt[y] = shift
        }
        if (shift == 0) return tiles
        return tiles.map { t ->
            t.copy(y = (t.y - shiftAt[t.y.toInt().coerceIn(0, rows - 1)]).coerceAtLeast(0f))
        }
    }

    /**
     * 从上往下、从左往右扫第一个放得下 `w×h` 的空位；扫到 [maxRows] 还没找到就返回 null。
     *
     * 扫的步长看块本身：整格尺寸按 1 格扫（网格模式的常态），带小数的按半格扫，
     * 否则自由模式留下的空档永远扫不到。
     *
     * [maxRows] 是**必须**的边界：这个函数会在拖动中被逐帧调用，没有上界就等于每帧
     * 往下扫一片无界的网格（`bottom` 还会随插入的块变大），既慢又会把块扔到看不见的
     * 地方去。传 null 表示不设上界（只有 [bottomSlot] 那种「一定要放下」的兜底才这么用）。
     */
    fun firstFreeSlot(tiles: List<HomeTile>, w: Float, h: Float, maxRows: Float? = null): Pair<Float, Float>? {
        val step = if (w % 1f == 0f && h % 1f == 0f) 1f else 0.5f
        val bottom = contentBottom(tiles)
        val limit = if (maxRows == null) bottom + step else minOf(bottom, maxRows - h)
        var y = 0f
        while (y <= limit + EPSILON) {
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

    // ── 卡片组上下排序的几何 ──

    /**
     * 组排序：被拖组跟手滑动了 [dragOffset] 像素（向下为正）之后，它应该落在哪个下标。
     *
     * [heights] 是**起手那一刻**的顺序下每一组占的高度（含组与组之间的间距）。判定用
     * 「被拖组的中心越过相邻组的中线」：基准顺序在整次拖拽里恒定（见
     * `HomeDragSession.base`），所以中线是静止的，来回各要越过一次中线才会来回换序——
     * 这就是天然的滞回，不会在边界上抖。返回的下标夹在合法范围内。
     *
     * 结果是纯函数：同一个 [dragOffset] 反复算永远得到同一个下标，所以拖动中每一帧
     * 都拿它重算并落进草稿，也不会因为「上一帧已经换过序」而算出别的结果。
     */
    fun reorderTargetIndex(heights: List<Float>, fromIndex: Int, dragOffset: Float): Int {
        if (fromIndex !in heights.indices) return fromIndex
        // 各组顶边（原坐标系）与被拖组中心
        var selfTop = 0f
        for (i in 0 until fromIndex) selfTop += heights[i]
        val center = selfTop + heights[fromIndex] / 2f + dragOffset

        var target = fromIndex
        // 往下：中心越过下一组的中线就再往后挪一位
        while (target < heights.lastIndex) {
            var nextTop = 0f
            for (i in 0..target) nextTop += heights[i]
            if (center > nextTop + heights[target + 1] / 2f) target++ else break
        }
        // 往上：中心越过上一组的中线就再往前挪一位
        while (target > 0) {
            var prevTop = 0f
            for (i in 0 until target - 1) prevTop += heights[i]
            if (center < prevTop + heights[target - 1] / 2f) target-- else break
        }
        return target
    }

    /**
     * 组排序的让位表：被拖组从 [fromIndex] 挪向 [targetIndex] 时，**其它组**各自需要在
     * 视觉上平移多少（px，向下为正），才能始终堆叠不留空。被拖组自己不在表里——它跟手。
     *
     * 规则：被拖组抽走后，夹在 [targetIndex] 与 [fromIndex] 之间的组往它腾出来的那一侧
     * 平移「被拖组高度」。和 [reorderTargetIndex] 搭配，松手时按目标下标落盘：那些组的
     * 新布局位置恰好等于「旧位置 + 让位偏移」，所以偏移归零的那一刻视觉连续、不跳。
     */
    fun reorderShifts(heights: List<Float>, fromIndex: Int, targetIndex: Int): Map<Int, Float> {
        if (fromIndex !in heights.indices || fromIndex == targetIndex) return emptyMap()
        val self = heights[fromIndex]
        val shifts = mutableMapOf<Int, Float>()
        if (targetIndex > fromIndex) {
            // 往下挪：中间的组整体上移一个被拖组的高度
            for (i in fromIndex + 1..targetIndex) shifts[i] = -self
        } else {
            for (i in targetIndex until fromIndex) shifts[i] = self
        }
        return shifts
    }
}
