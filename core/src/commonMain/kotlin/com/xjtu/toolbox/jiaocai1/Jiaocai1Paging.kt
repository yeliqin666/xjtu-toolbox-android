package com.xjtu.toolbox.jiaocai1

/**
 * @param index     阅读器页序，从 0 起
 * @param typeIndex 页型下标，对应 [Jiaocai1Paging.TYPE_NAMES]
 * @param numInType 页型内部编号
 * @param fileName  服务端图片名，不含扩展名
 */
data class Jiaocai1Page(
    val index: Int,
    val typeIndex: Int,
    val numInType: Int,
    val fileName: String,
) {
    val typeName: String get() = Jiaocai1Paging.TYPE_NAMES[typeIndex]

    /** 页标：正文只显示页码，其它页型带类型名 */
    val label: String
        get() = if (typeIndex == Jiaocai1Paging.TYPE_CONTENT) "第 $numInType 页"
        else if (Jiaocai1Paging.pageCountHint(typeIndex) == 1) typeName
        else "$typeName $numInType"
}

/**
 * 页面命名规则，移植自阅读器的 `pagetypeutil.js`。
 *
 * 一本书拆成 8 类页，每类各自从 1 编号，文件名 = 类型前缀 + 补零到总长 6：
 * 正文首页 `000001`、目录首页 `!00001`、封面 `cov001`。
 * 封底复用 `cov` 前缀且编号从 2 起（`cov002`），所以前缀表里 cov 出现两次。
 */
object Jiaocai1Paging {

    const val TYPE_COUNT = 8
    const val TYPE_CONTENT = 5

    /** 文件名前缀，下标即页型；正文无前缀 */
    private val PREFIXES = listOf("cov", "bok", "leg", "fow", "!", "", "att", "cov")

    val TYPE_NAMES = listOf("封面", "书名页", "版权页", "前言", "目录", "正文", "附录", "封底")

    /** 这些页型通常只有一页，页标不必带编号 */
    private val SINGLE_PAGE_TYPES = setOf(0, 1, 2, 7)

    internal fun pageCountHint(typeIndex: Int): Int = if (typeIndex in SINGLE_PAGE_TYPES) 1 else 2

    /** 前缀 + 补零到 6 位：`fileName(1, 5) == "000001"`，`fileName(1, 4) == "!00001"`。 */
    fun fileName(numInType: Int, typeIndex: Int): String {
        val prefix = PREFIXES[typeIndex]
        val digits = numInType.toString()
        val pad = (6 - prefix.length - digits.length).coerceAtLeast(0)
        return prefix + "0".repeat(pad) + digits
    }

    /** 把 reader.shtml 给的 8 组 [起, 止] 展开成线性页表；`起 > 止` 的页型跳过。 */
    fun flatten(ranges: List<IntRange>): List<Jiaocai1Page> {
        val out = ArrayList<Jiaocai1Page>()
        ranges.forEachIndexed { typeIndex, range ->
            if (range.first > range.last || range.first <= 0) return@forEachIndexed
            for (n in range.first..range.last) {
                out += Jiaocai1Page(
                    index = out.size,
                    typeIndex = typeIndex,
                    numInType = n,
                    fileName = fileName(n, typeIndex),
                )
            }
        }
        return out
    }

    /** 各页型首页在线性页表里的下标，用于跳章导航。 */
    fun sectionStarts(pages: List<Jiaocai1Page>): List<Pair<Int, Int>> =
        pages.groupBy { it.typeIndex }
            .map { (type, ps) -> type to ps.first().index }
            .sortedBy { it.second }
}
