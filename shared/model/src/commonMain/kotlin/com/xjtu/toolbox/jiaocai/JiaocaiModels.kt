package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.schedule.TextbookItem

/** 教材中心（`jiaocai.lib.xjtu.edu.cn`）的模型。 */
data class JiaocaiBook(
    val id: String = "",          // general_55258665
    val appId: Int = 0,
    val engineInstanceId: Int = 0,
    val title: String = "",
    val author: String = "",
    val summary: String = "",     // 包含课程名/获取方式等描述
    /** 全文库（jiaocai1.lib）书目号。「本地全文」链接直接带着它，非空即可在线阅读。 */
    val ssno: String? = null,
    /** 从描述拆出的键值对：课程名称、开课学院、教材名称、出版社、ISBN号等 */
    val fields: Map<String, String> = emptyMap(),
) {
    /** 列表接口只给"获取方式一：本地全文"这段文字，ssno 得进详情才拿得到 */
    val hasFullText: Boolean
        get() = ssno != null || fields.values.any { it.contains("本地全文") }
}

/** 「查教材」栏里「本学期我的教材」那一段要的一行。 */
data class JiaocaiTermTextbook(
    val courseName: String,
    val textbookName: String,
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
)

/** 「本学期我的教材」那一行从教务那份（`:core` 的 [TextbookItem]）过来的映射。 */
fun List<TextbookItem>.toJiaocaiTermTextbooks(): List<JiaocaiTermTextbook> =
    filter { it.hasSubstantiveTextbook }
        .map {
            JiaocaiTermTextbook(
                courseName = it.courseName,
                textbookName = it.textbookName,
                author = it.author,
                publisher = it.publisher,
                isbn = it.isbn,
            )
        }
