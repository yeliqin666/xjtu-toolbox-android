package com.xjtu.toolbox.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 首页网格的挤开与吸附规则（需求 §4.3、§4.4）。
 *
 * 这一组测试是「重构拖动算法」时唯一能挡回归的东西：手势与渲染都在 Compose 里跑不起来，
 * 但落位规则是纯函数，任何一端的结果都该一样。
 */
class HomeGridMathTest {

    private fun tile(
        id: String,
        x: Float,
        y: Float,
        w: Float = 2f,
        h: Float = 1f,
    ) = HomeTile(id = id, routeId = id, x = x, y = y, w = w, h = h)

    // ── 基本几何 ──

    @Test
    fun `贴边不算重叠`() {
        val left = tile("a", 0f, 0f)
        val right = tile("b", 2f, 0f)
        assertFalse(HomeGridMath.overlaps(left, right))
        assertTrue(HomeGridMath.overlaps(left, tile("c", 1f, 0f)))
        // 差一丝也算重叠（自由拖拽时会碰上）
        assertTrue(HomeGridMath.overlaps(left, tile("d", 1.5f, 0f)))
    }

    @Test
    fun `尺寸夹在 0点7 到 4 之间并吸整`() {
        assertEquals(1f, HomeGridMath.clampSpan(0.1f, grid = false), 0.0001f)
        assertEquals(4f, HomeGridMath.clampSpan(9f, grid = true), 0.0001f)
        assertEquals(2f, HomeGridMath.clampSpan(2.4f, grid = true), 0.0001f)
        assertEquals(2.4f, HomeGridMath.clampSpan(2.4f, grid = false), 0.0001f)
    }

    @Test
    fun `夹回组内：横向不出界、纵向不出负数`() {
        val clamped = HomeGridMath.clampToBoard(tile("a", 3.5f, -2f), grid = true)
        assertEquals(2f, clamped.x, 0.0001f) // 4 列 - 宽 2 = 最多摆到 2
        assertEquals(0f, clamped.y, 0.0001f)
    }

    // ── 组内拖拽 ──

    @Test
    fun `同尺寸被挤开的块回到拖拽块原来的位置`() {
        val a = tile("a", 0f, 0f)
        val b = tile("b", 2f, 0f)
        val moved = HomeGridMath.placeInGroup(listOf(a, b), a.copy(x = 2f), grid = true)

        val newA = moved.first { it.id == "a" }
        val newB = moved.first { it.id == "b" }
        assertEquals(2f, newA.x, 0.0001f)
        assertEquals(0f, newB.x, 0.0001f) // 让到 a 原来的坑
        assertEquals(0f, newB.y, 0.0001f)
    }

    @Test
    fun `更大的被挤开块就近挪到空位而不是沉底`() {
        val small = tile("small", 0f, 0f, w = 1f, h = 1f)
        val big = tile("big", 1f, 0f, w = 2f, h = 1f)
        val moved = HomeGridMath.placeInGroup(listOf(small, big), small.copy(x = 1f), grid = true)

        val newBig = moved.first { it.id == "big" }
        val newSmall = moved.first { it.id == "small" }
        assertEquals(1f, newSmall.x, 0.0001f)
        // 流式重排：big 被摘除后重插到最近的空位——(0,0) 会和 small 重叠，(2,0) 放得下
        assertEquals(2f, newBig.x, 0.0001f)
        assertEquals(0f, newBig.y, 0.0001f)
    }

    @Test
    fun `被挤开的块落回拖拽块留下的原槽位就是交换`() {
        // A(0,0) B(2,0) 都是 2×1；把 A 拖到整行最右的下一行，B 不动，A 落新行
        val a = tile("a", 0f, 0f)
        val b = tile("b", 2f, 0f)
        val moved = HomeGridMath.placeInGroup(listOf(a, b), a.copy(x = 0f, y = 1f), grid = true)
        assertEquals(1f, moved.first { it.id == "a" }.y, 0.0001f)
        assertEquals(2f, moved.first { it.id == "b" }.x, 0.0001f)
        assertEquals(0f, moved.first { it.id == "b" }.y, 0.0001f)
    }

    @Test
    fun `多块被压时按阅读顺序依次重插且不重叠`() {
        // 2×2 大块往下挪一格，压到 a/b（c 与它贴边，不被压）
        val tiles = listOf(
            tile("big", 0f, 0f, w = 2f, h = 2f),
            tile("a", 0f, 2f, w = 1f, h = 1f),
            tile("b", 1f, 2f, w = 1f, h = 1f),
            tile("c", 2f, 2f, w = 1f, h = 1f),
        )
        val moved = HomeGridMath.placeInGroup(tiles, tiles[0].copy(y = 2f), grid = true)
        assertEquals(2f, moved.first { it.id == "big" }.y, 0.0001f)
        // a/b 按阅读顺序重插到最上方的空位；c 贴边不算被压，原地不动
        assertEquals(0f, moved.first { it.id == "a" }.y, 0.0001f)
        assertEquals(0f, moved.first { it.id == "b" }.y, 0.0001f)
        assertEquals(2f, moved.first { it.id == "c" }.y, 0.0001f)
        moved.indices.forEach { i ->
            (i + 1 until moved.size).forEach { j ->
                assertFalse(
                    HomeGridMath.overlaps(moved[i], moved[j]),
                    "${moved[i].id} 与 ${moved[j].id} 重叠了：$moved",
                )
            }
        }
    }

    @Test
    fun `拖到原位的落位是幂等的`() {
        val tiles = listOf(
            tile("a", 0f, 0f),
            tile("b", 2f, 0f),
            tile("c", 0f, 1f),
        )
        // 拖动中每一帧都会按当前落点重算预览：落点没越过格线时结果必须和输入一模一样
        val same = HomeGridMath.placeInGroup(tiles, tiles[0].copy(), grid = true)
        tiles.forEach { old ->
            val new = moved(old.id, same)
            assertEquals(old.x, new.x, 0.0001f)
            assertEquals(old.y, new.y, 0.0001f)
        }
    }

    private fun moved(id: String, tiles: List<HomeTile>): HomeTile = tiles.first { it.id == id }

    @Test
    fun `只挤开直接重叠的块，不连锁`() {
        val a = tile("a", 0f, 0f)
        val b = tile("b", 2f, 0f)
        val c = tile("c", 0f, 1f)
        val moved = HomeGridMath.placeInGroup(listOf(a, b, c), a.copy(x = 2f), grid = true)

        val newC = moved.first { it.id == "c" }
        assertEquals(0f, newC.x, 0.0001f) // 谁也没碰它
        assertEquals(1f, newC.y, 0.0001f)
    }

    @Test
    fun `落位结果永远不重叠`() {
        val tiles = listOf(
            tile("a", 0f, 0f),
            tile("b", 2f, 0f),
            tile("c", 0f, 1f),
            tile("d", 2f, 1f),
        )
        listOf(0f, 0.5f, 1f, 1.7f, 2f, 3f).forEach { x ->
            listOf(0f, 0.6f, 1f).forEach { y ->
                val result = HomeGridMath.placeInGroup(tiles, tiles[0].copy(x = x, y = y), grid = false)
                result.indices.forEach { i ->
                    (i + 1 until result.size).forEach { j ->
                        assertFalse(
                            HomeGridMath.overlaps(result[i], result[j]),
                            "落点 ($x, $y) 之后 ${result[i].id} 与 ${result[j].id} 重叠了",
                        )
                    }
                }
            }
        }
    }

    // ── 跨组拖拽 ──

    @Test
    fun `跨组被挤开的块绝不离开本组`() {
        val target = listOf(tile("a", 0f, 0f), tile("b", 2f, 0f))
        val moved = tile("fromOther", 0f, 0f)
        val result = HomeGridMath.placeAcross(target, moved, grid = true)

        assertTrue(result.any { it.id == "fromOther" })
        // 只有真的被压到的 a 让位；b 与落点不重叠，原地不动（不做连锁位移）
        val newB = result.first { it.id == "b" }
        assertEquals(2f, newB.x, 0.0001f)
        assertEquals(0f, newB.y, 0.0001f)
        // a 让到哪里都行，但必须还在本组里（0..COLUMNS），而且组内不能重叠
        val newA = result.first { it.id == "a" }
        assertTrue(newA.x >= 0f && newA.x <= HomeGridMath.COLUMNS)
        assertTrue(newA.y >= 0f)
        result.indices.forEach { i ->
            (i + 1 until result.size).forEach { j ->
                assertFalse(HomeGridMath.overlaps(result[i], result[j]))
            }
        }
    }

    @Test
    fun `跨组挤开优先填本组空位`() {
        // 右上角是空的：被挤开的块应该就近填进去，而不是直接沉到最下方
        val target = listOf(tile("a", 0f, 0f, w = 1f, h = 1f))
        val moved = tile("fromOther", 0f, 0f, w = 1f, h = 1f)
        val result = HomeGridMath.placeAcross(target, moved, grid = true)

        val newA = result.first { it.id == "a" }
        assertEquals(1f, newA.x, 0.0001f)
        assertEquals(0f, newA.y, 0.0001f)
    }

    // ── 自由拖拽的边界联动 ──

    @Test
    fun `自由拖拽压到邻居时邻居被压缩而不是挤走`() {
        val a = tile("a", 0f, 0f, w = 2f, h = 1f)
        val b = tile("b", 2f, 0f, w = 2f, h = 1f)
        // a 向右拖到压住 b 的左半：b 的左边界被收拢让出空间，而不是被挤到最下方
        val result = HomeGridMath.placeInGroup(listOf(a, b), a.copy(x = 1f), grid = false)
        val newA = result.first { it.id == "a" }
        val newB = result.first { it.id == "b" }
        assertFalse(HomeGridMath.overlaps(newA, newB))
        // b 还在原来那一行，只是宽度被压缩
        assertEquals(0f, newB.y, 0.0001f)
        assertTrue(newB.w < 2f, "b 应该被压缩：$newB")
    }

    @Test
    fun `自由拖拽把邻居整个盖住时才挤走`() {
        val a = tile("a", 0f, 0f, w = 2f, h = 1f)
        val b = tile("b", 2f, 0f, w = 2f, h = 1f)
        // a 拖到和 b 完全同位（2,0）：b 没有任何一侧能收拢，只能去最下方
        val result = HomeGridMath.placeInGroup(listOf(a, b), a.copy(x = 2f), grid = false)
        val newB = result.first { it.id == "b" }
        assertTrue(newB.y >= 1f, "b 被整个盖住，应该被挤到下面：$newB")
    }

    // ── 空行压缩 ──

    @Test
    fun `拖走后全空行被删掉下面的块跟上来`() {
        val tiles = listOf(
            tile("a", 0f, 0f),
            tile("b", 2f, 0f),
            tile("c", 0f, 2f), // 第 1 行是空的
        )
        val compacted = HomeGridMath.compactEmptyRows(tiles)
        assertEquals(1f, compacted.first { it.id == "c" }.y, 0.0001f)
        assertEquals(0f, compacted.first { it.id == "a" }.y, 0.0001f)
    }

    @Test
    fun `没有空行时压缩不动任何块`() {
        val tiles = listOf(tile("a", 0f, 0f), tile("b", 2f, 0f))
        assertEquals(tiles, HomeGridMath.compactEmptyRows(tiles))
    }

    @Test
    fun `只剩一块时也把上面的空行收掉`() {
        // 两块一组、把上面那块拖走：只剩下面那块（y = 1），它必须被提回第一行，
        // 否则那一行空着，「空行自动向上」在单块组里就失效了
        val alone = listOf(tile("b", 0f, 1f))
        val compacted = HomeGridMath.compactEmptyRows(alone)
        assertEquals(0f, compacted.single().y, 0.0001f)
    }

    @Test
    fun `单块本来就在第一行时不动它`() {
        val alone = listOf(tile("b", 0f, 0f))
        assertEquals(alone, HomeGridMath.compactEmptyRows(alone))
    }

    @Test
    fun `读盘时把存档里遗留的空行收掉`() {
        // 老版本存档里可能留着空行（拖动中被行数预算钉着、松手也没收）：读盘时补一遍，
        // 否则用户升级完第一眼看到的还是那片空行
        val holed = HomeLayout(
            groups = listOf(HomeGroup(id = "g", title = "组", tiles = listOf(tile("a", 0f, 2f)))),
        )
        val fixed = holed.compactGaps(grid = true)
        assertEquals(0f, fixed.group("g")!!.tiles.single().y, 0.0001f)
        // 自由模式允许留空隙：原样返回（连对象都不换）
        assertTrue(holed.compactGaps(grid = false) === holed)
        // 本来就没空行时也不换对象
        val tidy = HomeLayout(groups = listOf(HomeGroup(id = "g", tiles = listOf(tile("a", 0f, 0f)))))
        assertTrue(tidy.compactGaps(grid = true) === tidy)
    }

    @Test
    fun `自由拖拽贴近邻居的边就吸过去`() {
        val anchor = tile("anchor", 0f, 0f)
        // 右边差 0.05 格；容差 0.28 之内 → 吸到 2f
        val snapped = HomeGridMath.snapToNeighbors(tile("m", 1.95f, 0f), listOf(anchor), tolerance = 0.28f)
        assertEquals(2f, snapped.x, 0.0001f)
    }

    @Test
    fun `离得远的边不吸`() {
        val anchor = tile("anchor", 0f, 0f)
        val snapped = HomeGridMath.snapToNeighbors(tile("m", 2.6f, 0f), listOf(anchor), tolerance = 0.28f)
        assertEquals(2.6f, snapped.x, 0.0001f)
    }

    // ── 缩放 ──

    @Test
    fun `放大到压住邻居时邻居被挤开且不重叠`() {
        val a = tile("a", 0f, 0f, w = 2f, h = 1f)
        val b = tile("b", 2f, 0f, w = 2f, h = 1f)
        val result = HomeGridMath.resizeInGroup(listOf(a, b), a.copy(w = 3f), grid = true)

        assertFalse(HomeGridMath.overlaps(result[0], result[1]))
        assertEquals(0f, result[1].x, 0.0001f)
        assertEquals(1f, result[1].y, 0.0001f)
    }

    // ── 空位与底部 ──

    @Test
    fun `空位从上往下扫`() {
        val tiles = listOf(tile("a", 0f, 0f))
        assertEquals(2f to 0f, HomeGridMath.firstFreeSlot(tiles, 2f, 1f))
    }

    @Test
    fun `整行满时空位落到下一行`() {
        val tiles = listOf(tile("a", 0f, 0f), tile("b", 2f, 0f))
        assertEquals(0f to 1f, HomeGridMath.firstFreeSlot(tiles, 2f, 1f))
    }

    // ── 行数预算（拖动中把组高钉死，见 §22b） ──

    @Test
    fun `空位扫描被行数上界挡住`() {
        // 第 1 行有一个空位，但预算只有 1 行（y < 1），扫不到 → null
        val tiles = listOf(tile("a", 0f, 0f), tile("b", 2f, 0f))
        assertNull(HomeGridMath.firstFreeSlot(tiles, 2f, 1f, maxRows = 1f))
        // 预算放宽到 2 行就能扫到第 1 行
        assertEquals(0f to 1f, HomeGridMath.firstFreeSlot(tiles, 2f, 1f, maxRows = 2f))
    }

    @Test
    fun `拖动中落位被夹进预算行数组高不变`() {
        // 1×1 的组（预算 1 行），往里拖一块：落点被夹在 y < 1，组不会长高
        val a = tile("a", 0f, 0f, w = 1f, h = 1f)
        val moved = HomeGridMath.placeInGroup(listOf(a), a.copy(x = 3f), grid = true, maxRows = 1f)
        assertTrue(HomeGridMath.contentBottom(moved) <= 1f + 0.0001f, "预算 1 行却长高了：$moved")
        assertEquals(3f, moved.first { it.id == "a" }.x, 0.0001f)
    }

    @Test
    fun `被挤开的块重插也受预算约束`() {
        // 预算 1 行：a、b 同在第 0 行；把 a 拖到 b 上，b 被挤走但只能在预算内找空位
        val a = tile("a", 0f, 0f, w = 2f, h = 1f)
        val b = tile("b", 2f, 0f, w = 2f, h = 1f)
        val moved = HomeGridMath.placeInGroup(listOf(a, b), a.copy(x = 2f), grid = true, maxRows = 1f)
        assertTrue(HomeGridMath.contentBottom(moved) <= 1f + 0.0001f, "预算 1 行却长高了：$moved")
        // 交换仍成立：b 回到 a 的原槽位
        assertEquals(2f, moved.first { it.id == "a" }.x, 0.0001f)
        assertEquals(0f, moved.first { it.id == "b" }.x, 0.0001f)
    }

    @Test
    fun `预算比块还矮时夹无可夹允许溢出`() {
        // 往一个 1 行的预算里放 2 行高的块：夹无可夹，原样放进去（松手压缩后自愈），
        // 总比硬压成 1 行或丢掉诚实
        val big = tile("big", 0f, 5f, w = 2f, h = 2f)
        val moved = HomeGridMath.placeInGroup(emptyList(), big, grid = true, maxRows = 1f)
        assertEquals(5f, moved.first { it.id == "big" }.y, 0.0001f)
    }

    @Test
    fun `不给预算就是松手语义组可长高并压空行`() {
        val tiles = listOf(tile("a", 0f, 0f), tile("b", 2f, 0f))
        // maxRows = null：允许落到新行
        assertEquals(0f to 1f, HomeGridMath.firstFreeSlot(tiles, 2f, 1f, maxRows = null))
        val placed = HomeGridMath.placeInGroup(tiles, tile("c", 0f, 1f), grid = true, maxRows = null)
        assertTrue(HomeGridMath.contentBottom(placed) >= 2f, "放开预算应能长高：$placed")
    }

    // ── 最下方落位与底部 ──

    @Test
    fun `最下方落位避开已有内容`() {
        val tiles = listOf(tile("a", 0f, 0f), tile("b", 0f, 2f))
        val slot = HomeGridMath.bottomSlot(tiles, tile("c", 0f, 0f))
        assertEquals(0f, slot.x, 0.0001f)
        assertEquals(3f, slot.y, 0.0001f)
    }

    @Test
    fun `内容底部按最靠下那一块算`() {
        assertEquals(0f, HomeGridMath.contentBottom(emptyList()), 0.0001f)
        assertEquals(3f, HomeGridMath.contentBottom(listOf(tile("a", 0f, 0f), tile("b", 0f, 2f))), 0.0001f)
    }

    // ── 组排序的几何 ──

    @Test
    fun `组排序中心没过中线就不换序`() {
        val heights = listOf(100f, 80f, 120f)
        // 第 0 组往下拖 49px：中心 50+49=99，下一组中线 100+40=140，不换
        assertEquals(0, HomeGridMath.reorderTargetIndex(heights, 0, 49f))
        // 拖过下一组中线：中心 50+100=150 > 140，换到 1
        assertEquals(1, HomeGridMath.reorderTargetIndex(heights, 0, 100f))
        // 再拖过第 2 组中线（180+60=240）：中心 50+200=250 > 240，换到 2
        assertEquals(2, HomeGridMath.reorderTargetIndex(heights, 0, 200f))
    }

    @Test
    fun `组排序往上拖过中线就换序`() {
        val heights = listOf(100f, 80f, 120f)
        // 第 2 组往上拖：原中心 100+80+60=240；上一组中线 100+40=140，拖 -110 → 中心 130 < 140 换到 1
        assertEquals(1, HomeGridMath.reorderTargetIndex(heights, 2, -110f))
        // 继续越过第 0 组中线 50：中心 240-200=40 < 50，换到 0
        assertEquals(0, HomeGridMath.reorderTargetIndex(heights, 2, -200f))
        // 没越过就原地
        assertEquals(2, HomeGridMath.reorderTargetIndex(heights, 2, -20f))
    }

    @Test
    fun `让位表只动夹在中间的组`() {
        val heights = listOf(100f, 80f, 120f, 90f)
        // 0 → 2：第 1、2 组上移一个第 0 组的高度，第 3 组不动
        val down = HomeGridMath.reorderShifts(heights, 0, 2)
        assertEquals(mapOf(1 to -100f, 2 to -100f), down)
        // 3 → 1：第 1、2 组下移一个第 3 组的高度（被拖组自己不在表里）
        val up = HomeGridMath.reorderShifts(heights, 3, 1)
        assertEquals(mapOf(1 to 90f, 2 to 90f), up)
        // 不换序 = 空表
        assertTrue(HomeGridMath.reorderShifts(heights, 1, 1).isEmpty())
    }
}

/** 首页布局的增删改与落盘格式。 */
class HomeLayoutTest {

    private fun layout(): HomeLayout = HomeLayout(
        groups = listOf(
            HomeGroup(
                id = "quick",
                title = "常用功能",
                colorHex = "#2F7CFF",
                tiles = listOf(
                    HomeTile("quick/0", "schedule", 0f, 0f, 2f, 1f),
                    HomeTile("quick/1", "campus_card", 2f, 0f, 2f, 1f),
                ),
            ),
            HomeGroup(
                id = "CLASS",
                title = "教务与课程",
                tiles = listOf(HomeTile("CLASS/schedule", "schedule", 0f, 0f, 2f, 1f)),
            ),
        ),
    )

    @Test
    fun `落盘之后读回来一模一样`() {
        val original = layout()
        val decoded = HomeLayoutCodec.decode(HomeLayoutCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `组标题里的竖线和换行不会把格式撑坏`() {
        val weird = layout().renameGroup("quick", "常|用\n功能")
        val decoded = HomeLayoutCodec.decode(HomeLayoutCodec.encode(weird))
        assertEquals("常|用\n功能", decoded?.group("quick")?.title)
    }

    @Test
    fun `坏数据一律回落到 null`() {
        assertNull(HomeLayoutCodec.decode(null))
        assertNull(HomeLayoutCodec.decode(""))
        assertNull(HomeLayoutCodec.decode("v0\ng|a||b"))
        assertNull(HomeLayoutCodec.decode("随便什么"))
    }

    @Test
    fun `同组移动把邻居挤到原位置`() {
        val moved = layout().moveTile("quick/0", "quick", x = 2f, y = 0f, grid = true)
        val group = moved.group("quick")!!
        assertEquals(2f, group.tiles.first { it.id == "quick/0" }.x, 0.0001f)
        assertEquals(0f, group.tiles.first { it.id == "quick/1" }.x, 0.0001f)
    }

    @Test
    fun `跨组移动会从原组摘掉并留在目标组`() {
        val moved = layout().moveTile("quick/1", "CLASS", x = 0f, y = 3f, grid = true)
        assertEquals(1, moved.group("quick")!!.tiles.size)
        assertEquals(2, moved.group("CLASS")!!.tiles.size)
        assertTrue(moved.group("CLASS")!!.tiles.any { it.id == "quick/1" })
    }

    @Test
    fun `添加功能不会重复、删除能删干净`() {
        val added = layout().addTile("CLASS", "library", "CLASS/library")
        assertEquals(2, added.group("CLASS")!!.tiles.size)
        // 同一个路由再加一次不生效
        assertEquals(added, added.addTile("CLASS", "library", "CLASS/library/x"))

        val removed = added.removeTile("CLASS/library")
        assertEquals(1, removed.group("CLASS")!!.tiles.size)
    }

    @Test
    fun `改组名与标题颜色`() {
        val renamed = layout().renameGroup("CLASS", "").tintGroup("CLASS", "#FF3B30")
        assertEquals("", renamed.group("CLASS")!!.title)
        assertEquals("#FF3B30", renamed.group("CLASS")!!.colorHex)
    }

    @Test
    fun `新块 id 全局递增不与删过的块撞号`() {
        // 尾号只涨不回退：删掉一块再加，新块也不会复用已存在的尾号
        val withOne = layout().addTile("CLASS", "library", layout().nextTileId("CLASS", "library"))
        val first = withOne.group("CLASS")!!.tiles.last().id
        val withTwo = withOne.addTile("CLASS", "notice", withOne.nextTileId("CLASS", "notice"))
        val second = withTwo.group("CLASS")!!.tiles.last().id
        assertTrue(first != second, "连续添加的两块撞了 id：$first")
        val removed = withTwo.removeTile(first)
        val third = removed.addTile("CLASS", "library", removed.nextTileId("CLASS", "library"))
        val ids = third.group("CLASS")!!.tiles.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "出现了重复 id：$ids")
    }

    @Test
    fun `读盘时撞号的块被重编而不是弄丢`() {
        val text = buildString {
            append("v1\n")
            append("g|quick||常用功能\n")
            append("t|quick|a|schedule|0|0|2000|1000\n")
            append("t|quick|a|library|2000|0|2000|1000\n")
        }
        val decoded = HomeLayoutCodec.decode(text)!!
        val ids = decoded.group("quick")!!.tiles.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        // 两个功能都还在
        assertTrue(decoded.group("quick")!!.tiles.any { it.routeId == "library" })
    }

    @Test
    fun `拖动组调顺序`() {
        val reordered = layout().moveGroup("CLASS", 0)
        assertEquals("CLASS", reordered.groups[0].id)
        assertEquals("quick", reordered.groups[1].id)
    }

    @Test
    fun `组排序按可见序列挪位时不可见组原地不动`() {
        // 全量序列 [quick(不可见), A, B, C]，可见序列 [A, B, C]：把 A 挪到可见序列末尾
        val base = HomeLayout(
            groups = listOf(
                HomeGroup(id = "quick"),
                HomeGroup(id = "A"),
                HomeGroup(id = "B"),
                HomeGroup(id = "C"),
            ),
        )
        val moved = base.moveGroupWithin("A", listOf("A", "B", "C"), 2)
        assertEquals(listOf("quick", "B", "C", "A"), moved.groups.map { it.id })
    }

    @Test
    fun `任何一次操作之后组内都不重叠`() {
        val moved = layout()
            .moveTile("quick/1", "quick", x = 0f, y = 0f, grid = true)
            .resizeTile("quick/0", 3f, 2f, grid = true)
            .addTile("quick", "library", "quick/9")
        assertTrue(moved.isConsistent(), "操作完出现了重叠：$moved")
    }
}
