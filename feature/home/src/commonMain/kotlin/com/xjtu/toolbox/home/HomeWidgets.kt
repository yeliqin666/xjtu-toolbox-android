package com.xjtu.toolbox.home

import com.xjtu.toolbox.nav.AppRoute

/** 小组件尺寸：占几列、几行（四列网格，格子是正方形）。 */
enum class WidgetSize(val cols: Int, val rows: Int, val key: String, val label: String) {
    SMALL(1, 1, "1x1", "小"),
    WIDE(2, 1, "2x1", "横条"),
    TALL(1, 2, "1x2", "竖条"),
    LARGE(2, 2, "2x2", "大");

    companion object {
        fun of(key: String): WidgetSize? = entries.firstOrNull { it.key == key }
        fun fromExtent(width: Float, height: Float): WidgetSize = entries.first {
            it.cols == (if (width >= 1.5f) 2 else 1) && it.rows == (if (height >= 1.5f) 2 else 1)
        }
    }
}

/** 首页上的一个小组件：哪个功能、多大。 */
data class HomeWidgetSpec(val routeId: String, val size: WidgetSize)

/** 排好位置的小组件：左上角在第 [col] 列、第 [row] 行。 */
data class PlacedWidget(val spec: HomeWidgetSpec, val col: Int, val row: Int)

/**
 * 一组小组件：组内按顺序自动排（[HomeWidgetLayout.pack]）。[title] 为空时常规态不画标题行；
 * [colorHex] 是标题颜色，空串 = 跟随主题。同一个功能可以同时出现在不同组里（比如常用功能）。
 */
data class HomeWidgetGroup(
    val id: String,
    val title: String = "",
    val colorHex: String = "",
    val widgets: List<HomeWidgetSpec> = emptyList(),
)

/** 小组件的落盘格式、默认布局与排版。 */
object HomeWidgetLayout {
    const val COLUMNS = 4
    const val QUICK_GROUP = "quick"

    fun encode(widgets: List<HomeWidgetSpec>): List<String> = widgets.map { "${it.routeId}:${it.size.key}" }

    /** 认不出的行、已经不在 [available] 里的功能、重复的功能都丢掉。 */
    fun decode(lines: List<String>, available: Collection<String>): List<HomeWidgetSpec> =
        lines.mapNotNull { line ->
            val id = line.substringBefore(':').trim()
            val size = WidgetSize.of(line.substringAfter(':', "").trim()) ?: return@mapNotNull null
            HomeWidgetSpec(id, size).takeIf { id in available }
        }.distinctBy { it.routeId }

    /** 分组落盘：每组一行 `#id\t标题\t颜色`，下面跟着本组的 `路由id:尺寸`。 */
    fun encodeGroups(groups: List<HomeWidgetGroup>): List<String> = groups.flatMap { g ->
        listOf("#" + listOf(g.id, g.title, g.colorHex).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }) +
            encode(g.widgets)
    }

    /** 早先的存档没有组标题行：整串当成一个无标题组。 */
    fun decodeGroups(lines: List<String>, available: Collection<String>): List<HomeWidgetGroup> {
        if (lines.none { it.startsWith('#') }) return listOf(HomeWidgetGroup("main", widgets = decode(lines, available)))
        val groups = mutableListOf<HomeWidgetGroup>()
        var header: List<String>? = null
        val body = mutableListOf<String>()
        fun flush() {
            header?.let { h -> groups += HomeWidgetGroup(h[0], h.getOrElse(1) { "" }, h.getOrElse(2) { "" }, decode(body, available)) }
            body.clear()
        }
        lines.forEach { line ->
            if (line.startsWith('#')) {
                flush()
                header = line.drop(1).split('\t')
            } else body += line
        }
        flush()
        return groups.distinctBy { it.id }
    }

    /** 默认分组：常用功能（小格）在最上面，下面每个分类一组。 */
    fun defaultGroups(
        quick: List<String>,
        categories: List<Triple<String, String, List<String>>>,
        hasStat: (String) -> Boolean,
    ): List<HomeWidgetGroup> =
        listOf(HomeWidgetGroup(QUICK_GROUP, "常用功能", widgets = quick.distinct().map { HomeWidgetSpec(it, WidgetSize.SMALL) })) +
            categories.map { (id, title, routes) -> HomeWidgetGroup(id, title, widgets = defaults(routes, hasStat)) }

    /** 把 [fromGroup] 组里的 [routeId] 挪到 [toGroup] 组的第 [toIndex] 位；跨组时目标组已有同一功能就不动。 */
    fun move(groups: List<HomeWidgetGroup>, fromGroup: String, routeId: String, toGroup: String, toIndex: Int): List<HomeWidgetGroup> {
        val spec = groups.firstOrNull { it.id == fromGroup }?.widgets?.firstOrNull { it.routeId == routeId } ?: return groups
        if (fromGroup != toGroup && groups.firstOrNull { it.id == toGroup }?.widgets?.any { it.routeId == routeId } != false) return groups
        val removed = groups.map { g -> if (g.id == fromGroup) g.copy(widgets = g.widgets - spec) else g }
        return removed.map { g ->
            if (g.id != toGroup) g
            else g.copy(widgets = g.widgets.toMutableList().apply { add(toIndex.coerceIn(0, size), spec) })
        }
    }

    /**
     * 没编辑过时的默认布局：按目录顺序，有实时状态的功能给横条（能放下一行数字），其余给小格。
     * 校园卡、课表这类最常看的放大一档。
     */
    fun defaults(routeIds: List<String>, hasStat: (String) -> Boolean): List<HomeWidgetSpec> =
        routeIds.map { id ->
            val size = when {
                id in FEATURED -> WidgetSize.LARGE
                id in INFORMATIONAL -> WidgetSize.WIDE
                hasStat(id) -> WidgetSize.WIDE
                else -> WidgetSize.SMALL
            }
            HomeWidgetSpec(id, size)
        }

    /**
     * 按顺序逐个放进第一个放得下的位置（从上到下、从左到右），小格会回填前面留下的空洞，
     * 所以拖动、改尺寸后整体依旧紧凑。
     */
    fun pack(widgets: List<HomeWidgetSpec>, columns: Int = COLUMNS): List<PlacedWidget> {
        val taken = mutableListOf<BooleanArray>()
        fun row(r: Int): BooleanArray {
            while (taken.size <= r) taken.add(BooleanArray(columns))
            return taken[r]
        }
        fun fits(c: Int, r: Int, size: WidgetSize): Boolean {
            if (c + size.cols > columns) return false
            for (dr in 0 until size.rows) for (dc in 0 until size.cols) if (row(r + dr)[c + dc]) return false
            return true
        }
        return widgets.map { spec ->
            val size = spec.size.let { if (it.cols > columns) WidgetSize.SMALL else it }
            var r = 0
            var spot: Pair<Int, Int>? = null
            while (spot == null) {
                for (c in 0 until columns) if (fits(c, r, size)) { spot = c to r; break }
                if (spot == null) r++
            }
            val (c0, r0) = spot!!
            for (dr in 0 until size.rows) for (dc in 0 until size.cols) row(r0 + dr)[c0 + dc] = true
            PlacedWidget(spec.copy(size = size), c0, r0)
        }
    }

    /** 排完一共占几行。 */
    fun rowCount(placed: List<PlacedWidget>): Int = placed.maxOfOrNull { it.row + it.spec.size.rows } ?: 0

    /** 默认就放大的功能：最常看、内容也最多。 */
    private val FEATURED = setOf(AppRoute.CampusCard.id, AppRoute.Library.id)
    private val INFORMATIONAL = setOf(AppRoute.Schedule.id, AppRoute.SchoolCalendar.id, AppRoute.Lms().id,
        AppRoute.Attendance.id, AppRoute.EmptyRoom.id, AppRoute.Notification.id, AppRoute.Community.id,
        AppRoute.Games.id, AppRoute.Agent.id, AppRoute.YellowPage.id)
}
