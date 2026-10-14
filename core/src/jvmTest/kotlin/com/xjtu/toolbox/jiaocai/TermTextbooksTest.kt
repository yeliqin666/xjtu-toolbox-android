package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.schedule.TextbookItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [toJiaocaiTermTextbooks]（`:web` 那一端「本学期我的教材」的映射）的两条钉子。
 *
 * 为什么在这一层测：那一段的数据在**教务**（serve 契约 §5.8 的 `GET /api/jwxt/textbooks`，
 * `:core` 的 [TextbookItem]），而 `TextbookScreen` 的宿主端口吃的是 [JiaocaiTermTextbook]
 * ⇒ 中间这个映射是 `:web` 侧那段代码里**唯一的逻辑**（其余都是把两个端口接起来）。
 * `:web` 现在还没有测试源集（D14 才建），所以把这一层放在 `:core`（两端同一个判据、同一份代码）。
 *
 * 两条与 `:app` 的 `AppJiaocaiTermTextbooks`（读同一份教务数据的落盘缓存）**逐字同口径**：
 * ① 滤掉「无教材」那些行（[TextbookItem.hasSubstantiveTextbook]，与屏上断言的是同一个判据）；
 * ② 只搬那五格（`courseCode` / `price` / `edition` 不进 DTO —— 那一段用不上），顺序原样。
 */
class TermTextbooksTest {

    /** 正常教材留下、两档「算不上教材」的行被滤掉，五格逐字段搬。 */
    @Test
    fun substantiveRowsSurviveAndNonSubstantiveOnesAreDropped() {
        val real = TextbookItem(
            courseName = "线性代数",
            textbookName = "线性代数（第七版）",
            author = "同济大学数学系",
            publisher = "高等教育出版社",
            isbn = "9787040396614",
            price = "45.00",
            edition = "第7版",
            courseCode = "MATH1002",
        )
        // 「无教材」那一档：书名就是「无教材」（报表的写法），ISBN 是占位号
        val none = TextbookItem(
            courseName = "大学物理",
            textbookName = "无教材",
            isbn = "9780000000000",
            courseCode = "PHYS1002",
        )
        // 判据的另外两支也不该漏：书名只有一个字、又没有 ISBN 数字与作者 ⇒ 算不上教材
        val stub = TextbookItem(courseName = "工程训练", textbookName = "无", isbn = "", author = "")

        assertEquals(
            listOf(
                JiaocaiTermTextbook(
                    courseName = "线性代数",
                    textbookName = "线性代数（第七版）",
                    author = "同济大学数学系",
                    publisher = "高等教育出版社",
                    isbn = "9787040396614",
                ),
            ),
            listOf(real, none, stub).toJiaocaiTermTextbooks(),
        )
    }

    /**
     * 映射本身**不添判据、也不滤 ISBN**（占位号是屏那一侧 `realIsbn` 的事），顺序也原样 ——
     * 顺带钉住「读不到就是空表」（`TextbookScreen` 拿空表就整段不显示，不画一行假数据）。
     */
    @Test
    fun mappingCopiesFiveFieldsInOrderAndKeepsEmptyAsEmpty() {
        val placeholderIsbn = TextbookItem(
            courseName = "大学物理",
            textbookName = "大学物理（上册）",
            isbn = "9780000000000",
            courseCode = "PHYS1001",
        )
        val second = TextbookItem(courseName = "艺术导论", textbookName = "艺术的故事", author = "贡布里希")

        val mapped = listOf(placeholderIsbn, second).toJiaocaiTermTextbooks()
        assertEquals(listOf("大学物理", "艺术导论"), mapped.map { it.courseName }, "顺序 = 输入顺序（不排序）")
        assertEquals("9780000000000", mapped[0].isbn, "占位号原样搬：滤它是屏那一侧的事")
        assertEquals(emptyList(), emptyList<TextbookItem>().toJiaocaiTermTextbooks(), "空表进 ⇒ 空表出（那一段整段不显示）")
    }
}
