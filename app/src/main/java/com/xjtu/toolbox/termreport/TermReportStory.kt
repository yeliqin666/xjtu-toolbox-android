package com.xjtu.toolbox.termreport

import com.xjtu.toolbox.card.Transaction
import kotlinx.datetime.LocalDate

/**
 * 学期总结的「讲法」：称号、学期之最、换算、写给下学期，以及多套文案。
 * 同一个人每次打开挑到的句子一样（种子 = 学期 + 账号），不同人、不同学期读起来不重样。
 */

/** 按种子挑文案：同一个槽位每次都挑同一句。 */
class StoryCopy(seed: String) {
    private val base = seed.hashCode()
    fun index(slot: String, size: Int) = Math.floorMod(base * 31 + slot.hashCode(), size)
    fun pick(slot: String, vararg options: String): String = options[index(slot, options.size)]
}

/** 不叫 copy()：会被 data class 自带的 copy 抢先匹配。 */
fun TermReport.storyCopy() = StoryCopy(seed)

/** 深夜活跃次数：凌晨开盒子 + 深夜找小岱 + 夜宵。 */
val TermReport.owlCount: Long
    get() = (usage?.lateLaunches ?: 0) + (agent?.lateNight ?: 0) + (card?.meal?.lateNights?.toLong() ?: 0)

/** 学期人格：几个维度各 0–1，最突出的两项组合出一个类型。 */
data class Persona(val name: String, val desc: String, val axes: List<Pair<String, Float>>)

private val PERSONA_NAMES = mapOf(
    setOf("学业", "自律") to listOf("自律学神型", "按部就班的学霸"),
    setOf("学业", "出勤") to listOf("前排优等生", "教室里的定海神针"),
    setOf("学业", "夜行") to listOf("深夜刷题型", "月光下的学霸"),
    setOf("学业", "干饭") to listOf("吃饱再学型", "能吃能学两开花"),
    setOf("学业", "玩心") to listOf("张弛有度的学霸", "会玩也会学"),
    setOf("学业", "好奇") to listOf("求知欲爆棚型", "打破砂锅问到底"),
    setOf("出勤", "自律") to listOf("规律生活家", "人形时钟"),
    setOf("出勤", "干饭") to listOf("两点一线踏实派", "教室食堂往返者"),
    setOf("出勤", "夜行") to listOf("白天上课晚上修仙", "全天候在线"),
    setOf("出勤", "玩心") to listOf("课上认真课下快乐", "该玩玩该学学"),
    setOf("出勤", "好奇") to listOf("课堂提问家", "好奇的老实人"),
    setOf("干饭", "夜行") to listOf("深夜食堂主理人", "宵夜教父"),
    setOf("干饭", "自律") to listOf("三餐规律养生派", "按时吃饭的狠人"),
    setOf("干饭", "玩心") to listOf("快乐干饭人", "吃喝玩乐全能王"),
    setOf("干饭", "好奇") to listOf("美食探索家", "一边吃一边问"),
    setOf("夜行", "玩心") to listOf("夜猫子玩家", "通宵派对选手"),
    setOf("夜行", "好奇") to listOf("深夜聊天搭子", "失眠哲学家"),
    setOf("夜行", "自律") to listOf("自律的夜猫子", "熬夜但有计划"),
    setOf("玩心", "自律") to listOf("劳逸结合大师", "玩也要按计划"),
    setOf("玩心", "好奇") to listOf("好奇心驱动型", "什么都想试试"),
    setOf("好奇", "自律") to listOf("效率研究员", "工具人中的工具人"),
)

private val PERSONA_LINES = mapOf(
    "学业" to "成绩单是你最好的名片",
    "出勤" to "教室里总能找到你",
    "干饭" to "食堂是你的第二个家",
    "自律" to "说好的事，你都做了",
    "夜行" to "夜晚才是你的主场",
    "玩心" to "快乐也是一门必修课",
    "好奇" to "遇事不决，先问小岱",
)

fun TermReport.persona(): Persona? {
    val discipline = listOfNotNull(
        todos?.takeIf { it.created >= 5 }?.rate,
        card?.meal?.let { it.breakfastDays / 60f },
        attendance?.let { 1f - it.late / 15f },
    ).takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val axes = buildList {
        grades?.let { add("学业" to (it.gpa / 4.0).toFloat()) }
        attendance?.let { add("出勤" to (it.rate - 0.7f) / 0.3f) }
        card?.meal?.let { add("干饭" to it.meals / 300f) }
        discipline?.let { add("自律" to it) }
        if (usage != null || agent != null || card?.meal != null) add("夜行" to owlCount / 60f)
        games?.let { add("玩心" to it.minutes / 900f) }
        agent?.let { add("好奇" to it.messages / 300f) }
    }.map { (k, v) -> k to v.coerceIn(0.08f, 1f) }
    if (axes.size < 3) return null
    val (a, b) = axes.sortedByDescending { it.second }.take(2).map { it.first }
    val copy = storyCopy()
    val names = PERSONA_NAMES[setOf(a, b)] ?: listOf("${a}${b}双修型")
    return Persona(
        name = names[copy.index("persona", names.size)],
        desc = "「$a」和「$b」是你这学期最亮的两项：${PERSONA_LINES[a]}，${PERSONA_LINES[b]}。",
        axes = axes,
    )
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun weekdayName(index: Int) = WEEKDAYS.getOrElse(index) { "" }

/** 「12 月 21 日」 */
fun LocalDate.md() = "$monthNumber 月 $dayOfMonth 日"

/** 流水时间 → 「12 月 21 日 23:41」；解析不了原样返回。 */
fun Transaction.stamp(): String =
    runCatching { LocalDate.parse(time.take(10)).md() + " " + time.drop(11).take(5) }.getOrDefault(time).trim()

/**
 * 一个候选称号。[score] 是原始达标度（≥ 1 算够格），[weight] 是拿来比谁更突出的分：
 * 封顶后乘上稀有度——人人都有的（全勤、学分、课多）压一压，少见的（逆袭、踩点、从早吃到晚）抬一抬。
 */
data class TermTitle(val name: String, val reason: String, val score: Double, val weight: Double = score)

/** 原始达标度封顶到这里，免得「课多」「打卡多」这种随总量线性涨的项把别的全压下去。 */
private const val SCORE_CAP = 1.6

/** 和最突出的那个差不到这么多，就算并列，由种子挑。 */
private const val TIE_BAND = 0.25

/** 全部候选称号按 [TermTitle.weight] 排好。每个称号有几种叫法，按种子挑一个。 */
fun TermReport.titleCandidates(): List<TermTitle> {
    val copy = storyCopy()
    return buildList {
        fun add(names: List<String>, reason: String, score: Double, rarity: Double = 1.0) {
            if (score <= 0) return
            val name = names[copy.index("title-${names.first()}", names.size)]
            add(TermTitle(name, reason, score, score.coerceAtMost(SCORE_CAP) * rarity))
        }
        grades?.let { g ->
            add(listOf("学霸本霸", "绩点收割者", "卷王之王"), "GPA %.2f".format(g.gpa), g.gpa / 3.8, 0.95)
            add(listOf("满分猎手", "九五俱乐部", "高分捕手"), "${g.top95} 门 95 分以上", g.top95 / 2.0, 1.1)
            g.previousGpa?.let { p ->
                val d = g.gpa - p
                add(listOf("逆袭选手", "绝地反击", "触底反弹王"), "GPA 比上学期高 %.2f".format(d), d / 0.4, 1.25)
                add(listOf("稳步上分", "上分专业户", "进步看得见"), "GPA 比上学期高 %.2f".format(d), d / 0.15 * 0.9, 1.05)
            }
            add(listOf("稳定输出型", "六边形选手", "不偏科达人"), "均分 %.1f".format(g.average), (g.average - 70) / 15, 0.8)
            add(listOf("学分收割机", "学分仓鼠", "满载而归"), "%.1f 学分".format(g.credits), g.credits / 30, 0.75)
        }
        courses?.let { c ->
            add(listOf("早八战士", "晨光骑士", "早八钉子户"), "早八 ${c.earlyClasses} 次", c.earlyClasses / 40.0, 1.0)
            add(listOf("课表铁人", "满课勇士", "教室常驻居民"), "一学期 ${c.totalSections} 节课", c.totalSections / 420.0, 0.75)
            add(listOf("晚课守夜人", "月亮下课党", "夜读人"), "最晚上到第 ${c.latestSection} 节", if (c.latestSection >= 11) 1.05 else 0.0, 0.9)
            c.topBuilding?.let { (b, n) -> add(listOf("${b}常驻嘉宾", "${b}钉子户", "${b}荣誉居民"), "在${b}上了 $n 节", n / 160.0, 0.95) }
        }
        attendance?.let { a ->
            add(listOf("全勤模范", "从不缺席", "打卡永动机"), "${a.total} 次打卡零迟到零缺勤", if (a.late == 0 && a.absent == 0) a.total / 60.0 else 0.0, 0.85)
            add(listOf("连续打卡王", "准时连击", "稳定在场"), "最长连续正常 ${a.longestStreak} 次", a.longestStreak / 50.0, 0.9)
            add(listOf("踩点艺术家", "极限到场", "铃声赛跑者"), "迟到 ${a.late} 次但从不缺勤", if (a.absent == 0) a.late / 5.0 else 0.0, 1.2)
            add(listOf("课堂常客", "出勤优等生", "教室熟面孔"), "到场率 ${(a.rate * 100).toInt()}%", a.rate / 0.97, 0.7)
        }
        card?.let { c ->
            c.meal?.let { m ->
                add(listOf("夜宵常客", "深夜食堂VIP", "宵夜守望者"), "夜宵 ${m.lateNights} 次", m.lateNights / 15.0, 1.15)
                add(listOf("早餐打卡王", "早饭不缺席", "晨间干饭人"), "吃了 ${m.breakfastDays} 天早饭", m.breakfastDays / 40.0, 1.05)
                add(listOf("干饭实干家", "干饭王者", "食堂编外人员"), "在学校吃了 ${m.meals} 顿", m.meals / 220.0, 0.8)
                add(listOf("奶茶续命党", "糖分驱动型", "饮品鉴赏家"), "饮品 ${m.drinks} 次", m.drinks / 25.0, 1.1)
                add(listOf("精打细算家", "省钱小能手", "性价比之王"), "平均一顿 ¥%.1f".format(m.avgMeal), if (m.meals >= 60 && m.avgMeal > 0) 11.0 / m.avgMeal else 0.0, 1.0)
                add(listOf("豪横干饭人", "吃饭不看价", "犒劳自己专家"), "平均一顿 ¥%.1f".format(m.avgMeal), if (m.meals >= 30) m.avgMeal / 20.0 else 0.0, 1.05)
                m.canteen?.let { (name, n) -> add(listOf("${name}老顾客", "${name}常驻食客", "${name}代言人"), "去了 $n 次", n / 60.0, 1.0) }
            }
            add(listOf("从早吃到晚", "全天候食客", "一日五餐"), "最长一天从 ${c.longestMealDay?.second} 吃到 ${c.longestMealDay?.third}", if (c.longestMealDay != null) 0.95 else 0.0, 1.1)
            add(listOf("一卡通大户", "消费主力军", "校园GDP贡献者"), "一学期花了 ¥%.0f".format(c.spend), c.spend / 5000.0, 0.9)
        }
        todos?.let { t ->
            add(listOf("执行力担当", "说到做到", "清单杀手"), "待办完成率 ${(t.rate * 100).toInt()}%", if (t.created >= 10) t.rate / 0.85 else 0.0, 1.05)
            add(listOf("计划控", "规划大师", "日程表达人"), "给自己定了 ${t.created} 件事", t.created / 40.0, 1.0)
        }
        agent?.let { a ->
            add(listOf("小岱的老朋友", "小岱知己", "AI 搭子"), "和小岱聊了 ${a.messages} 句", a.messages / 200.0, 1.05)
            add(listOf("首席提问官", "十万个为什么", "好奇宝宝"), "开了 ${a.chats} 个对话", a.chats / 30.0, 1.0)
        }
        games?.let { g ->
            add(listOf("摸鱼大师", "课间游戏王", "快乐摸鱼人"), "小游戏 ${g.minutes} 分钟", g.minutes / 600.0, 1.05)
            g.favorite?.let { add(listOf("${it}发烧友", "${it}高手", "${it}常驻玩家"), "最爱玩「$it」", g.minutes / 180.0, 1.0) }
        }
        usage?.let { u ->
            add(listOf("工具箱重度用户", "盒子常驻用户", "效率控"), "打开了 ${u.launches} 次", u.launches / 400.0, 0.8)
            add(listOf("付款码常客", "扫码达人", "刷码如风"), "亮了 ${u.payCodeOpens} 次付款码", u.payCodeOpens / 120.0, 0.9)
        }
        add(listOf("深夜修仙党", "夜行动物", "月光值班员"), "深夜活跃 $owlCount 次", owlCount / 25.0, 1.1)
        fitness?.let { if ("优秀" in it || "良好" in it) add(listOf("运动健将", "体测达人", "活力满格"), "体测 $it", if ("优秀" in it) 1.3 else 1.0, 1.05) }
    }.sortedByDescending { it.weight }
}

/**
 * 主称号：够格的里，和最突出的差不到 [TIE_BAND] 的都算并列，由种子挑一个；
 * 都不够格就挑最接近的；什么数据都没有给个兜底。
 */
fun TermReport.mainTitle(): TermTitle {
    val all = titleCandidates()
    val eligible = all.filter { it.score >= 1 }
    if (eligible.isNotEmpty()) {
        val top = eligible.first().weight
        val tied = eligible.filter { it.weight >= top - TIE_BAND }
        return tied[storyCopy().index("main-title", tied.size)]
    }
    val copy = storyCopy()
    return all.firstOrNull { it.score >= 0.5 }
        ?: if (courses == null && card == null && grades == null) {
            TermTitle(copy.pick("t-empty", "低调神秘人", "隐身侠客", "传说中的人"), "这学期留下的痕迹不多，下学期多来坐坐", 1.0)
        } else {
            TermTitle(copy.pick("t-even", "宝藏型选手", "均衡发展派", "全能小透明"), "哪一项都不偏科，均衡就是最大的天赋", 1.0)
        }
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
    grades?.best?.let { add("最高分：${it.courseName} ${scoreLabel(it)}") }
    card?.let { c ->
        c.longestMealDay?.let { (d, first, last) -> add("${d.md()}，你从 $first 吃到了 $last") }
        c.meal?.latest?.let { add("最晚的一顿：${it.stamp()} · ${it.displayMerchant}") }
        c.meal?.earliest?.let { add("最早的一顿：${it.stamp()} · ${it.displayMerchant}") }
        c.meal?.canteen?.let { add("最常去：${it.first}（${it.second} 次）") }
        c.priciestDay?.let { (d, v) -> add("花钱最多的一天：${d.md()}，¥%.0f".format(v)) }
    }
    usage?.topFeatures?.firstOrNull()?.let { add("最常打开：${it.first}（${it.second} 次）") }
    agent?.peakHour?.let { add("最常在 $it 点找小岱") }
    games?.favorite?.let { add("最爱玩：$it") }
    todos?.takeIf { it.created >= 5 }?.let { add("待办完成率 ${(it.rate * 100).toInt()}%") }
}.take(8)

/**
 * 彩蛋：命中几条出几条，最有意思的在前（第一条当大标题）。
 * 每条是（大字、说明）。
 */
fun TermReport.eggs(copy: StoryCopy): List<Pair<String, String>> = buildList {
    card?.longestMealDay?.let { (d, first, last) ->
        add(d.md() to copy.pick("egg-meal", "你从 $first 吃到了 $last，这一天是怎么熬过来的？", "从 $first 的第一口到 $last 的最后一口，这一天你一定很拼"))
    }
    card?.meal?.latest?.takeIf { (mealMinutes(it) ?: 0) >= 24 * 60 }?.let {
        add(it.stamp() to "凌晨还在「${it.displayMerchant}」吃了一顿，那晚在忙什么？")
    }
    card?.meal?.earliest?.takeIf { (mealMinutes(it) ?: 24 * 60) < 6 * 60 + 40 }?.let {
        add(it.stamp() to "天还没亮透，你已经在「${it.displayMerchant}」吃上了")
    }
    attendance?.takeIf { it.late == 0 && it.absent == 0 && it.total >= 30 }?.let {
        add("满勤" to "${it.total} 次打卡，一次没落下。这份坚持，比任何分数都珍贵")
    }
    courses?.takeIf { it.busiestDay.second >= 10 }?.let {
        add("最满的一天" to "有一个${weekdayName(it.busiestDay.first)}，你上满了 ${it.busiestDay.second} 节课。那天的你，值得一顿好吃的")
    }
    card?.meal?.stall?.takeIf { it.second >= 30 }?.let { (s, n) ->
        add("$n 次" to "「$s」你一共吃了 $n 次，老板该给你办张会员卡了")
    }
    usage?.busiestDay?.takeIf { it.second >= 25 }?.let { (d, n) ->
        add(d.md() to "这一天你打开了岱宗盒子 $n 次，是在等成绩还是在抢课？")
    }
    games?.longestDay?.takeIf { it.second >= 2 * 3600 }?.let { (d, s) ->
        add(d.md() to "这天小游戏玩了 ${s / 60} 分钟，快乐是真的快乐")
    }
    card?.quietStreak?.takeIf { it.second >= 10 }?.let { (d, n) ->
        add("$n 天" to "从 ${d.md()} 起，校园卡安静了 $n 天。是回家了，还是出去玩了？")
    }
    grades?.takeIf { it.top95 >= 3 }?.let { add("${it.top95} 门 95+" to "一学期 ${it.top95} 门 95 分以上，这不是运气") }
}

/** 流水时间换成「一天里的分钟数」，凌晨 0–3 点算前一天的 24–27 点。 */
private fun mealMinutes(tx: Transaction): Int? {
    val h = tx.time.drop(11).take(2).toIntOrNull() ?: return null
    val m = tx.time.drop(14).take(2).toIntOrNull() ?: 0
    return (if (h < 4) h + 24 else h) * 60 + m
}

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

/** 「写给下学期」：做得好的、可以试试的、带数字的小目标（label, value, icon）。 */
data class NextTerm(val keep: List<String>, val tries: List<String>, val goals: List<Triple<String, String, String>>)

fun TermReport.nextTerm(copy: StoryCopy): NextTerm {
    val keep = buildList {
        grades?.let { g ->
            val d = g.previousGpa?.let { g.gpa - it }
            when {
                d != null && d > 0.05 -> add("GPA 涨了 %.2f，说明这学期的方法是对的".format(d))
                g.gpa >= 3.7 -> add("GPA %.2f 稳在高位，节奏别丢".format(g.gpa))
            }
            if (g.top95 > 0) add("${g.top95} 门拿到 95 分或 A+，擅长的方向值得深挖")
        }
        attendance?.let { a ->
            if (a.late == 0 && a.absent == 0 && a.total >= 20) add("零迟到零缺勤，这份靠谱是稀缺品")
            else if (a.rate >= 0.97f) add("到场率 ${(a.rate * 100).toInt()}%，出勤稳得住")
            if (a.longestStreak >= 30) add("连续准时 ${a.longestStreak} 次，习惯已经长在身上了")
        }
        card?.meal?.let { m ->
            if (m.breakfastDays >= 40) add("吃了 ${m.breakfastDays} 天早饭，好习惯继续")
            if (m.meals >= 150) add("在学校好好吃了 ${m.meals} 顿饭，身体是本钱")
        }
        todos?.takeIf { it.created >= 10 && it.rate >= 0.8f }?.let { add("待办完成率 ${(it.rate * 100).toInt()}%，说到做到") }
        courses?.takeIf { it.earlyClasses >= 30 }?.let { add("扛下了 ${it.earlyClasses} 个早八，意志力满分") }
    }.take(3)

    val extra = listOf(
        "每周留半天给自己，不排课也不排事",
        "在图书馆找一个固定的座位，进入状态会快很多",
        "选一门纯粹因为好奇的课",
        "认识一位新老师，课后多问一个问题",
        "期中前列一张复习清单，期末会轻松一半",
        "周末去校外走走，换换脑子",
        "给自己定一个小技能：一门语言、一项运动或一种乐器",
    )
    val tries = (advice(copy).filterNot { it.startsWith("保持这个节奏") || it.startsWith("照现在") || it.startsWith("没什么要改") } +
        List(extra.size) { i -> extra[(copy.index("next-try", extra.size) + i) % extra.size] }).distinct().take(3)

    val goals = buildList {
        grades?.let { g ->
            val target = (g.gpa + if (g.gpa < 3.0) 0.2 else if (g.gpa < 4.0) 0.1 else 0.0).coerceAtMost(4.3)
            add(Triple("GPA", if (target > g.gpa + 0.001) "冲 %.2f".format(target) else "守住 %.2f".format(g.gpa), "trophy"))
        }
        attendance?.let { a ->
            add(Triple("迟到", if (a.late == 0) "继续 0 次" else "≤ ${a.late / 2} 次", "clock"))
        }
        card?.meal?.let { m ->
            val weekly = m.breakfastDays * 7f / days.coerceAtLeast(1)
            add(Triple("早饭", "每周 ${(weekly + 1).toInt().coerceIn(3, 7)} 天", "sunrise"))
        }
        todos?.takeIf { it.created >= 5 }?.let { t ->
            add(Triple("待办完成", "${((t.rate * 100).toInt() + 10).coerceAtMost(95)}%", "list"))
        }
        if (owlCount >= 20) add(Triple("深夜活跃", "减半到 ${owlCount / 2} 次", "moon"))
        if (size < 4) add(Triple("新尝试", "一件没做过的事", "sparkle"))
    }.take(4)
    return NextTerm(keep, tries, goals)
}

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
