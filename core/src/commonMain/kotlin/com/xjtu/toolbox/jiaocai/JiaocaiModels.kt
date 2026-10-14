package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.schedule.TextbookItem

/**
 * 教材中心（`jiaocai.lib.xjtu.edu.cn`）的**模型** —— 搬迁前它长在 `:app` 的 `JiaocaiApi.kt` 里
 * （取数实现那个文件），搬进共享内核时按老规矩拆开：模型与取数端口在 `:core`（屏要用），
 * 取数实现在 `:data`（它要 okhttp）。两边**同包同名**，所以 `:data` 那份 `JiaocaiApi` 里
 * 一行 import 都不用加，`JiaocaiBook` 的字段口径也逐字沿用搬迁前。
 */
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

/**
 * 「查教材」栏里「本学期我的教材」那一段要的一行。
 *
 * ## 为什么另立一个类型，而不是直接用 `:app` 的 `TextbookItem`
 *
 * 那一段的数据来自**教务**（`ScheduleCache` 里 `schedule_textbooks_$term` 那份缓存，
 * 见 `:app` 的 `ScheduleCache.readTextbooks`），`TextbookItem` 是与课表/考试挤在同一个
 * `:app` 文件里的实体 —— 它不属于 `jiaocai` 域，搬它会把课表那一摊一起拖进来。
 * 而这一屏真正要的只有下面这几个字段（课程名 + 书 + 一键读全文所需的 isbn），
 * 所以按 `Jiaocai1ShelfRecord` 的老规矩切一条窄缝：**宿主自己映射**成它。
 *
 * 搬迁前那个 `hasSubstantiveTextbook` 判据（服务端对无教材课程回书名「无教材」）
 * 由端口实现方在读完缓存后滤掉 —— 那是「从哪读」那一半的事。
 */
data class JiaocaiTermTextbook(
    val courseName: String,
    val textbookName: String,
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
)

/**
 * 「本学期我的教材」那一行从**教务**那份（`:core` 的 [TextbookItem]）过来的映射。
 *
 * serve 契约的 `GET /api/jwxt/textbooks`（§5.8）端的就是 [TextbookItem] 九格，而 `:web` 那一端的
 * 宿主端口 [JiaocaiTermTextbooks] 吃的是 [JiaocaiTermTextbook] ⇒ 中间这一层就是它。
 *
 * 两件事与 `:app` 的 `AppJiaocaiTermTextbooks`（那边读 `ScheduleCache`）**逐字一致**：
 * ①「无教材」那些行滤掉（[TextbookItem.hasSubstantiveTextbook]，与屏上那条判据同一个）；
 * ② 只搬那五格。顺序**原样**（不排序）：`:app` 那份的顺序是缓存给的，屏自己不排。
 *
 * ⚠️ `:app` 那一份没有改成调这个函数（本次是重构收尾，只动三端不一致那几处）—— 两处现在是
 * 同一套口径的两份写法，理由写在 `docs/desktop-port-plan.md` §16.5 的 D15 里。
 */
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
