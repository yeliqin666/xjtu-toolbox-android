package com.xjtu.toolbox.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow

/** 编辑态里正在进行的手势种类。一次只有一种；[HomeDragMode.IDLE] = 没有手势。 */
internal enum class HomeDragMode { IDLE, TILE, RESIZE, GROUP, PALETTE }

/**
 * 按下的落点归谁：手势层据此决定「接管」还是「放行」。
 *
 * 放行（[Pass]）的那些位置——移除按钮、删除组按钮、组名、悬浮条——下面还有真的点击
 * 或长按节点在等，手势层一旦吃掉 down，它们就永远点不着了。
 */
internal sealed interface HomeHit {
    /** 卡片体：拖过 slop = 移动这一块。 */
    data class Tile(val tileId: String, val groupId: String) : HomeHit

    /** 卡片右下角把手：拖过 slop = 缩放这一块。 */
    data class Resize(val tileId: String, val groupId: String) : HomeHit

    /** 组标题行左侧的排序把手：拖过 slop = 上下排这一组。 */
    data class GroupHandle(val groupId: String, val index: Int) : HomeHit

    /** 不归手势层管，放行给下层节点。 */
    data object Pass : HomeHit
}

/**
 * 编辑态的拖拽会话。四种手势（移动卡片、缩放、组排序、从悬浮条拖新块）共用这一份。
 *
 * 四条根基，缺一条就会回到「拖不动 / 落点飘 / 松手跳」：
 *
 * 1. **手势只挂在一个常驻、不滚动的层上**（`Modifier.homeDragLayer`，挂在页面根 Box
 *    自己的修饰符链上——不是盖在上面的兄弟覆盖层，兄弟覆盖层带 pointerInput 会独占整条
 *    命中路径，把下面的滚动列 / 顶栏 / 卡片全挡掉。同 AOSP Launcher3 的
 *    `DragLayer`）。它给的 [pointer] 就是**视口坐标**——`PointerInputChange.position`
 *    拿来直接用，不需要 `boundsInRoot()`、不需要逐块登记位置、不做差分累加。
 *    卡片节点自己**一个拖拽手势都不挂**，所以「被拖的块换到别的组会不会让手势协程被
 *    销毁」这个问题从根上不存在——它不再需要被钉死在源组画残影。
 * 2. **命中与几何只认起手快照 [base]**：组高、格换算、块的矩形全部由 [base] 这一份
 *    数据推出，整次拖拽里恒定。渲染用的是实时布局（每帧重算），两者能解耦是因为
 *    **渲染不需要几何表**——块在组内的位置由「数据格 × rowUnit」算出，组在页面里的位置
 *    交给 Column 自己排。几何与渲染因此不可能不一致（旧实现两套高度对不上，才要在
 *    松手时补算一次 `settledGeometry`）。
 * 3. **几何一律在「板内坐标」，手指坐标只做一次减法**：几何表以组区容器左上角为原点
 *    （`left = top = 0`），是**纯数据**，不含任何测量量；手指板坐标 = [pointer] −
 *    [boardOrigin]。[boardOrigin] 由组区容器的 `onGloballyPositioned` 每帧刷新，随滚动
 *    一起动，所以自动滚动时手指不动也能正确换组、缩放尺寸也会自然跟着变大——不需要
 *    任何「按预估位置补算」的特例，也不需要在坐标里手工掺 `scrollY`。
 * 4. **布局是幂等纯函数**：每帧 `layout = f(base, 手指板坐标)` 从快照重算，绝不
 *    `layout = f(layout, delta)` 累积。同一个手指位置反复算永远得到同一个布局，拖多久
 *    都不漂；命中 → 落点 → 布局之间也不存在反馈回路（旧实现振荡的根源）。
 *
 * 回读的几何基准只有组区容器自己的位置与宽度（[boardOrigin]/[boardWidth]），
 * 它长在**从不做动画**的节点上，所以永远准。旧实现把**每块弹簧动画的中间帧位置**当
 * 基准还自称「绝对合成，动画污染不了」，那恰恰被自己的动画污染了——落点飘就是这么来的。
 */
@Stable
internal class HomeDragSession {

    var mode by mutableStateOf(HomeDragMode.IDLE)
        private set

    /**
     * 手指的**页面根局部**坐标（手势层给的，手势层铺在页面根 Box 上）。
     * 板内坐标 = [pointer] − [boardOrigin]，由首页/CursorLayer 现算。
     */
    var pointer by mutableStateOf(Offset.Zero)

    val active: Boolean get() = mode != HomeDragMode.IDLE

    /** 拖的是卡片（含从悬浮条拖出的新块）：落点与接收高亮按卡片那一套走。 */
    val draggingTile: Boolean get() = mode == HomeDragMode.TILE || mode == HomeDragMode.PALETTE

    // ── 组区容器的几何基准 ──

    /**
     * 组区容器的左上角，在**页面根局部坐标**里（每帧由 `onGloballyPositioned` 刷新）。
     *
     * 它随滚动移动——这正是要的：`手指板坐标 = 手指页面坐标 − boardOrigin`，页面滚到哪
     * 映射都自动跟上，**不需要再手工掺 scrollY**。旧实现存
     * `boundsInRoot().top − pageOrigin.y + scrollY` 那种「内容坐标」，那条链任何一环时序
     * 不对（pageOrigin 尚未回填、scrollY 差一帧）都会让整份几何表整体偏移，表现就是
     * 「一拖就跑到上方老远」。
     */
    var boardOrigin by mutableStateOf(Offset.Zero)
    var boardWidth by mutableFloatStateOf(0f)

    /**
     * 页面根 Box 与组区容器的**活坐标对象**（不是回填的数值）。
     *
     * 起手那一刻用 [refreshBoardFromCoordinates] 现算原点：`LayoutCoordinates` 是活对象，
     * 读的时候就是当前值，所以页面滚到哪都不会拿到过期位置。之前把 `onGloballyPositioned`
     * 回填的 Offset 当基准存着，页面一滚动它就停在旧值上——命中全部落空（按住没反应）、
     * 抓取点偏几百 dp（一拖就跳到远处），都是这一个原因。
     */
    var rootCoords: LayoutCoordinates? = null
    var boardCoords: LayoutCoordinates? = null

    /** 各块最近一次布局的活坐标（普通 map，不进组合）。起手时用它定位抓取点——这是绝对真相。 */
    val tileCoords = mutableMapOf<String, LayoutCoordinates>()

    /**
     * 起手时某块的页面局部左上角（**实测**活坐标）。
     *
     * 为什么用它而不是几何推算：起手那一瞬组区原点 [boardOrigin] 可能还停留在上一帧的旧值
     * （容器位置没变则回调不触发），几何算出来的 origin 会整体偏移，幽灵于是「按下瞬间上跳」。
     * 实测块位置永远不会。null = 还没量到（调用方回退几何推算）。
     */
    fun tileTopLeft(tileId: String): Offset? {
        val r = rootCoords ?: return null
        val c = tileCoords[tileId] ?: return null
        if (!r.isAttached || !c.isAttached) return null
        // boundsInWindow 是屏幕绝对位置，减根位置换成页面局部坐标——与抓取点的手指坐标同空间
        return c.boundsInWindow().topLeft - r.boundsInWindow().topLeft
    }

    /** 【临时诊断】上一次手势收尾的路径；定位完连同 HOME_DRAG_DEBUG 一起删。 */
    var lastEvent by mutableStateOf("-")

    /**
     * 把 [boardOrigin] / [boardWidth] 刷成**此刻**的值。
     *
     * 用 `boundsInWindow`（屏幕上的绝对位置，滚动与动画都如实反映）两两相减换成页面局部坐标，
     * 不用 `localPositionOf`——后者对「滚动容器里的子节点」的偏移语义不可靠（实测起手一瞬
     * 给出 y=0 这种停在内容起点的值，就是「按下瞬间上跳」的来源）。
     */
    fun refreshBoardFromCoordinates() {
        val r = rootCoords ?: return
        val c = boardCoords ?: return
        if (!r.isAttached || !c.isAttached) return
        boardOrigin = c.boundsInWindow().topLeft - r.boundsInWindow().topLeft
        if (c.size.width > 0) boardWidth = c.size.width.toFloat()
    }

    /**
     * 页面根 Box 在窗口里的位置。
     *
     * **只给悬浮条用**：那条长条里的图标自己有长按手势（拖出新块），要把「事件的图标
     * 相对坐标」合成成视口坐标，就得知道图标在哪。它登记的位置长在从不做动画的节点上
     * （拖出新块时长条一动不动），所以是准的。卡片一律不登记位置——见类头第 1 条。
     */
    var pageOrigin by mutableStateOf(Offset.Zero)

    /**
     * 悬浮条（球或展开的长条）当前的视口矩形：手势层对落在这里的 down 一律放行——
     * 那条长条盖在组区右缘上，不排除就会把图标们的长按手势抢走。null = 没挂载。
     */
    var paletteRect by mutableStateOf<Rect?>(null)

    // ── 起手快照：几何与命中的唯一基准 ──

    /** 起手那一刻的整页布局。拖拽中每帧都从它重算，它自己不变。 */
    var base by mutableStateOf<HomeLayout?>(null)
        private set

    /** [base] 里参与渲染的组 id（可见组序列）：组排序落盘时把可见下标映射回全量序列。 */
    var baseGroupIds: List<String> = emptyList()
        private set

    // ── 卡片拖拽 / 缩放 / 悬浮条拖出 ──

    /** 被操作那块的 id；悬浮条拖出时是预览与落盘共用的新 id。 */
    var tileId by mutableStateOf<String?>(null)
        private set
    var routeId by mutableStateOf<String?>(null)
        private set

    /** 被拖块的尺寸（格）：画幽灵块用。缩放中逐帧更新。 */
    var sizeCells by mutableStateOf(Offset(2f, 1f))
        private set

    /** 起手时「手指 − 块左上角」（都是视口坐标）：幽灵块 = 手指 − 它，抓哪儿就跟哪儿。 */
    var grab by mutableStateOf(Offset.Zero)
        private set

    /** 起手时块的视口左上角：松手落空时幽灵飞回这里再消失。 */
    var startGhost by mutableStateOf(Offset.Zero)
        private set

    /**
     * 缩放的绝对基准（同 AOSP `AppWidgetResizeFrame.onControllerTouchEvent`：
     * `delta = 当前位置 − 按下位置`，每帧从 baseline 重算，不累积增量）。
     * 把手自己会随尺寸移动，用增量必然漂。实时尺寸由 [HomeScreen] 按
     * `(当前内容坐标 − resizeStartContent)` 派生，所以这两个基准要对它可见。
     */
    var resizeStartCells = Offset.Zero
        private set

    /** 起手时手指的**内容**坐标：缩放位移按内容坐标算，自动滚动时尺寸跟着继续变大。 */
    var resizeStartContent = Offset.Zero
        private set

    /** 从悬浮条拖出的新块：落点用 [HomeLayout.addTileAt] 而不是 `moveTile`。 */
    var fromPalette by mutableStateOf(false)
        private set

    /** 拖拽起始于哪个组（悬浮条新块为 null）：接收高亮只在「拖进别的组」时画（§19）。 */
    var sourceGroupId by mutableStateOf<String?>(null)
        private set

    // ── 组排序 ──

    var groupId by mutableStateOf<String?>(null)
        private set

    /** 起手时它在 [baseGroupIds] 里的下标：整次拖拽恒定，中线判定因此不抖。 */
    var groupIndex by mutableIntStateOf(-1)
        private set

    /** 起手时「手指 − 组顶」（都是内容 y）：跟手位移 = 手指内容 y − 它 − 组顶。 */
    var groupGrabY by mutableFloatStateOf(0f)
        private set

    /**
     * 松手结算中（幽灵吸附 / 回弹动画）：布局已固化、手指已抬起。
     * 命中重算与边缘自动滚动都该停下，[active] 因此不含这一段。
     */
    var settling by mutableStateOf(false)

    /** 幽灵块左上角（视口坐标）：跟手阶段直接由手指推出。 */
    val ghostOrigin: Offset get() = pointer - grab

    /** 起手快照里的可见组；[base] 为空时是空表。 */
    val baseGroups: List<HomeGroup>
        get() = base?.groups?.filter { it.id in baseGroupIds } ?: emptyList()

    /** 起手：把这一场的快照与身份定下来。[origin] 是被操作对象的**视口**左上角。 */
    private fun begin(layout: HomeLayout, visibleIds: List<String>, gesture: HomeDragMode, origin: Offset, startPointer: Offset) {
        reset()
        base = layout
        baseGroupIds = visibleIds
        mode = gesture
        pointer = startPointer
        startGhost = origin
        grab = startPointer - origin
    }

    /** 移动卡片。[origin] 是这一块在起手布局里的视口左上角。 */
    fun beginTile(
        layout: HomeLayout,
        visibleIds: List<String>,
        tile: HomeTile,
        tileGroupId: String,
        origin: Offset,
        startPointer: Offset,
    ) {
        begin(layout, visibleIds, HomeDragMode.TILE, origin, startPointer)
        tileId = tile.id
        routeId = tile.routeId
        sizeCells = Offset(tile.w, tile.h)
        sourceGroupId = tileGroupId
    }

    /** 缩放。[origin] 同 [beginTile]；[startContent] 是起手时手指的内容坐标（缩放基准）。 */
    fun beginResize(
        layout: HomeLayout,
        visibleIds: List<String>,
        tile: HomeTile,
        origin: Offset,
        startPointer: Offset,
        startContent: Offset,
    ) {
        begin(layout, visibleIds, HomeDragMode.RESIZE, origin, startPointer)
        tileId = tile.id
        routeId = tile.routeId
        sizeCells = Offset(tile.w, tile.h)
        resizeStartCells = Offset(tile.w, tile.h)
        resizeStartContent = startContent
    }

    /** 从悬浮条拖出一个新块：[newTileId] 已按全局唯一规则分好，预览与落盘用同一个。 */
    fun beginPalette(
        layout: HomeLayout,
        visibleIds: List<String>,
        newTileId: String,
        newRouteId: String,
        w: Float,
        h: Float,
        startPointer: Offset,
        colUnit: Float,
        rowUnit: Float,
    ) {
        // 悬浮条上按住的是一枚圆图标，幽灵块抓在中心最自然
        val center = Offset(w * colUnit / 2f, h * rowUnit / 2f)
        begin(layout, visibleIds, HomeDragMode.PALETTE, startPointer - center, startPointer)
        tileId = newTileId
        routeId = newRouteId
        fromPalette = true
        sizeCells = Offset(w, h)
        sourceGroupId = null
    }

    /**
     * 组排序。[groupTop] 是这一组顶边（含标题行）的**内容** y，[startContent] 是起手时
     * 手指的内容坐标；两者之差就是抓取偏移，之后跟手位移 = 当前内容 y − 它 − 组顶。
     */
    fun beginGroup(
        layout: HomeLayout,
        visibleIds: List<String>,
        draggedGroupId: String,
        index: Int,
        groupTop: Float,
        startPointer: Offset,
        startContent: Offset,
    ) {
        // 组排序不画幽灵：origin/grab 用不上，起点填成手指本身
        begin(layout, visibleIds, HomeDragMode.GROUP, startPointer, startPointer)
        groupId = draggedGroupId
        groupIndex = index
        groupGrabY = startContent.y - groupTop
    }

    /**
     * 手指移动：只记下**页面根局部坐标**。
     *
     * 落点、目标组、缩放尺寸、组排序目标下标**全部不在这里算**——它们是 [HomeScreen] 里
     * 读 `(pointer, boardOrigin, 快照几何)` 的 `derivedStateOf` 派生量。这样设计的好处：
     * 边缘自动滚动改的是 [boardOrigin]（以及派生出的手指板坐标），它一变，所有派生量自动
     * 重算，不需要任何「滚动时补算」的特例（旧实现要为缩放专门补一次尺寸，就是因为把计算
     * 塞进了手势回调里）。
     */
    fun move(now: Offset) {
        pointer = now
    }

    /**
     * 手势结束：进入结算，但**留下 [base] 与 [tileId]**——幽灵的吸附/回弹动画还要靠它们画，
     * 动画播完由 [finish] 彻底清场。
     */
    fun release() {
        lastEvent = "release(mode was $mode)"
        if (mode == HomeDragMode.IDLE) return
        mode = HomeDragMode.IDLE
        settling = true
    }

    /** 结算动画播完（或被新手势顶掉）：彻底清场。 */
    fun finish() {
        lastEvent = "finish"
        reset()
    }

    /**
     * 手势被系统抢走 / 从头到尾没拖过 slop（其实是点击）：整场作废，布局由调用方恢复成
     * [base]（AOSP Launcher3 的 `revertTempState()` 就是这个语义）。
     */
    fun abort() {
        lastEvent = "abort"
        reset()
    }

    private fun reset() {
        mode = HomeDragMode.IDLE
        settling = false
        base = null
        baseGroupIds = emptyList()
        tileId = null
        routeId = null
        fromPalette = false
        sourceGroupId = null
        groupId = null
        groupIndex = -1
        sizeCells = Offset(2f, 1f)
        grab = Offset.Zero
        startGhost = Offset.Zero
        groupGrabY = 0f
    }
}

/**
 * 组区几何表：每组多高、在哪里、每块在哪个矩形，全部由 [HomeDragSession.base] 这一份
 * **数据**推出（见 [buildBoardGeometry]）。
 *
 * 坐标一律是**板内坐标**：原点就是组区容器左上角（构造时 `left = top = 0`），
 * 所以整张表是**纯数据**、不含任何测量量，也就没有「测量时序不对导致整体偏移」的余地。
 * 手指坐标由首页用 `pointer − boardOrigin` 现算成同一套板内坐标。
 */
internal class HomeBoardGeometry(
    /** 组区左边界（视口 x = 内容 x，横向不滚动）。 */
    val left: Float,
    val width: Float,
    /** 一格的宽（px）：`(组宽 + 缝隙) / 列数`。 */
    val colUnit: Float,
    /** 一格的高（px）：`单元高 + 缝隙`。 */
    val rowUnit: Float,
    val slots: List<Slot>,
) {
    internal class Slot(
        val groupId: String,
        /** 组顶（含标题行）的内容 y。 */
        val top: Float,
        /** 网格区顶的内容 y。 */
        val gridTop: Float,
        /** 组总高（标题行 + 网格），不含组间距。 */
        val height: Float,
        /** 这一组在起手快照里的块：命中测试直接查它，不回读任何节点。 */
        val tiles: List<HomeTile>,
    )

    val ready: Boolean get() = colUnit > 0f && rowUnit > 0f && slots.isNotEmpty()

    fun slot(groupId: String): Slot? = slots.firstOrNull { it.groupId == groupId }

    /**
     * 手指（内容坐标）落在哪一组。
     *
     * 组间空隙对半分：上一组向下延半个组间距、下一组向上延半个组间距，两段区间正好
     * **首尾相接不重叠**，所以这是位置的纯函数——不存在「边界上来回切」，也就不需要
     * 记忆当前组的滞回。横向超出组区、或落进所有组之外的空白，返回 null（松手回弹）。
     */
    fun groupAt(content: Offset, halfGap: Float): String? {
        if (content.x < left || content.x > left + width) return null
        return slots.firstOrNull { content.y >= it.top - halfGap && content.y < it.top + it.height + halfGap }?.groupId
    }

    /** 手指（内容坐标）换算成 [groupId] 组里的格坐标（不取整、不夹：交给 `clampToBoard`）。 */
    fun cellAt(groupId: String, content: Offset): Offset? {
        val s = slot(groupId) ?: return null
        return Offset((content.x - left) / colUnit, (content.y - s.gridTop) / rowUnit)
    }

    /** 某一格的**板内坐标**左上角：松手时幽灵要吸附到这里（加 boardOrigin 换成页面坐标）。 */
    fun cellOrigin(groupId: String, cellX: Float, cellY: Float): Offset? {
        val s = slot(groupId) ?: return null
        return Offset(left + cellX * colUnit, s.gridTop + cellY * rowUnit)
    }

    /** 某一块的板内坐标左上角：起手时用它算 grab。 */
    fun tileOrigin(groupId: String, tile: HomeTile): Offset? {
        val s = slot(groupId) ?: return null
        return Offset(left + tile.x * colUnit, s.gridTop + tile.y * rowUnit)
    }

    /** 各组高度表（含组间距）：组排序纯函数 [HomeGridMath.reorderTargetIndex] 的输入。 */
    fun heights(gapPx: Float): List<Float> = slots.map { it.height + gapPx }

    /**
     * 组区容器的总高（含组间与首组间距）：拖拽期间用它当 `heightIn(min = …)`，
     * 把页面总高钉在起手快照的那一档。
     *
     * 不钉会出事：拖走一块之后源组变矮、页面变短，`scrollY` 被夹回上界，而 `scrollY`
     * 又是内容坐标的唯一来源——落点跟着跳，跳了又改布局，布局又改高度，成一个回路。
     * 变高不钉也没关系（只放宽上界、不动当前位置），所以用 min 而不是 height。
     */
    fun totalHeight(gapPx: Float, firstGapPx: Float): Float {
        if (slots.isEmpty()) return 0f
        val groups = slots.fold(0f) { acc, s -> acc + s.height }
        return groups + firstGapPx + gapPx * (slots.size - 1)
    }

    /**
     * 按下的落点归谁（[content] 是内容坐标）。
     *
     * 优先级：卡片左上角的移除热区 → 卡片右下角的缩放把手 → 卡片体 → 组标题行的排序
     * 把手 → 放行。「放行」包含删除组按钮、组名与所有空白——那里下面还有真的点击节点
     * 在等，手势层绝不能吃掉 down。
     */
    fun hit(content: Offset, handlePx: Float, removePx: Float): HomeHit {
        if (content.x < left || content.x > left + width) return HomeHit.Pass
        slots.forEachIndexed { index, s ->
            // 标题行：只有左侧那一小块是排序把手，其余（组名、删除按钮）放行
            if (content.y >= s.top && content.y < s.gridTop) {
                return if (content.x - left <= handlePx) HomeHit.GroupHandle(s.groupId, index) else HomeHit.Pass
            }
            if (content.y < s.top || content.y > s.top + s.height) return@forEachIndexed
            // 网格区：模型层保证组内不重叠，所以命中第一块就是唯一那块
            s.tiles.forEach { tile ->
                val topLeft = Offset(left + tile.x * colUnit, s.gridTop + tile.y * rowUnit)
                val right = topLeft.x + tile.w * colUnit
                val bottom = topLeft.y + tile.h * rowUnit
                if (content.x < topLeft.x || content.x > right || content.y < topLeft.y || content.y > bottom) {
                    return@forEach
                }
                if (content.x - topLeft.x <= removePx && content.y - topLeft.y <= removePx) return HomeHit.Pass
                return if (right - content.x <= handlePx && bottom - content.y <= handlePx) {
                    HomeHit.Resize(tile.id, s.groupId)
                } else {
                    HomeHit.Tile(tile.id, s.groupId)
                }
            }
        }
        return HomeHit.Pass
    }
}

/**
 * 由**起手快照**推出组区几何表（纯函数）：每组高度 = 标题区 + 格数 × 单元高。
 *
 * 拖拽期间渲染的实时布局会让某些组变高变矮，但这里始终按快照算，所以命中区间恒定，
 * 命中与滚动、命中与落点之间都不存在反馈回路。
 */
internal fun buildBoardGeometry(
    groups: List<HomeGroup>,
    left: Float,
    /** 组区内容顶（第一组标题行之上还要加一个组间距，见 [firstGapPx]）。 */
    top: Float,
    width: Float,
    cellHeightPx: Float,
    gapPx: Float,
    headerPx: Float,
    groupGapPx: Float,
    firstGapPx: Float,
): HomeBoardGeometry {
    val colUnit = if (width > 0f) (width + gapPx) / HomeGridMath.COLUMNS else 0f
    val rowUnit = cellHeightPx + gapPx
    val slots = mutableListOf<HomeBoardGeometry.Slot>()
    var y = top + firstGapPx
    groups.forEach { group ->
        val rows = HomeGridMath.contentBottom(group.tiles).coerceAtLeast(1f)
        val gridHeight = rows * rowUnit - gapPx
        slots += HomeBoardGeometry.Slot(
            groupId = group.id,
            top = y,
            gridTop = y + headerPx,
            height = headerPx + gridHeight,
            tiles = group.tiles,
        )
        y += headerPx + gridHeight + groupGapPx
    }
    return HomeBoardGeometry(left, width, colUnit, rowUnit, slots)
}
