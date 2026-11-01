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
        assertEquals(0.7f, HomeGridMath.clampSpan(0.1f, grid = false), 0.0001f)
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
    fun `更大的被挤开块去本组最下方`() {
        val small = tile("small", 0f, 0f, w = 1f, h = 1f)
        val big = tile("big", 1f, 0f, w = 2f, h = 1f)
        val moved = HomeGridMath.placeInGroup(listOf(small, big), small.copy(x = 1f), grid = true)

        val newBig = moved.first { it.id == "big" }
        val newSmall = moved.first { it.id == "small" }
        assertEquals(1f, newSmall.x, 0.0001f)
        // 大块比拖拽块原尺寸（1×1）大，只能去最下面一行
        assertEquals(1f, newBig.y, 0.0001f)
        assertEquals(0f, newBig.x, 0.0001f)
    }

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
    fun `拖动组调顺序`() {
        val reordered = layout().moveGroup("CLASS", 0)
        assertEquals("CLASS", reordered.groups[0].id)
        assertEquals("quick", reordered.groups[1].id)
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
