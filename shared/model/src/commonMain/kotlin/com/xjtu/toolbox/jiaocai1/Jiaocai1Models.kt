package com.xjtu.toolbox.jiaocai1

/** 教材全文库（`jiaocai1.lib.xjtu.edu.cn`）的模型。 */

/** 中图法分类节点。[id] 即检索参数 `cls`，如 `0O109101`。 */
@kotlinx.serialization.Serializable
data class Jiaocai1Category(
    val id: String = "",
    val name: String = "",
    val level: Int = 0,
    val parentId: Int = 0,
    val nodeId: Int = 0,
    val children: List<Jiaocai1Category> = emptyList(),
)

/** [ssno] 是全文库主键，jiaocai.lib 的「本地全文」链接里带的就是它。 */
data class Jiaocai1Book(
    val ssno: String,
    val title: String,
    val author: String = "",
    val publishDate: String = "",
    val themeWord: String = "",
    val callNo: String = "",
    val classifyPath: String = "",
    val coverUrl: String = "",
)

data class Jiaocai1SearchResult(
    val books: List<Jiaocai1Book> = emptyList(),
    val totalRows: Int = 0,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
) {
    val hasMore: Boolean get() = currentPage < totalPages
}

/** 检索字段，只能单选。组合检索需走 stype=4 的高级检索表单。 */
enum class Jiaocai1SearchField(val key: String, val label: String) {
    BOOK_NAME("bookName", "书名"),
    AUTHOR("bookAuthor", "作者"),
    THEME("themeWord", "主题词"),
    CALL_NO("bookCallno", "索书号"),
    ISBN("bookISBN", "ISBN"),
}

/** [jpgPath] 是服务端按会话下发的令牌路径，不要持久化：换会话即失效，表现为取图失败，需重新 [Jiaocai1Source.openBook]。 */
data class Jiaocai1BookHandle(
    val ssno: String,
    val title: String,
    val jpgPath: String,
    /** 8 种页型各自的 [起, 止]；`起 > 止` 表示该书没有这类页 */
    val pageRanges: List<IntRange>,
    /** 正文页数 */
    val bookPages: Int,
) {
    /** 线性页表，下标即阅读器页序 */
    val pages: List<Jiaocai1Page> by lazy { Jiaocai1Paging.flatten(pageRanges) }
}
