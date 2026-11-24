package com.xjtu.toolbox.termreport

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学期总结文案的措辞底线：按多个种子、好坏两组数据把每个槽位的候选句都渲染一遍。
 * 拦两类句子：煽情空泛的套话；拿个人状况开玩笑、同情或居高临下的说法。
 */
class TermReportCopyTest {

    private val cliches = listOf(
        "旅程", "温柔", "致敬", "闪闪发光", "新的自己", "值得", "珍贵", "稀缺品",
        "被看见", "写满了", "最大的天赋", "人间烟火", "运气都不会太差", "会感谢你",
        "也是一种自律", "这不是运气", "长在身上", "意志力满分", "身体是本钱",
        "朝夕相处", "主旋律", "一段熬过的夜", "记得你的努力", "每一次起床都算数",
        "选课的胜利", "懂得生活", "从胃开始", "一种本事", "生活就在哪",
        "泰山不让土壤", "这座山上的土", "由同学做给同学", "说一句话也算", "谢谢你", "挺乐意",
        "一整个学期的投入", "忙着生活", "收获了别的", "不止绩点", "你做到了",
    )

    /** 同情口吻，或拿出勤、成绩、花钱戳痛处的说法。摸鱼、修仙、续命这类自嘲式的俏皮话不在此列。 */
    private val hurtful = listOf(
        "可怜", "心疼", "惨", "又挂", "吃土", "面子", "佛系", "掉线", "掉链子",
        "失手", "滑坡", "捡来", "扛不住", "有点稀", "工具人",
    )

    /** 示例数据之外再造一组「数据不好」的：出勤低、成绩下滑有挂科、待办完成少、夜宵多、游戏久。 */
    private fun variants(): List<TermReport> {
        val good = demoTermReport()
        val bad = good.copy(
            attendance = good.attendance?.copy(rate = 0.62f, late = 9, absent = 6, normal = 20),
            grades = good.grades?.copy(gpa = 2.41, previousGpa = 3.02, failed = 2, top95 = 0, average = 68.0),
            todos = good.todos?.copy(created = 30, done = 6, overdue = 9),
            card = good.card?.copy(spend = 9800.0, meal = good.card?.meal?.copy(lateNights = 40, breakfastDays = 3)),
            games = good.games?.copy(minutes = 3000),
            usage = good.usage?.copy(lateLaunches = 60),
            agent = good.agent?.copy(lateNight = 80),
        )
        return listOf(good, bad)
    }

    private fun texts(report: TermReport): String {
        val copy = report.storyCopy()
        val next = report.nextTerm(copy)
        return buildString {
            append(report.toHtml())
            report.titleCandidates().forEach { append(it.name).append(it.reason) }
            report.eggs(copy).forEach { append(it.second) }
            report.advice(copy).forEach(::append)
            (next.keep + next.tries).forEach(::append)
            next.goals.forEach { append(it.second) }
            report.persona()?.let { append(it.name).append(it.desc) }
        }
    }

    private fun hits(words: List<String>): List<String> =
        variants().flatMap { base ->
            (0 until 300).flatMap { i ->
                val text = texts(base.copy(seed = "seed-$i"))
                words.filter { it in text }
            }
        }.distinct()

    @Test
    fun `各槽位候选句不含套话`() {
        val found = hits(cliches)
        assertTrue("文案里还有套话：$found", found.isEmpty())
    }

    @Test
    fun `不拿个人状况开玩笑也不同情`() {
        val found = hits(hurtful)
        assertTrue("文案里还有带刺或怜悯的说法：$found", found.isEmpty())
    }

    @Test
    fun `兜底称号说明不含套话`() {
        val empty = demoTermReport().copy(courses = null, card = null, grades = null, attendance = null,
            todos = null, agent = null, games = null, usage = null, fitness = null)
        val title = empty.mainTitle()
        val text = title.name + title.reason
        assertTrue(text, (cliches + hurtful).none { it in text })
    }
}
