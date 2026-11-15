package com.xjtu.toolbox.home

/**
 * 把 dev 上那套「小组件」布局的落盘数据迁成卡片组的 [HomeLayout]。
 *
 * 为什么需要它：dev 发过版，用户的小组件分组、标题、颜色、尺寸与顺序都在 `home_widgets` 里。
 * 那套实现已经并进卡片组，所以升级时必须把这些排版**原样搬过来**，不能让人白摆一遍。
 *
 * 老格式（每组一行表头，下面跟着本组的块）：
 * ```
 * #quick	常用功能	#FF3B30
 * campus_card:2x1
 * schedule:2x2
 * ```
 * 没有 `#` 表头的老存档（更早的版本）整串当一组、无标题、无颜色。
 *
 * 排法用与 dev `HomeWidgetLayout.pack` **逐行等价**的 first-fit：从上到下、从左到右找第一个
 * 放得下的位置，小格回填前面的空洞。所以迁完之后每块的列/行位置与升级前看到的完全一致。
 */
object HomeWidgetMigration {

    /** 一格是「列 × 行」，与卡片组的格坐标同一套语义。 */
    private data class Size(val cols: Int, val rows: Int)

    private val SIZES = mapOf(
        "1x1" to Size(1, 1),
        "2x1" to Size(2, 1),
        "1x2" to Size(1, 2),
        "2x2" to Size(2, 2),
    )

    private const val COLUMNS = 4

    /** 解析出来的一行：路由 id + 尺寸。 */
    private data class LegacyTile(val routeId: String, val size: Size)

    /**
     * @param lines `home_widgets` 拆出来的行（已去掉空行）。
     * @param available 当前目录里还有的路由 id；已经不存在的功能直接丢掉，免得留下点不开的块。
     * @return 迁好的整页布局；一块都解析不出来就返回 null（调用方退回默认布局）。
     */
    fun decode(lines: List<String>, available: Set<String>): HomeLayout? {
        val groups = mutableListOf<Triple<String, String, List<LegacyTile>>>()
        if (lines.none { it.startsWith("#") }) {
            groups += Triple("main", "", parseTiles(lines, available))
        } else {
            var header: List<String>? = null
            val body = mutableListOf<String>()
            fun flush() {
                val h = header ?: return
                val id = h.getOrElse(0) { "main" }.ifBlank { "main" }
                groups += Triple(id, h.getOrElse(1) { "" }, parseTiles(body, available))
                body.clear()
            }
            lines.forEach { line ->
                if (line.startsWith('#')) {
                    flush()
                    header = line.drop(1).split('\t')
                } else {
                    body += line
                }
            }
            flush()
        }

        // 块 id 要全局唯一（`locate` / `key` 都靠它），这里按「组/路由#序号」重编一遍；
        // 同一个功能本来就允许出现在不同组里（常用功能就是这么来的），所以不能只用路由 id。
        var serial = 0
        val converted = groups.mapNotNull { (id, title, tiles) ->
            if (tiles.isEmpty()) return@mapNotNull null
            HomeGroup(
                id = id,
                title = title,
                tiles = pack(tiles).map { (routeId, box) ->
                    HomeTile(
                        id = "$id/$routeId#${serial++}",
                        routeId = routeId,
                        x = box.x,
                        y = box.y,
                        w = box.w,
                        h = box.h,
                    )
                },
            )
        }
        if (converted.isEmpty()) return null

        // 老数据里同一个组 id 出现过两次就合并（否则 `group()` 只会取到第一份）
        return HomeLayout(
            converted.groupBy { it.id }.map { (id, same) ->
                HomeGroup(
                    id = id,
                    title = same.first().title,
                    tiles = same.flatMap { it.tiles },
                )
            },
        )
    }

    /** 一组里的 `路由id:尺寸` 行 → 去重、按目录过滤后的块。 */
    private fun parseTiles(lines: List<String>, available: Set<String>): List<LegacyTile> {
        val seen = mutableSetOf<String>()
        return lines.mapNotNull { raw ->
            val id = raw.substringBefore(':').trim()
            val size = SIZES[raw.substringAfter(':', "").trim()] ?: return@mapNotNull null
            if (id.isEmpty() || id !in available || !seen.add(id)) return@mapNotNull null
            LegacyTile(id, size)
        }
    }

    /** first-fit 排位：返回「路由 id → 列/行/宽/高」（与 dev 的 `pack` 同一套）。 */
    private fun pack(tiles: List<LegacyTile>): List<Pair<String, Box>> {
        val taken = mutableListOf<BooleanArray>()
        fun row(r: Int): BooleanArray {
            while (taken.size <= r) taken.add(BooleanArray(COLUMNS))
            return taken[r]
        }
        fun fits(c: Int, r: Int, size: Size): Boolean {
            if (c + size.cols > COLUMNS) return false
            for (dr in 0 until size.rows) {
                for (dc in 0 until size.cols) if (row(r + dr)[c + dc]) return false
            }
            return true
        }
        return tiles.map { tile ->
            var r = 0
            var spot: Pair<Int, Int>? = null
            while (spot == null) {
                for (c in 0..COLUMNS - tile.size.cols) {
                    if (fits(c, r, tile.size)) {
                        spot = c to r
                        break
                    }
                }
                if (spot == null) r++
            }
            val (c0, r0) = spot!!
            for (dr in 0 until tile.size.rows) {
                for (dc in 0 until tile.size.cols) row(r0 + dr)[c0 + dc] = true
            }
            tile.routeId to Box(
                x = c0.toFloat(),
                y = r0.toFloat(),
                w = tile.size.cols.toFloat(),
                h = tile.size.rows.toFloat(),
            )
        }
    }

    /** 一块在格坐标系里的落位。 */
    private data class Box(val x: Float, val y: Float, val w: Float, val h: Float)
}
