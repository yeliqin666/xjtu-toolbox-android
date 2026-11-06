package com.xjtu.toolbox.home

/**
 * 一个功能按钮在卡片组里的位置与尺寸，单位是「格」（见 [HomeGridMath]）。
 *
 * [id] 是**这一块**的稳定标识，不是路由 id：同一个功能可以同时出现在「常用功能」和
 * 它自己的分类组里（现在就是这样，常用功能是快捷方式），所以 `日程` 在两个组里是两块。
 * 拖动、挤开、落盘都认 [id]。
 */
data class HomeTile(
    val id: String,
    /** 打开哪个功能，= `AppRoute.id`；目录里查不到时这一块就不画（旧版本存的、研究生看不到的）。 */
    val routeId: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    /** 纯图标模式：缩到这个尺寸以下就不摆文字（需求 §3.3 的极值形态）。 */
    val iconOnly: Boolean get() = w < 1.2f || h < 1.2f

    val area: Float get() = w * h
}

/**
 * 首页的一个卡片组。
 *
 * [id] 稳定不变（默认组用分类名，自建组是 `custom-<时间戳式的短串>`）；[title] 是用户能改的组名
 * ——默认填分类名（「常用功能」「教务」…），编辑态点组头可以重命名（存进快照、跟着导出 / 导入走）；
 * 空串 = 常规态整行不画、编辑态显示「未命名分组」占位（需求 §2.3）；[colorHex] 是标题颜色，
 * 空串 = 跟随主题。
 */
data class HomeGroup(
    val id: String,
    val title: String = "",
    val colorHex: String = "",
    val tiles: List<HomeTile> = emptyList(),
) {
    /** 组里内容占到的底部（格），渲染高度按它算。 */
    val rows: Float get() = HomeGridMath.contentBottom(tiles)
}

/**
 * 首页整页的分组与摆放。
 *
 * 默认布局由 [HomeLayoutDefaults] 按服务目录生成；用户动过之后整页落盘（见
 * `HomeLayoutStore`），所以这里只管纯数据与纯操作，不碰存储、不碰 Compose。
 */
data class HomeLayout(val groups: List<HomeGroup> = emptyList()) {

    fun group(id: String): HomeGroup? = groups.firstOrNull { it.id == id }

    /** 找到某一块属于哪个组。 */
    fun locate(tileId: String): Pair<HomeGroup, HomeTile>? {
        groups.forEach { group ->
            group.tiles.firstOrNull { it.id == tileId }?.let { return group to it }
        }
        return null
    }

    fun tile(tileId: String): HomeTile? = locate(tileId)?.second

    /** 整块替换一个组（顺序不变）。 */
    fun replaceGroup(group: HomeGroup): HomeLayout =
        copy(groups = groups.map { if (it.id == group.id) group else it })

    /**
     * 把一块挪到 [toGroupId] 组的 `(x, y)`。同组是重排，跨组是换组——
     * 两种情况共用同一套挤开规则，只是「被挤开的块能不能回到原位置」不同。
     *
     * [rowBudget]（组 id → 行数上限）只在**拖动中**传：传了就把落位夹进各组的行数预算，
     * 于是整场拖拽里**没有任何一个组会变高**（渲染侧再把高度钉在预算那一档，见
     * `HomeGroupSection`）。这是「落点不飘」的物理保证——命中区间由起手快照算出，渲染
     * 也由同一份快照的高度排布，两者永远严格相等；组一变高，下面的组就被推下去，而命中
     * 还按快照的旧位置算，手指与格子的对应关系当场失效（那是振荡的根源，AOSP Launcher3
     * 拖拽期间 CellLayout 尺寸恒定，是同一个道理）。
     *
     * 传 null = 不限行数、且压掉空行：这是**松手那一次**用的。拖到组末尾想新增一行，
     * 就是靠松手这一次放开预算才成立的；拖动中放开则会让落点跟着组高一起动。
     */
    fun moveTile(
        tileId: String,
        toGroupId: String,
        x: Float,
        y: Float,
        grid: Boolean = true,
        snapTolerance: Float = 0f,
        rowBudget: Map<String, Float>? = null,
    ): HomeLayout {
        val (from, tile) = locate(tileId) ?: return this
        val target = group(toGroupId) ?: return this
        val moved = tile.copy(x = x, y = y)

        if (from.id == target.id) {
            val adjusted = if (snapTolerance > 0f) {
                val others = from.tiles.filter { it.id != tileId }
                HomeGridMath.snapToNeighbors(HomeGridMath.clampToBoard(moved, grid), others, snapTolerance)
            } else {
                moved
            }
            val placed = HomeGridMath.placeInGroup(from.tiles, adjusted, grid, rowBudget?.get(from.id))
            return replaceGroup(from.copy(tiles = finishRows(placed, grid)))
        }

        // 跨组：先把它从原来那组摘掉（原位置留下空档，不压缩别的块），再放进目标组
        val fromAfter = from.copy(tiles = from.tiles.filter { it.id != tileId })
        val targetAfter = target.copy(tiles = HomeGridMath.placeAcross(target.tiles, moved, grid, rowBudget?.get(target.id)))
        return copy(
            groups = groups.map {
                when (it.id) {
                    fromAfter.id -> fromAfter.copy(tiles = finishRows(fromAfter.tiles, grid))
                    targetAfter.id -> targetAfter.copy(tiles = finishRows(targetAfter.tiles, grid))
                    else -> it
                }
            },
        )
    }

    /**
     * 改一块的尺寸（右下角拖动）；挤开规则和拖拽一致，[rowBudget] 同 [moveTile]。
     *
     * 缩放**不吃行数预算**（[HomeScreen] 传 null）：把卡片拉高、组跟着变高是用户的明确
     * 意图，而且缩放全程不需要命中判定——尺寸由「手指的内容坐标位移」绝对差值算出，
     * 与组高无关。组高变化只影响页面总高，那由 `heightIn(min = 快照总高)` 钉住。
     */
    fun resizeTile(tileId: String, w: Float, h: Float, grid: Boolean = true, rowBudget: Map<String, Float>? = null): HomeLayout {
        val (group, tile) = locate(tileId) ?: return this
        val moved = tile.copy(w = w, h = h)
        val placed = HomeGridMath.resizeInGroup(group.tiles, moved, grid, rowBudget?.get(group.id))
        return replaceGroup(group.copy(tiles = finishRows(placed, grid)))
    }

    /**
     * 一次落位的收尾：网格模式下把这一组的**全空行**收掉，下面的块整体上移补位（§A1）。
     *
     * 拖动中也收（以前只在松手那一次收——那样预览留着空行、松手一压缩，落点就和看到的不一样，
     * 于是 16278fd7 那轮反过来取消了松手压缩，空行就永远留着了）。现在两边都收，预览与落盘
     * 逐格一致；组高在拖动中由行数预算钉住（渲染那一层），所以收掉空行只是块在组内上移，
     * 不会让下面的组跟着跳。自由模式允许留空隙，不压。
     */
    private fun finishRows(tiles: List<HomeTile>, grid: Boolean): List<HomeTile> =
        if (grid) HomeGridMath.compactEmptyRows(tiles) else tiles

    /**
     * 往 [groupId] 组里加一个功能。**同一组里允许出现同一个功能的多块**（2026-11 按需求改）：
     * 「常用功能」本来就是同一路由的第二块，组内再来一块也是用户的明确意图，没有理由拦。
     */
    fun addTile(groupId: String, routeId: String, tileId: String): HomeLayout {
        val group = group(groupId) ?: return this
        // 新块按 2×1 进组：和默认布局一档，放不下就落到最下面
        val size = 2f to 1f
        val slot = HomeGridMath.firstFreeSlot(group.tiles, size.first, size.second)
        val tile = if (slot != null) {
            HomeTile(tileId, routeId, slot.first, slot.second, size.first, size.second)
        } else {
            HomeGridMath.bottomSlot(group.tiles, HomeTile(tileId, routeId, 0f, 0f, size.first, size.second))
        }
        return replaceGroup(group.copy(tiles = group.tiles + tile))
    }

    /**
     * 往 [groupId] 组的**指定格** `(x, y)` 放一个功能（从添加面板 / 悬浮条拖出来落到这里）：
     * 新块按 2×1 进组，落点照样挤开邻居。同组已有同路由**照样加**，而且待遇和别的卡片
     * **没有任何区别**——落点上是谁就挤谁，不做特判（2026-11 维护者定：只删限制，不加规则）。
     * `[rowBudget]` 同 [moveTile]。
     */
    fun addTileAt(
        groupId: String,
        routeId: String,
        tileId: String,
        x: Float,
        y: Float,
        grid: Boolean = true,
        rowBudget: Map<String, Float>? = null,
    ): HomeLayout {
        val group = group(groupId) ?: return this
        val placed = HomeGridMath.placeInGroup(group.tiles, HomeTile(tileId, routeId, x, y, 2f, 1f), grid, rowBudget?.get(groupId))
        return replaceGroup(group.copy(tiles = finishRows(placed, grid)))
    }

    /**
     * 读盘时把各组里遗留的**全空行**收掉（只网格模式）。
     *
     * 理由：老版本会在存档里留下空行（拖动中被行数预算钉着、松手那次也没收），而「收空行」
     * 只在用户再动一次布局时才跑——那样用户升级完第一眼看到的还是那片空行，会以为没修。
     * 无空行时原样返回（`compactEmptyRows` 没有位移就返回同一个实例），不产生多余对象。
     */
    fun compactGaps(grid: Boolean = true): HomeLayout {
        if (!grid) return this
        var changed = false
        val compacted = groups.map { group ->
            val tiles = HomeGridMath.compactEmptyRows(group.tiles)
            if (tiles === group.tiles) {
                group
            } else {
                changed = true
                group.copy(tiles = tiles)
            }
        }
        return if (changed) copy(groups = compacted) else this
    }

    /**
     * 把整页的摆位**取整改进网格**（自由摆放 → 开启网格的那一下用，不可撤销）。
     *
     * 规则就三步，全部复用拖拽那一套纯函数，所以结果和「用户手动一格一格拖进去」一致：
     * 1. 每块先 [HomeGridMath.clampToBoard]：宽高取整、夹进 1..4 格，左边界夹进 0..列数-w；
     * 2. 按阅读顺序（上→下、左→右）逐块落位：原位没被占就直接用，占了找第一个放得下的
     *    空位（[HomeGridMath.firstFreeSlot]），实在没有就摞到本组最下方
     *    （[HomeGridMath.bottomSlot]）——绝不重叠，也绝不跨组；
     * 3. 每组最后收一遍空行（[HomeGridMath.compactEmptyRows]）。
     *
     * 自由模式下允许 1.3×2.7 这种非整数摆位，网格模式里它们既吸不住也画不齐；
     * 取整之后**原来的位置和大小就不再是那份数据了**，这就是设置面板里那句「不可恢复」。
     */
    fun snapToGrid(): HomeLayout = copy(
        groups = groups.map { group ->
            val packed = mutableListOf<HomeTile>()
            group.tiles
                .sortedWith(compareBy({ it.y }, { it.x }, { it.id }))
                .forEach { tile ->
                    val clamped = HomeGridMath.clampToBoard(tile, grid = true)
                    val placed = if (HomeGridMath.isFree(clamped, packed)) {
                        clamped
                    } else {
                        HomeGridMath.firstFreeSlot(packed, clamped.w, clamped.h)
                            ?.let { clamped.copy(x = it.first, y = it.second) }
                            ?: HomeGridMath.bottomSlot(packed, clamped)
                    }
                    packed += placed
                }
            group.copy(tiles = HomeGridMath.compactEmptyRows(packed))
        },
    )

    /**
     * 删掉一块。网格模式下顺手把因此空出来的整行收掉（§A1：空行不该留着，下面的块上移），
     * 否则删掉中间那一行里的块之后，这一行会一直空着，下面的块与后面的组也跟着永远错开。
     */
    fun removeTile(tileId: String, grid: Boolean = true): HomeLayout = copy(
        groups = groups.map { group ->
            val kept = group.tiles.filter { it.id != tileId }
            if (kept.size == group.tiles.size) group else group.copy(tiles = finishRows(kept, grid))
        },
    )

    /**
     * 去掉**一个块都不剩的组**：编辑态里把某一组的卡片全搬走之后，这个组在常规态本来就是
     * 隐藏的（`groupVisible` 只认有卡的组），留着它只是存档里的垃圾。落盘时统一清一次，
     * 于是「空卡组在保存后自动删除」。
     */
    fun withoutEmptyGroups(): HomeLayout =
        if (groups.none { it.tiles.isEmpty() }) this else copy(groups = groups.filter { it.tiles.isNotEmpty() })

    /**
     * 给新块分一个**全局唯一**的 id：尾号取整页现有 `#N` 尾号的最大值 +1。
     *
     * 不能用「组内块数」当尾号——删掉一块再加就会撞号，`key(tile.id)` 与 `locate`
     * 都会错乱。尾号只保证唯一；前面的 `组/路由` 前缀只是让人读落盘数据时好认。
     */
    fun nextTileId(groupId: String, routeId: String): String {
        val serial = groups.flatMap { it.tiles }
            .mapNotNull { it.id.substringAfterLast('#', "").toIntOrNull() }
            .maxOrNull() ?: -1
        return "$groupId/$routeId#${serial + 1}"
    }

    /**
     * 改组名（空串 = 常规态不显示标题）。
     *
     * 界面入口是编辑态点组头打开的「分组设置」，里面既能改名也能改标题颜色；
     * 组名存进整页快照，导出 / 导入布局时原样带走。
     */
    fun renameGroup(groupId: String, title: String): HomeLayout =
        group(groupId)?.let { replaceGroup(it.copy(title = title)) } ?: this

    /** 改标题颜色；空串 = 跟随主题。 */
    fun tintGroup(groupId: String, colorHex: String): HomeLayout =
        group(groupId)?.let { replaceGroup(it.copy(colorHex = colorHex)) } ?: this

    fun addGroup(group: HomeGroup): HomeLayout = copy(groups = groups + group)

    /** 删组（组里的块一起删；默认组不允许删，见调用方）。 */
    fun removeGroup(groupId: String): HomeLayout = copy(groups = groups.filter { it.id != groupId })

    /** 拖拽卡片组本身调上下顺序。 */
    fun moveGroup(groupId: String, toIndex: Int): HomeLayout {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return this
        val target = toIndex.coerceIn(0, groups.lastIndex)
        if (index == target) return this
        val list = groups.toMutableList()
        list.add(target, list.removeAt(index))
        return copy(groups = list)
    }

    /**
     * 拖拽排序的落盘版：组排序的几何（中线、让位）是在**可见组序列**里算的——
     * 「常用功能」被设置里关掉时它不渲染，可见序列就是全量序列去掉它。
     * 这里把「可见序列里挪到 [targetVisibleIndex]」映射回全量序列：可见槽位按新顺序
     * 依次填回去，不可见的组原地不动。
     */
    fun moveGroupWithin(groupId: String, visibleIds: List<String>, targetVisibleIndex: Int): HomeLayout {
        val order = visibleIds.toMutableList()
        val from = order.indexOf(groupId)
        if (from < 0) return this
        val target = targetVisibleIndex.coerceIn(0, order.lastIndex)
        if (from == target) return this
        order.add(target, order.removeAt(from))
        val byId = groups.associateBy { it.id }
        val queue = order.mapNotNull { byId[it] }
        val iterator = queue.iterator()
        return copy(
            groups = groups.map { group ->
                if (group.id in visibleIds && iterator.hasNext()) iterator.next() else group
            },
        )
    }

    /** 组里所有块是否都不重叠（自检与单测用）。 */
    fun isConsistent(): Boolean = groups.all { group ->
        group.tiles.indices.all { i ->
            (i + 1 until group.tiles.size).all { j ->
                !HomeGridMath.overlaps(group.tiles[i], group.tiles[j])
            }
        }
    }
}


