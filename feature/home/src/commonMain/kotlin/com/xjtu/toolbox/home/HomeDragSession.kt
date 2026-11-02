package com.xjtu.toolbox.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * 编辑态的拖拽会话：移动卡片、右下角缩放、组上下排序、从悬浮条拖新块，四种手势共用一份。
 *
 * 不跳变的三条根基：
 *
 * 1. **坐标只有一套**——页面根 Box 的局部坐标（`boundsInRoot` − [pageOrigin]）。手势节点
 *    在 `onGloballyPositioned` 里登记自己的页面局部位置，手指位置永远用「节点位置 +
 *    事件的节点相对坐标」**绝对合成**，不做差分累加：页面滚动、位置动画进行中，合成值
 *    都不受污染。
 * 2. **拖拽中不改数据**。预览布局由首页按会话状态实时推导（`derivedStateOf`），其它块
 *    实时弹簧让位；松手只是把预览固化为草稿——松手瞬间没有任何位置突变。
 * 3. **命中只回读一个基准**：组区容器的 [boardOrigin]/[boardWidth]。各组高度是数据的
 *    纯函数（格数 × 单元高 + 标题行），不需要逐组回读 bounds。
 */
@Stable
internal class HomeDragSession {

    /** 页面根 Box 在窗口里的位置：所有 `boundsInRoot` 先减它再存，统一成页面局部坐标。 */
    var pageOrigin by mutableStateOf(Offset.Zero)

    /** 组区容器（包着全部卡片组的那一层）的页面局部坐标与宽度：命中的唯一回读基准。 */
    var boardOrigin by mutableStateOf(Offset.Zero)
    var boardWidth by mutableStateOf(0f)

    /** 每块手势节点的页面局部左上角（`onGloballyPositioned` 逐块登记）：绝对合成手指位置用。 */
    val tileTops = mutableStateMapOf<String, Offset>()

    /** 每个组排序把手节点的页面局部顶边（同上）。 */
    val handleTops = mutableStateMapOf<String, Float>()

    // ── 卡片拖拽（含从悬浮条拖新块） ──

    /** 被拖块的 id（悬浮条拖出时是预览与落盘共用的新 id）；null = 没在拖卡片。 */
    var dragTileId by mutableStateOf<String?>(null)
        private set
    var dragRouteId by mutableStateOf<String?>(null)
        private set

    /** 从哪个组拖出来的；悬浮条新块为 null。 */
    var dragFromGroup by mutableStateOf<String?>(null)
        private set
    var dragFromPalette by mutableStateOf(false)
        private set

    /** 被拖块的尺寸（格）：画幽灵块与落点占位框用。 */
    var dragSizeCells by mutableStateOf(Offset(2f, 1f))
        private set

    /** 起手时这一块所在的数据格：拖拽中它的真身钉在这里画残影（节点静止，手势坐标才不漂）。 */
    var dragStartCell by mutableStateOf(Offset.Zero)
        private set

    /** 起手那一刻「手指 − 块左上角」（px）：之后幽灵块 = 手指 − 它，相对位置恒定。 */
    var grabOffset by mutableStateOf(Offset.Zero)
        private set

    /** 起手那一刻幽灵块的位置：松手落空时幽灵飞回这里再消失。 */
    var dragStartGhost by mutableStateOf(Offset.Zero)
        private set

    /** 手指当前的页面局部坐标（绝对合成，见类头注释）。 */
    var pointer by mutableStateOf(Offset.Zero)

    /** 当前认定的目标组（带滞回：手指还在它的扩张区间里就不切走）；null = 没有可落的组。 */
    var targetGroupId by mutableStateOf<String?>(null)

    /**
     * 松手结算中（幽灵吸附/回弹动画）：预览已固化、手指已抬起，
     * 命中重算与边缘自动滚动都该停下（[active] 因此不含这一段）。
     */
    var settling by mutableStateOf(false)
        private set

    /** 拖拽代次：每次 begin 递增。结算动画结束时比对，被新拖拽顶掉就不清场。 */
    var dragEpoch by mutableIntStateOf(0)
        private set

    val dragging: Boolean get() = dragRouteId != null

    /** 幽灵块左上角（页面局部坐标）：跟手阶段直接由手指推出。 */
    val ghostOrigin: Offset get() = pointer - grabOffset

    fun beginTileDrag(tile: HomeTile, groupId: String, tileTop: Offset, startPointer: Offset) {
        dragEpoch++
        settling = false
        // 多指互斥：另一根手指开始拖卡片时，进行中的组排序直接结束（让位动画归位）
        endReorder()
        dragTileId = tile.id
        dragRouteId = tile.routeId
        dragFromGroup = groupId
        dragFromPalette = false
        dragSizeCells = Offset(tile.w, tile.h)
        dragStartCell = Offset(tile.x, tile.y)
        grabOffset = startPointer - tileTop
        pointer = startPointer
        dragStartGhost = tileTop
        targetGroupId = groupId
    }

    /** 从悬浮条拖出一个新块：[tileId] 已按全局唯一规则分好，预览与落盘用同一个。 */
    fun beginPaletteDrag(
        tileId: String,
        routeId: String,
        w: Float,
        h: Float,
        startPointer: Offset,
        colUnit: Float,
        rowUnit: Float,
    ) {
        dragEpoch++
        settling = false
        endReorder()
        dragTileId = tileId
        dragRouteId = routeId
        dragFromGroup = null
        dragFromPalette = true
        dragSizeCells = Offset(w, h)
        // 悬浮条上按住的是一枚圆图标，幽灵块抓在中心最自然
        grabOffset = Offset(w * colUnit / 2f, h * rowUnit / 2f)
        pointer = startPointer
        dragStartGhost = startPointer - grabOffset
        targetGroupId = null
    }

    /** 落点固化后、清场之前：残影瞬移到落点（藏在幽灵下面），真块接管时才不会重弹一遍。 */
    fun snapDraggedTileTo(x: Float, y: Float) {
        dragStartCell = Offset(x, y)
    }

    /** 进入/退出松手结算（[epoch] 对不上说明已被新拖拽顶掉，不清场）。 */
    fun markSettling() {
        settling = true
    }

    fun clearSettling(epoch: Int) {
        if (dragEpoch == epoch) settling = false
    }

    fun endDrag() {
        dragTileId = null
        dragRouteId = null
        dragFromGroup = null
        dragFromPalette = false
        targetGroupId = null
    }

    // ── 缩放 ──

    var resizingId by mutableStateOf<String?>(null)
        private set

    /** 实时尺寸（格）：被缩那块直接跟手渲染（不弹簧），其它块按预览让位。 */
    var resizeCells by mutableStateOf(Offset.Zero)
        private set

    /** 起手补偿 = 实际尺寸 − 手指到左上角的距离：把手热区里按在哪一点都不跳变。 */
    private var resizeGrab = Offset.Zero

    val resizing: Boolean get() = resizingId != null

    fun beginResize(
        tileId: String,
        cells: Offset,
        tileTop: Offset,
        startPointer: Offset,
        colUnit: Float,
        rowUnit: Float,
    ) {
        resizingId = tileId
        resizeCells = cells
        resizeGrab = Offset(cells.x * colUnit, cells.y * rowUnit) - (startPointer - tileTop)
        pointer = startPointer
        settling = false
        endReorder()
    }

    /** 手指位置 → 新尺寸（格）：绝对合成，页面滚动时尺寸跟着手指走，松手才固化。 */
    fun updateResize(pointerNow: Offset, tileTop: Offset, colUnit: Float, rowUnit: Float, grid: Boolean) {
        val sizePx = pointerNow - tileTop + resizeGrab
        resizeCells = Offset(
            HomeGridMath.clampSpan(sizePx.x / colUnit, grid),
            HomeGridMath.clampSpan(sizePx.y / rowUnit, grid),
        )
        // 缩放与卡片拖拽互斥，共用 pointer：边缘自动滚动时按它补算尺寸
        pointer = pointerNow
    }

    fun endResize() {
        resizingId = null
    }

    // ── 组排序 ──

    var reorderGroupId by mutableStateOf<String?>(null)
        private set

    /** 起手时它在可见组列表里的下标（拖动中数据不变，下标不变）。 */
    var reorderFromIndex by mutableStateOf(-1)
        private set

    /** 手指的页面局部 y（绝对合成）。 */
    var reorderPointerY by mutableStateOf(0f)

    /** 起手时「手指 − 被拖组顶边」：跟手位移 = 手指 y − 它 − 组原顶。 */
    var reorderGrabY by mutableStateOf(0f)
        private set

    /** 组排序代次：每次 begin 递增，归位动画结束时比对用（同 [dragEpoch]）。 */
    var reorderEpoch by mutableIntStateOf(0)
        private set

    val reordering: Boolean get() = reorderGroupId != null

    /** 任何一种手势在进行中（自动滚动的触发条件）；松手结算不算。 */
    val active: Boolean get() = (dragging || resizing || reordering) && !settling

    fun beginReorder(groupId: String, fromIndex: Int, groupTop: Float, startPointerY: Float) {
        reorderEpoch++
        // 多指互斥：开始拖组排序时，进行中的卡片拖拽/缩放直接结束
        endDrag()
        endResize()
        settling = false
        reorderGroupId = groupId
        reorderFromIndex = fromIndex
        reorderGrabY = startPointerY - groupTop
        reorderPointerY = startPointerY
    }

    fun endReorder() {
        reorderGroupId = null
        reorderFromIndex = -1
    }

    /** 手势被系统抢走（取消）时的总兜底：清掉一切进行中的状态，不留卡住的幽灵块。 */
    fun abort() {
        endDrag()
        endResize()
        endReorder()
    }
}

/**
 * 卡片组区的几何表：每组多高、在哪里，全部由**数据**推出（格数 × 单元高 + 标题行），
 * 唯一回读的基准是组区容器的位置与宽度（[HomeDragSession.boardOrigin]/[boardWidth]）。
 *
 * 预览布局会让某些组变高变矮，但这里始终按**数据布局**算——命中区间在拖动中恒定，
 * 预览的让位动画才不会反过来搅动命中（那是振荡的源头）。
 */
internal class HomeBoardGeometry(
    /** 一格的宽（px）：`(组宽 + 缝隙) / 列数`。 */
    val colUnit: Float,
    /** 一格的高（px）：`单元高 + 缝隙`。 */
    val rowUnit: Float,
    /** 按渲染顺序的每组槽位。 */
    val slots: List<Slot>,
) {
    internal class Slot(
        val groupId: String,
        /** 组顶（含标题行）的页面局部 y。 */
        val top: Float,
        /** 网格区顶的页面局部 y。 */
        val gridTop: Float,
        /** 组总高（标题行 + 网格），不含组间距。 */
        val height: Float,
    )

    fun slot(groupId: String): Slot? = slots.firstOrNull { it.groupId == groupId }

    /**
     * 手指落在哪一组。组间空隙归上方组（向下延半个组间距）；[prefer] 是当前目标组——
     * 手指还在它的扩张区间里就保持它（滞回，边界上不来回切）。横向超出组区、或落进
     * 所有组之外的空白，返回 null（松手时幽灵回弹）。
     */
    fun groupAt(point: Offset, prefer: String?, halfGap: Float, left: Float, right: Float): String? {
        if (point.x < left || point.x > right) return null
        fun expanded(s: Slot) = point.y >= s.top - halfGap && point.y < s.top + s.height + halfGap
        if (prefer != null) {
            val s = slot(prefer)
            if (s != null && expanded(s)) return prefer
        }
        return slots.firstOrNull { expanded(it) }?.groupId
    }

    /** 手指换算成 [groupId] 组里的格坐标（不取整、不夹：交还给 placeInGroup 的 clamp）。 */
    fun cellAt(groupId: String, point: Offset, left: Float): Offset? {
        val s = slot(groupId) ?: return null
        return Offset((point.x - left) / colUnit, (point.y - s.gridTop) / rowUnit)
    }

    /** 落点格 → 幽灵块吸附动画的目标（该格的页面局部左上角）。 */
    fun cellOrigin(groupId: String, cellX: Float, cellY: Float, left: Float): Offset? {
        val s = slot(groupId) ?: return null
        return Offset(left + cellX * colUnit, s.gridTop + cellY * rowUnit)
    }

    /** 各组高度表（含组间距）：组排序纯函数 [HomeGridMath.reorderTargetIndex] 的输入。 */
    fun heights(gapPx: Float): List<Float> = slots.map { it.height + gapPx }
}
