package com.xjtu.toolbox.termreport

/**
 * 学期总结的「讲法」：称号、学期之最、换算、写给下学期，以及多套文案。
 * 同一份总结每次打开挑到的句子一样（按学期码定种子），不同人、不同学期读起来不重样。
 */

/** 按学期码挑文案：同一个槽位每次都挑同一句。 */
class StoryCopy(seed: String) {
    private val base = seed.hashCode()
    fun pick(slot: String, vararg options: String): String = options[Math.floorMod(base * 31 + slot.hashCode(), options.size)]
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun weekdayName(index: Int) = WEEKDAYS.getOrElse(index) { "" }

/** 一个候选称号：[score] ≥ 1 算够格，越大越突出；[reason] 写在称号下面。 */
data class TermTitle(val name: String, val reason: String, val score: Double)

/**
 * 全部候选称号按突出程度排好。门槛尽量放在多数人够得着的地方，
 * 避免「数据都有、却挑不出称号」；真挑不出时由 [mainTitle] 兜底。
 */
fun TermReport.titleCandidates(): List<TermTitle> = buildList {
    fun add(name: String, reason: String, score: Double) { if (score > 0) add(TermTitle(name, reason, score)) }
    grades?.let { g ->
        add("学霸本霸", "GPA %.2f".format(g.gpa), g.gpa / 3.8)
        add("满分猎手", "${g.top95} 门 95 分以上", g.top95 / 2.0)
        g.previousGpa?.let { p ->
            val d = g.gpa - p
            add("逆袭选手", "GPA 比上学期高 %.2f".format(d), d / 0.4)
            add("稳步上分", "GPA 比上学期高 %.2f".format(d), d / 0.15 * 0.9)
        }
        add("稳定输出型", "均分 %.1f".format(g.average), (g.average - 70) / 15)
        add("学分收割机", "%.1f 学分".format(g.credits), g.credits / 30)
    }
    courses?.let { c ->
        add("早八战士", "早八 ${c.earlyClasses} 次", c.earlyClasses / 40.0)
        add("课表铁人", "一学期 ${c.totalSections} 节课", c.totalSections / 420.0)
        add("晚课守夜人", "最晚上到第 ${c.latestSection} 节", if (c.latestSection >= 11) 1.05 else 0.0)
        c.topBuilding?.let { (b, n) -> add("${b}常驻嘉宾", "在${b}上了 $n 节", n / 160.0) }
    }
    attendance?.let { a ->
        add("全勤模范", "${a.total} 次打卡零迟到零缺勤", if (a.late == 0 && a.absent == 0) a.total / 30.0 else 0.0)
        add("连续打卡王", "最长连续正常 ${a.longestStreak} 次", a.longestStreak / 50.0)
        add("踩点艺术家", "迟到 ${a.late} 次但从不缺勤", if (a.absent == 0) a.late / 5.0 else 0.0)
        add("课堂常客", "到场率 ${(a.rate * 100).toInt()}%", a.rate / 0.97)
    }
    card?.let { c ->
        c.meal?.let { m ->
            add("夜宵常客", "夜宵 ${m.lateNights} 次", m.lateNights / 15.0)
            add("早餐打卡王", "吃了 ${m.breakfastDays} 天早饭", m.breakfastDays / 40.0)
            add("干饭实干家", "在学校吃了 ${m.meals} 顿", m.meals / 220.0)
            add("奶茶续命党", "饮品 ${m.drinks} 次", m.drinks / 25.0)
            add("精打细算家", "平均一顿 ¥%.1f".format(m.avgMeal), if (m.meals >= 60 && m.avgMeal > 0) 11.0 / m.avgMeal else 0.0)
            add("豪横干饭人", "平均一顿 ¥%.1f".format(m.avgMeal), if (m.meals >= 30) m.avgMeal / 20.0 else 0.0)
            m.canteen?.let { (name, n) -> add("${name}老顾客", "去了 $n 次", n / 60.0) }
        }
        add("从早吃到晚", "最长一天从 ${c.longestMealDay?.second} 吃到 ${c.longestMealDay?.third}", if (c.longestMealDay != null) 0.95 else 0.0)
        add("一卡通大户", "一学期花了 ¥%.0f".format(c.spend), c.spend / 5000.0)
    }
    todos?.let { t ->
        add("执行力担当", "待办完成率 ${(t.rate * 100).toInt()}%", if (t.created >= 10) t.rate / 0.85 else 0.0)
        add("计划控", "给自己定了 ${t.created} 件事", t.created / 40.0)
    }
    agent?.let { a ->
        add("小岱的老朋友", "和小岱聊了 ${a.messages} 句", a.messages / 200.0)
        add("首席提问官", "开了 ${a.chats} 个对话", a.chats / 30.0)
    }
    games?.let { g ->
        add("摸鱼大师", "小游戏 ${g.minutes} 分钟", g.minutes / 600.0)
        g.favorite?.let { add("${it}发烧友", "最爱玩「$it」", g.minutes / 180.0) }
    }
    usage?.let { u ->
        add("工具箱重度用户", "打开了 ${u.launches} 次", u.launches / 400.0)
        add("付款码常客", "亮了 ${u.payCodeOpens} 次付款码", u.payCodeOpens / 120.0)
    }
    val owl = (usage?.lateLaunches ?: 0) + (agent?.lateNight ?: 0) + (card?.meal?.lateNights?.toLong() ?: 0)
    add("深夜修仙党", "深夜活跃 $owl 次", owl / 25.0)
    fitness?.let { if ("优秀" in it || "良好" in it) add("运动健将", "体测 $it", if ("优秀" in it) 1.3 else 1.0) }
}.sortedByDescending { it.score }

/** 主称号：够格的里取最突出的；都不够格就挑最接近的；什么数据都没有给个兜底。 */
fun TermReport.mainTitle(): TermTitle {
    val all = titleCandidates()
    return all.firstOrNull { it.score >= 1 } ?: all.firstOrNull { it.score >= 0.5 }
        ?: if (courses == null && card == null && grades == null) TermTitle("低调神秘人", "这学期留下的痕迹不多，下学期多来坐坐", 1.0)
        else TermTitle("宝藏型选手", "哪一项都不偏科，均衡就是最大的天赋", 1.0)
}

/** 副标签：主称号之外再挑两三个。 */
fun TermReport.sideTitles(): List<String> {
    val main = mainTitle().name
    return titleCandidates().filter { it.score >= 0.8 && it.name != main }.map { it.name }.distinct().take(3)
}

/** 学期之最：只挑算得出来的，最多 8 条。 */
fun TermReport.highlights(): List<String> = buildList {
    courses?.let { c ->
        if (c.busiestDay.second >= 8) add("最满的一天上了 ${c.busiestDay.second} 节课（${weekdayName(c.busiestDay.first)}）")
        if (c.latestSection >= 11) add("最晚上到第 ${c.latestSection} 节")
        c.topTeacher?.let { add("给你上课最多的老师：${it.first}") }
    }
    attendance?.let { a -> if (a.longestStreak >= 10) add("连续 ${a.longestStreak} 次准时打卡") }
    grades?.best?.let { add("最高分：${it.courseName} ${it.score}") }
    card?.let { c ->
        c.longestMealDay?.let { (d, first, last) -> add("${d.monthNumber} 月 ${d.dayOfMonth} 日，你从 $first 吃到了 $last") }
        c.meal?.latest?.let { add("最晚的一顿：${it.time.drop(11).take(5)} · ${it.displayMerchant}") }
        c.meal?.canteen?.let { add("最常去：${it.first}（${it.second} 次）") }
        c.priciestDay?.let { (d, v) -> add("花钱最多的一天：${d.monthNumber} 月 ${d.dayOfMonth} 日，¥%.0f".format(v)) }
    }
    usage?.topFeatures?.firstOrNull()?.let { add("最常打开：${it.first}（${it.second} 次）") }
    agent?.peakHour?.let { add("最常在 $it 点找小岱") }
    games?.favorite?.let { add("最爱玩：$it") }
    todos?.takeIf { it.created >= 5 }?.let { add("待办完成率 ${(it.rate * 100).toInt()}%") }
}.take(8)

/** 换算：把数字变成摸得着的东西。 */
object Equivalents {
    /** 校内一碗牛肉面按 15 元算。 */
    fun noodles(spend: Double) = (spend / 15).toInt()
    fun classHours(sections: Int) = sections * 45 / 60
    /** 一部电影按 110 分钟算。 */
    fun movies(minutes: Long) = (minutes / 110).toInt()
}

/** 写给下学期：挑两三条最相关的，不说教。 */
fun TermReport.advice(copy: StoryCopy): List<String> = buildList {
    attendance?.let { a ->
        if (a.late >= 5) add("早八前一晚早点睡，闹钟往前拨十分钟，迟到会少一半")
        if (a.absent >= 3) add("缺勤有点多，重要的课先保住，期末会轻松很多")
    }
    grades?.let { g ->
        if (g.failed > 0) add("挂掉的课别慌，补考和重修都是机会，早点问清时间")
        val d = g.previousGpa?.let { g.gpa - it }
        if (d != null && d < -0.15) add("分数有点小滑坡，挑一两门最有把握的课先拉回来")
    }
    todos?.let { t ->
        if (t.created >= 8 && t.rate < 0.5) add("待办少记点、只记要紧的，完成的感觉会上瘾")
        if (t.overdue >= 5) add("过了截止的待办清一清，脑子会轻松很多")
    }
    card?.meal?.let { m ->
        if (m.lateNights >= 15) add("夜宵悠着点，胃和钱包都会感谢你")
        if (m.meals >= 60 && m.breakfastDays * 4 < m.meals / 3) add("试试多吃几顿早饭，上午的课会清醒很多")
    }
    if ((usage?.lateLaunches ?: 0) >= 20 || (agent?.lateNight ?: 0) >= 30) add("熬夜的次数有点多，早点睡也是一种自律")
    if ((games?.minutes ?: 0) >= 1200) add("游戏玩得开心，也给自己留几段整块的学习时间")
    if (isEmpty()) add(copy.pick("advice-default", "保持这个节奏，下学期也会是好学期", "照现在这样走下去就很好", "没什么要改的，继续做自己"))
}.take(3)

/** 给小岱点评用的摘要。 */
fun TermReport.agentSnapshot(): String = buildString {
    append("用户的「$termName」学期总结（$start 至 $end，本机统计，部分数据可能不全）：\n")
    courses?.let { append("课程 ${it.courses} 门，共 ${it.totalSections} 节，周均 ${it.weeklyAvg} 节，早八 ${it.earlyClasses} 次。\n") }
    attendance?.let { append("考勤 ${it.total} 次，迟到 ${it.late}、缺勤 ${it.absent}、请假 ${it.leave}，到场率 ${(it.rate * 100).toInt()}%。\n") }
    grades?.let { append("成绩 ${it.count} 门，学分 %.1f，GPA %.2f，均分 %.1f，不及格 ${it.failed} 门%s。\n".format(it.credits, it.gpa, it.average, it.previousGpa?.let { p -> "，上学期 GPA %.2f".format(p) }.orEmpty())) }
    todos?.let { append("自建待办 ${it.created} 条，完成 ${it.done} 条，逾期未完成 ${it.overdue} 条。\n") }
    card?.let { c ->
        append("校园卡自 ${c.from} 起支出 %.0f 元".format(c.spend))
        c.meal?.let { append("，吃了 ${it.meals} 顿，早饭 ${it.breakfastDays} 天，夜宵 ${it.lateNights} 次") }
        append("。\n")
    }
    fitness?.let { append("体测：$it。\n") }
    games?.let { append("小游戏玩了 ${it.minutes} 分钟（${it.days} 天）。\n") }
    agent?.let { append("和小岱聊了 ${it.messages} 条消息、${it.chats} 个对话。\n") }
    usage?.let { append("打开工具箱 ${it.launches} 次，最常用：${it.topFeatures.joinToString("、") { f -> f.first }}。\n") }
    append("称号：${mainTitle().name}。\n")
    append("请先肯定做得好的地方，再给两三条具体、可执行的建议，语气轻松，不要说教，不要渲染焦虑。")
}
