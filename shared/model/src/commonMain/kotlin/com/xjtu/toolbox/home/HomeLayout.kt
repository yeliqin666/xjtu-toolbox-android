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
 * [id] 稳定不变（默认组用分类名，自建组是 `custom-<时间戳式的短串>`）；[title] 允许为空串——
 * 空串时整块标题组件都不画（需求 §2.3）；[colorHex] 是标题颜色，空串 = 跟随主题。
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
     */
    fun moveTile(
        tileId: String,
        toGroupId: String,
        x: Float,
        y: Float,
        grid: Boolean = true,
        snapTolerance: Float = 0f,
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
            val placed = HomeGridMath.placeInGroup(from.tiles, adjusted, grid)
            // 网格模式下把拖走留下的全空行收掉，下面的块跟上来
            return replaceGroup(from.copy(tiles = if (grid) HomeGridMath.compactEmptyRows(placed) else placed))
        }

        // 跨组：先把它从原来那组摘掉（原位置留下空档，不压缩别的块），再放进目标组
        val fromAfter = from.copy(tiles = from.tiles.filter { it.id != tileId })
        val targetAfter = target.copy(tiles = HomeGridMath.placeAcross(target.tiles, moved, grid))
        return copy(
            groups = groups.map {
                when (it.id) {
                    // 网格模式下，源组少了一块可能空出行，目标组挤开后也可能：都收一收
                    fromAfter.id -> if (grid) fromAfter.copy(tiles = HomeGridMath.compactEmptyRows(fromAfter.tiles)) else fromAfter
                    targetAfter.id -> if (grid) targetAfter.copy(tiles = HomeGridMath.compactEmptyRows(targetAfter.tiles)) else targetAfter
                    else -> it
                }
            },
        )
    }

    /** 改一块的尺寸（右下角拖动）；挤开规则和拖拽一致。 */
    fun resizeTile(tileId: String, w: Float, h: Float, grid: Boolean = true): HomeLayout {
        val (group, tile) = locate(tileId) ?: return this
        val moved = tile.copy(w = w, h = h)
        val placed = HomeGridMath.resizeInGroup(group.tiles, moved, grid)
        return replaceGroup(group.copy(tiles = if (grid) HomeGridMath.compactEmptyRows(placed) else placed))
    }

    /** 往 [groupId] 组里加一个功能；组里已经有同一个路由就不重复加。 */
    fun addTile(groupId: String, routeId: String, tileId: String): HomeLayout {
        val group = group(groupId) ?: return this
        if (group.tiles.any { it.routeId == routeId }) return this
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
     * 往 [groupId] 组的**指定格** `(x, y)` 放一个功能（从添加面板拖出来落到这里）：
     * 新块按 2×1 进组，落点照样挤开邻居；组里已有同路由就不加。
     */
    fun addTileAt(groupId: String, routeId: String, tileId: String, x: Float, y: Float, grid: Boolean = true): HomeLayout {
        val group = group(groupId) ?: return this
        if (group.tiles.any { it.routeId == routeId }) return this
        val placed = HomeGridMath.placeInGroup(group.tiles, HomeTile(tileId, routeId, x, y, 2f, 1f), grid)
        return replaceGroup(group.copy(tiles = if (grid) HomeGridMath.compactEmptyRows(placed) else placed))
    }

    fun removeTile(tileId: String): HomeLayout = copy(
        groups = groups.map { group -> group.copy(tiles = group.tiles.filter { it.id != tileId }) },
    )

    /** 改组名（允许空串 = 不显示标题）。 */
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

    /** 组里所有块是否都不重叠（自检与单测用）。 */
    fun isConsistent(): Boolean = groups.all { group ->
        group.tiles.indices.all { i ->
            (i + 1 until group.tiles.size).all { j ->
                !HomeGridMath.overlaps(group.tiles[i], group.tiles[j])
            }
        }
    }
}


