package com.xjtu.toolbox.jiaocai1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 教材全文库的页面命名规则（[Jiaocai1Paging]，移植自阅读器的 `pagetypeutil.js`）在 JVM 上的口径。 */
class Jiaocai1PagingTest {

    @Test
    fun `文件名：页型前缀 + 补零到 6 位`() {
        assertEquals("000001", Jiaocai1Paging.fileName(1, 5), "正文没有前缀")
        assertEquals("000012", Jiaocai1Paging.fileName(12, 5))
        assertEquals("!00001", Jiaocai1Paging.fileName(1, 4), "目录前缀是 !")
        assertEquals("cov001", Jiaocai1Paging.fileName(1, 0), "封面 cov001")
        assertEquals("bok001", Jiaocai1Paging.fileName(1, 1))
        assertEquals("cov002", Jiaocai1Paging.fileName(2, 7), "封底复用 cov 前缀、编号从 2 起")
    }

    @Test
    fun `文件名：编号比补零位还长时不截断也不报错`() {
        assertEquals("1234567", Jiaocai1Paging.fileName(1_234_567, 5))
    }

    @Test
    fun `页标：正文只报页码，单页页型只报类型名，其余带编号`() {
        fun page(typeIndex: Int, num: Int) =
            Jiaocai1Page(index = 0, typeIndex = typeIndex, numInType = num, fileName = "")

        assertEquals("第 7 页", page(Jiaocai1Paging.TYPE_CONTENT, 7).label)
        assertEquals("封面", page(0, 1).label)
        assertEquals("封底", page(7, 2).label)
        assertEquals("目录 2", page(4, 2).label)
        assertEquals("正文", Jiaocai1Paging.TYPE_NAMES[Jiaocai1Paging.TYPE_CONTENT])
    }

    @Test
    fun `展开页表：8 组区间顺次编号，空区间与倒置区间跳过`() {
        val pages = Jiaocai1Paging.flatten(JIAOCAI1_TEST_RANGES)

        assertEquals(8, pages.size)
        assertEquals(
            listOf("cov001", "bok001", "!00001", "!00002", "000001", "000002", "000003", "cov002"),
            pages.map { it.fileName },
        )
        assertEquals(
            listOf(0, 1, 4, 4, 5, 5, 5, 7),
            pages.map { it.typeIndex },
            "页型下标就是区间在入参里的位置",
        )
        assertEquals(pages.indices.toList(), pages.map { it.index }, "index 由展开顺序重排")
        assertEquals(listOf(1, 1, 1, 2, 1, 2, 3, 2), pages.map { it.numInType })
    }

    @Test
    fun `展开页表：起为 0 的区间整段跳过（「没有这类页」的约定）`() {
        val pages = Jiaocai1Paging.flatten(listOf(0..3, 1..1))

        assertEquals(listOf("bok001"), pages.map { it.fileName })
    }

    @Test
    fun `跳章导航：各页型首面的下标，按页表顺序`() {
        val pages = Jiaocai1Paging.flatten(JIAOCAI1_TEST_RANGES)

        assertEquals(
            listOf(0 to 0, 1 to 1, 4 to 2, 5 to 4, 7 to 7),
            Jiaocai1Paging.sectionStarts(pages),
        )
        assertTrue(Jiaocai1Paging.sectionStarts(emptyList()).isEmpty())
    }

    private companion object {
        /** 一本书的 8 组页型区间（下标即页型，见 [Jiaocai1Paging.TYPE_NAMES]） */
        val JIAOCAI1_TEST_RANGES = listOf(
            1..1,
            1..1,
            0..-1,
            2..1,
            1..2,
            1..3,
            0..-1,
            2..2,
        )
    }
}
