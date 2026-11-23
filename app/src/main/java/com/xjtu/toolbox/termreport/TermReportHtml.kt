package com.xjtu.toolbox.termreport

import kotlinx.datetime.LocalDate
import kotlin.math.ln
import kotlin.random.Random

/**
 * 把学期总结排成一份自包含的 HTML（年终总结那样的整屏竖滑页）。
 * 不引外部资源，存下来以后用任何 WebView 打开都一样；插画、图标都是内联 SVG。
 */
fun TermReport.toHtml(): String {
    val copy = storyCopy()
    val title = mainTitle()
    val weeks = term.weeks.coerceAtLeast(1)
    val slides = buildList {
        add(slide("#1B1F4B", "#3A2F7A", "#A5B4FC", Hero.CALENDAR) {
            lead("calendar", termName)
            body(copy.pick("cover-lead", "这学期一共", "从开学到最后一周，一共", "这一段旅程，一共"))
            count(days.toLong(), 80, suf = "天")
            small("${start.year} 年 ${start.md()} — ${if (end.year != start.year) "${end.year} 年 " else ""}${end.md()}")
            fact("教学周", "$weeks 周", "calendar")
            fact("折合", "${days * 24} 小时", "clock")
            fact("周末", "${days / 7} 个", "sun")
            fact("约等于", "${"%.1f".format(days / 365.0 * 100)}% 的一年", "chart")
            val chapters = listOfNotNull(courses?.let { "课表" }, attendance?.let { "考勤" }, grades?.let { "成绩" },
                card?.let { "校园卡" }, todos?.let { "待办" }, agent?.let { "小岱" }, usage?.let { "盒子" })
            if (chapters.size >= 2) note("list", "接下来会看到：${chapters.joinToString("、")}")
            quote(copy.pick("cover", "学期结束啦，一起看看这段时间", "辛苦了，来看看这学期的你", "翻篇之前，先回头看一眼", "这学期的你，都在这里了"))
            hint()
        })
        if (daily.count { it > 0 } >= 7) add(slide("#13203F", "#2F4C8F", "#93C5FD", null) {
            lead("calendar", copy.pick("cal-lead", "你的学期日历", "这学期的每一天", "一格一天"))
            val active = daily.count { it > 0 }
            count(active.toLong(), 64, suf = "天有动静")
            heatmap(daily, start.dayOfWeek.ordinal)
            small("每格是一天，颜色越深那天越忙：上课打卡、刷卡、打开盒子、找小岱都算")
            val byWeek = daily.withIndex().groupBy { (i, _) -> (i + start.dayOfWeek.ordinal) / 7 }.mapValues { (_, l) -> l.sumOf { it.value } }
            byWeek.maxByOrNull { it.value }?.let { (w, _) -> note("flame", "第 ${w + 1} 周最忙，" + copy.pick("cal-busy", "大概是考试周？", "那周你一定很充实", "那一周的你，满格运转")) }
            var run = 0; var longest = 0
            daily.forEach { if (it > 0) longest = maxOf(longest, ++run) else run = 0 }
            if (longest >= 7) note("bolt", "最长连着 $longest 天没停过")
        })
        courses?.let { c ->
            add(slide("#0F3D5E", "#1F7A8C", "#7DD3FC", Hero.BOOK) {
                lead("book", copy.pick("course-lead", "这学期你上了", "课表上一共排了", "你和这些课朝夕相处"))
                count(c.courses.toLong(), 64, suf = "门课")
                fact("总节数", "${c.totalSections} 节", "list")
                fact("折合", "${Equivalents.classHours(c.totalSections)} 小时", "clock")
                fact("每周平均", "${c.weeklyAvg} 节", "calendar")
                fact("早八", "${c.earlyClasses} 次 · 每周 ${"%.1f".format(c.earlyClasses.toFloat() / weeks)}", "sunrise")
                val max = (c.perWeekday.maxOrNull() ?: 0).coerceAtLeast(1)
                vbars(c.perWeekday.mapIndexed { i, n -> weekdayName(i).drop(1) to n.toFloat() / max })
                val busy = c.perWeekday.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
                val light = c.perWeekday.take(5).withIndex().minByOrNull { it.value }
                busy?.let {
                    note("bolt", copy.pick("course-busy", "${weekdayName(it.index)}是你最满的一天", "${weekdayName(it.index)}的课表最挤", "每到${weekdayName(it.index)}，你都格外忙") +
                        "，平均每个${weekdayName(it.index)} ${"%.1f".format(it.value.toFloat() / weeks)} 节")
                }
                if (light != null && busy != null && light.index != busy.index) {
                    note("coffee", "${weekdayName(light.index)}最轻松，" + copy.pick("course-light", "适合补觉", "可以去图书馆占个好位置", "是一周里的喘息"))
                }
                val weekend = c.perWeekday.drop(5).sum()
                if (weekend > 0) note("alarm", "周末也上了 $weekend 节，辛苦了")
                if (c.busiestDay.second >= 8) note("flame", "单日最多 ${c.busiestDay.second} 节，从早到晚连轴转")
                quote(when {
                    c.earlyClasses >= 40 -> copy.pick("early-hi", "早八 ${c.earlyClasses} 次，你是真正的早八战士", "${c.earlyClasses} 个早八，太阳都没你起得早", "早八 ${c.earlyClasses} 次，致敬每一个挣扎起床的清晨")
                    c.earlyClasses > 0 -> copy.pick("early-mid", "其中早八 ${c.earlyClasses} 次，每一次起床都算数", "早八 ${c.earlyClasses} 次，被窝没能留住你", "${c.earlyClasses} 个早八，辛苦啦")
                    else -> copy.pick("early-none", "一个早八都没有，排课运气拉满", "零早八，懂得生活", "这学期的闹钟大概很清闲", "没有早八的学期，是选课的胜利")
                })
            })
            if (c.topCourses.size >= 2 || c.periods.sum() > 0) add(slide("#0D3347", "#246A73", "#7DD3FC", null) {
                lead("book", copy.pick("course2-lead", "课表细看", "这些课占了你最多时间", "把课表拆开看"))
                if (c.topCourses.isNotEmpty()) {
                    section("节数最多的课")
                    val max = c.topCourses.first().second.coerceAtLeast(1)
                    c.topCourses.forEach { (name, n) -> hbar(name, "$n 节", n.toFloat() / max) }
                    c.topCourses.first().let { (name, n) ->
                        note("medal", "「$name」最常见，占了全部课时的 ${n * 100 / c.totalSections.coerceAtLeast(1)}%")
                    }
                }
                if (c.periods.sum() > 0) {
                    section("一天里的课")
                    stacked(listOf("上午", "下午", "晚上").zip(c.periods))
                    val (am, pm, eve) = c.periods
                    note(if (am >= pm) "sunrise" else "sun", when {
                        am >= pm && am >= eve -> copy.pick("period-am", "上午的课最多，早起是这学期的主旋律", "课都挤在上午，下午才是自由时间")
                        pm >= eve -> copy.pick("period-pm", "下午的课最多，午觉得抓紧", "下午是你的主战场")
                        else -> copy.pick("period-eve", "晚上的课比白天还多，夜校生活", "晚课最多，月亮陪你上课")
                    })
                }
                c.topBuilding?.let { (b, n) -> fact("最常待的楼", "$b · $n 节", "building") }
                c.topTeacher?.let { (t, n) -> fact("见得最多的老师", "$t · $n 节", "user") }
                if (c.customEvents > 0) note("sparkle", "你还自己排了 ${c.customEvents} 条日程，时间管理有一套")
                c.topBuilding?.let { (b, _) -> quote(copy.pick("building", "${b}的每一层楼梯，你大概都走熟了", "说起${b}，你可以当半个导游", "${b}：这学期的第二个宿舍")) }
            })
        }
        attendance?.let { a ->
            add(slide("#14324A", "#2C7DA0", "#67E8F9", Hero.CHECK) {
                lead("check", copy.pick("att-lead", "这学期你打卡", "考勤机记下了你", "你出现在教室里"))
                count(a.total.toLong(), 64, suf = "次")
                ring(a.rate, "${(a.rate * 100).toInt()}%", "到场率")
                quote(when {
                    a.late == 0 && a.absent == 0 -> copy.pick("att-perfect", "零迟到零缺勤，全勤模范就是你", "一次没落下，老师都记住你了", "满勤！这份自律值得一个大大的赞")
                    a.rate >= 0.95f -> copy.pick("att-hi", "到场率超高，靠谱本谱", "几乎每节都在，稳", "出勤这块，你没让人操心")
                    a.rate >= 0.8f -> copy.pick("att-mid", "大部分时候都在线，不错", "偶尔掉线，整体靠谱", "出勤还行，下学期再稳一点")
                    else -> copy.pick("att-lo", "自由的灵魂不被打卡定义——下学期给老师一点面子？", "出勤有点佛系，重要的课先保住", "下学期多去几次，说不定会爱上某门课")
                })
                fact("正常", "${a.normal} 次", "check")
                if (a.late > 0) fact("迟到", "${a.late} 次", "clock")
                if (a.absent > 0) fact("缺勤", "${a.absent} 次", "x")
                if (a.leave > 0) fact("请假", "${a.leave} 次", "doc")
                val slips = a.late + a.absent
                if (slips > 0) note("bolt", "平均每 ${a.total / slips} 次打卡才失手一次")
                if (a.longestStreak >= 10) note("flame", "最长连续准时 ${a.longestStreak} 次，约 ${a.longestStreak * 100 / a.total.coerceAtLeast(1)}% 的打卡连成一串")
                // 有失手就点出最容易失手的那门/那天，没有就点名全勤的课
                val lateCourse = a.mostLate?.takeIf { it.second >= 2 }
                val slipDay = a.slipWeekday?.takeIf { it.second >= 2 }
                when {
                    lateCourse != null -> note("alarm", "最容易迟到的是「${lateCourse.first}」，${lateCourse.second} 次" +
                        (slipDay?.let { "；${weekdayName(it.first)}最容易掉链子" } ?: ""))
                    a.perfectNames.isNotEmpty() -> note("star", a.perfectNames.joinToString("、") { "「$it」" } + " 一次没落下")
                }
            })
        }
        grades?.let { g ->
            add(slide("#3B1E54", "#9B3675", "#F9A8D4", Hero.TROPHY) {
                lead("trophy", copy.pick("gpa-lead", "这学期的 GPA", "这学期你交出的答卷", "成绩单上的数字是"))
                big("%.2f".format(g.gpa), 80)
                quote(g.previousGpa?.let { p ->
                    val d = g.gpa - p
                    when {
                        d >= 0.3 -> copy.pick("gpa-up-big", "比上学期高了 %.2f，这是一次漂亮的逆袭".format(d), "猛涨 %.2f，努力被看见了".format(d), "一口气涨了 %.2f，这学期你是认真的".format(d), "%.2f 的跃升，换个人似的".format(d))
                        d > 0.005 -> copy.pick("gpa-up", "比上学期高了 %.2f，进步看得见".format(d), "又往上走了 %.2f，稳步上分".format(d), "涨了 %.2f，一点一点变好".format(d), "比上学期多 %.2f，方向是对的".format(d))
                        d < -0.005 -> copy.pick("gpa-down", "比上学期低了 %.2f，下学期再找回来".format(-d), "小滑坡 %.2f 而已，调整一下就好".format(-d), "回落了 %.2f，起伏本来就是常态".format(-d))
                        else -> copy.pick("gpa-flat", "和上学期持平，稳", "纹丝不动，稳定输出", "和上学期几乎一样，状态在线")
                    }
                } ?: when {
                    g.gpa >= 3.8 -> copy.pick("gpa-hi", "这个成绩，可以骄傲一下", "学霸认证，没有疑问", "成绩单好看到想裱起来", "这份答卷，写满了用心")
                    g.gpa >= 3.3 -> copy.pick("gpa-mid", "扎实的一学期", "稳稳当当，值得点赞", "不声不响，交出一份好成绩", "踏实的分数，踏实的你")
                    else -> copy.pick("gpa-lo", "分数只是一部分，你还收获了别的", "下学期挑几门重点突破", "大学不止绩点，但也别全放下", "这只是一个学期，后面还长")
                })
                fact("课程", "${g.count} 门", "book")
                fact("学分", "%.1f".format(g.credits), "star")
                fact("均分", "%.1f".format(g.average), "chart")
                g.best?.let { fact("最高分", scoreLabel(it), "trophy") }
                stacked(g.bands)
                val graded = g.bands.sumOf { it.second }
                val top = g.bands.firstOrNull()?.second ?: 0
                if (graded > 0 && top > 0) note("chart", "计分的课里 ${top * 100 / graded}% 在 90 分 / A 以上")
                g.previousGpa?.let { p -> note(if (g.gpa >= p) "bolt" else "heart", "上学期 %.2f → 这学期 %.2f".format(p, g.gpa)) }
                g.best?.let { note("medal", "最高分来自「${it.courseName}」") }
                if (g.top95 > 0) note("sparkle", "${g.top95} 门 95 分或 A+ 以上，满分猎手")
                if (g.failed > 0) note("heart", copy.pick("failed", "有 ${g.failed} 门没过，补考和重修都还来得及", "${g.failed} 门暂时没过，别慌，机会还在", "${g.failed} 门要再来一次，这回会更熟"))
            })
            if (g.top.size >= 2 || g.history.size >= 2) add(slide("#33184A", "#7E2F6B", "#F9A8D4", null) {
                lead("trophy", copy.pick("gpa2-lead", "成绩单细看", "分数背后", "每一门课"))
                if (g.top.isNotEmpty()) {
                    section("分数最高的课")
                    // 条长从 60 分起算，差几分也看得出来
                    g.top.forEach { (name, label, v) -> hbar(name, label, ((v - 60) / 40).toFloat()) }
                }
                g.heaviest?.let { h ->
                    note("star", "学分最重的是「${h.courseName}」（%s 学分），拿了 ${scoreLabel(h)}".format(
                        if (h.coursePoint % 1.0 == 0.0) h.coursePoint.toInt().toString() else "%.1f".format(h.coursePoint)))
                }
                if (g.history.size >= 2) {
                    section("每学期 GPA")
                    val lo = (g.history.minOf { it.second } - 0.3).coerceAtLeast(0.0)
                    val hi = g.history.maxOf { it.second }
                    vbars(g.history.map { (t, v) -> t to ((v - lo) / (hi - lo).coerceAtLeast(0.01)).toFloat() },
                        g.history.map { "%.2f".format(it.second) })
                    val rank = g.history.sortedByDescending { it.second }.indexOfFirst { it.second == g.gpa } + 1
                    note(if (rank == 1) "medal" else "chart", when {
                        rank == 1 -> copy.pick("gpa-rank1", "这是你 ${g.history.size} 个学期里 GPA 最高的一次", "${g.history.size} 个学期里的新高，就是这学期")
                        rank > 0 -> "在你的 ${g.history.size} 个学期里排第 $rank"
                        else -> "一共记录了 ${g.history.size} 个学期"
                    })
                    val first = g.history.first()
                    if (g.history.size >= 3) note("bolt", "从${first.first}的 %.2f 到现在的 %.2f".format(first.second, g.gpa))
                }
                quote(copy.pick("gpa2-end", "每一个分数，都是一段熬过的夜", "成绩不是全部，但它记得你的努力", "这份成绩单，你自己最清楚有多不容易"))
            })
        }
        todos?.let { t ->
            add(slide("#113D2E", "#2E8B57", "#86EFAC", Hero.CLIPBOARD) {
                lead("list", copy.pick("todo-lead", "你给自己定了", "待办清单上写过", "这学期你计划了"))
                count(t.created.toLong(), 64, suf = "件事")
                ring(t.rate, "${(t.rate * 100).toInt()}%", "完成了 ${t.done} 件")
                quote(when {
                    t.rate >= 0.85f -> copy.pick("todo-hi", "说到做到，执行力拉满", "清单杀手，勾勾勾", "你定的事，几乎都办成了")
                    t.rate >= 0.5f -> copy.pick("todo-mid", "大半都搞定了，不赖", "一半以上打了勾，继续保持", "做完的比没做的多，这就是赢")
                    else -> copy.pick("todo-lo", "记下来就是开始，下学期挑几件最要紧的先做", "计划比做完多一点，正常，慢慢来", "想做的事很多，说明你有很多想要的")
                })
                fact("每周新增", "${"%.1f".format(t.created.toFloat() / weeks)} 件", "plus")
                fact("每周完成", "${"%.1f".format(t.done.toFloat() / weeks)} 件", "check")
                if (t.overdue > 0) note("clock", "还有 ${t.overdue} 件过了截止没勾掉，可能早做完只是忘了点")
                if (t.monthly.size >= 2) {
                    val max = t.monthly.maxOf { it.second }.coerceAtLeast(1)
                    vbars(t.monthly.map { (m, n) -> monthName(m) to n.toFloat() / max }, t.monthly.map { it.second.toString() })
                    t.monthly.maxByOrNull { it.second }?.let { (m, n) ->
                        note("flame", "${monthName(m)}最忙，记了 $n 件" + copy.pick("todo-peak", "，那个月事情真多", "，大概是期中前后", "，你扛下来了"))
                    }
                }
                if (t.byAgent > 0) note("chat", "其中 ${t.byAgent} 件是让小岱帮你记的")
            })
        }
        card?.let { c ->
            add(slide("#4A2511", "#C0632B", "#FDBA74", Hero.WALLET) {
                lead("wallet", copy.pick("card-lead", "校园卡一共花了", "这学期一卡通刷出去", "钱包这学期流出了"))
                count(c.spend.toLong(), 68, pre = "¥")
                fact("折合牛肉面", "${Equivalents.noodles(c.spend)} 碗", "bowl")
                fact("刷卡天数", "${c.activeDays} 天", "calendar")
                if (c.activeDays > 0) fact("日均", "¥%.1f".format(c.spend / c.activeDays), "coin")
                c.categories.firstOrNull()?.let { (name, v) -> fact("大头", "$name ${(v * 100 / c.spend.coerceAtLeast(1.0)).toInt()}%", "chart") }
                val maxMonth = c.monthly.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                vbars(c.monthly.map { (m, v) -> "${m.takeLast(2).trimStart('0')}月" to (v / maxMonth).toFloat() })
                c.monthly.maxByOrNull { it.second }?.let { (m, v) ->
                    note("flame", "${monthName(m)}花得最多，¥%.0f".format(v) +
                        copy.pick("card-peak", "，那个月发生了什么？", "，大概是期中的补给", "，钱包那个月最累"))
                }
                val maxCat = c.categories.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                c.categories.forEach { (name, v) -> hbar(name, "¥%.0f".format(v), (v / maxCat).toFloat()) }
                if (c.from > start) small("本机流水从 ${c.from.md()} 起，之前的没算进来")
            })
            add(slide("#3F2212", "#A4552A", "#FDBA74", null) {
                lead("coin", copy.pick("card2-lead", "花钱习惯", "钱都花在哪天", "钱包的小秘密"))
                if (c.perWeekday.any { it > 0 }) {
                    section("周一到周日")
                    val max = c.perWeekday.max().coerceAtLeast(1.0)
                    vbars(c.perWeekday.mapIndexed { i, v -> weekdayName(i).drop(1) to (v / max).toFloat() },
                        c.perWeekday.map { "%.0f".format(it) })
                }
                c.topWeekday?.let { (d, _) ->
                    note("bolt", "${weekdayName(d)}是你最舍得花钱的日子" + copy.pick("card-wd", "", "，犒劳自己的固定节目", "，一周的消费高峰"))
                }
                c.priciestDay?.let { (d, v) -> note("calendar", "花得最多的一天是 ${d.md()}，¥%.0f".format(v)) }
                c.priciest?.let { note("coin", "单笔最大：${it.stamp()} 在${it.displayMerchant}，¥%.0f".format(-it.amount)) }
                c.quietStreak?.takeIf { it.second >= 7 }?.let { (d, n) ->
                    note("coffee", "最长 $n 天没刷卡（${d.md()} 起）" + copy.pick("card-quiet", "，回家了吧？", "，钱包难得休息", "，那几天去哪儿了？"))
                }
                quote(when {
                    c.spend / c.activeDays.coerceAtLeast(1) <= 25 -> copy.pick("card-thrift", "花得克制，日子过得明白", "会过日子，是一种本事")
                    else -> copy.pick("card-free", "该花就花，对自己好一点没错", "钱花在哪，生活就在哪")
                })
            })
            c.meal?.let { m ->
                add(slide("#5A1E1E", "#D9534F", "#FCA5A5", Hero.BOWL) {
                    lead("bowl", copy.pick("meal-lead", "这学期你在学校吃了", "食堂记住了你", "一日三餐，你一共吃了"))
                    count(m.meals.toLong(), 72, suf = "顿")
                    small("平均一顿 ¥%.1f，吃饭占了支出的 %d%%".format(m.avgMeal, (m.mealShare * 100).toInt()))
                    val first = m.earliest?.time?.drop(11)?.take(5)
                    val last = m.latest?.time?.drop(11)?.take(5)
                    if (first != null && last != null) {
                        clock24(hourOf(first), hourOf(last), first, last, "你的干饭时间带")
                    }
                    if (m.days > 0) note("bowl", "吃饭的日子里平均每天 ${"%.1f".format(m.meals.toFloat() / m.days)} 顿" +
                        if (m.breakfastDays > 0) "，${m.breakfastDays * 100 / m.days}% 的日子吃了早饭" else "")
                    m.canteen?.let { fact("最常去", "${it.first} · ${it.second} 次", "store") }
                    m.stall?.let { fact("最爱档口", "${it.first} · ${it.second} 次", "heart") }
                    if (m.breakfastDays > 0) fact("吃了早饭", "${m.breakfastDays} 天", "sun")
                    if (m.lateNights > 0) fact("夜宵", "${m.lateNights} 次", "moon")
                    if (m.drinks > 0) fact("饮品", "${m.drinks} 杯", "coffee")
                    m.earliest?.let { note("sunrise", "最早的一顿：${it.stamp()}，${it.displayMerchant}") }
                    m.latest?.let { note("moon", "最晚的一顿：${it.stamp()}，${it.displayMerchant}") }
                    m.peak?.let { (h, quip) -> note("clock", "$h 点是你的干饭高峰，$quip") }
                    quote(when {
                        m.lateNights >= 15 -> copy.pick("meal-night", "夜宵是你和校园最温柔的约定", "深夜的食堂灯光，你最熟", "饿着肚子的夜晚，你一个都没放过")
                        m.breakfastDays >= 40 -> copy.pick("meal-bf", "早饭从不缺席，自律从胃开始", "早餐打卡王，上午的你一定很清醒", "一天从早饭开始，你做到了")
                        m.avgMeal > 0 && m.avgMeal <= 11 -> copy.pick("meal-cheap", "精打细算，吃得好也吃得省", "性价比之王", "每一分钱都吃在刀刃上")
                        else -> copy.pick("meal-mid", "好好吃饭的人，运气都不会太差", "吃饱了，才有力气学习", "人间烟火气，最抚学子心")
                    })
                })
            }
        }
        val eggs = eggs(copy)
        if (eggs.isNotEmpty()) add(slide("#2A0F3D", "#B83280", "#F0ABFC", Hero.EGG) {
            lead("egg", if (eggs.size > 1) "彩蛋 × ${minOf(eggs.size, 3)}" else "彩蛋")
            big(eggs[0].first, 36)
            quote(eggs[0].second)
            eggs.drop(1).take(2).forEach { (head, text) -> note("sparkle", "$head：$text") }
        })
        val g = games
        if ((g != null && (g.minutes > 0 || g.records.isNotEmpty())) || fitness != null) add(slide("#1E2A5A", "#4F6BD9", "#A5B4FC", Hero.GAMEPAD) {
            lead("game", "课余时间")
            if (g != null && g.minutes > 0) {
                body(copy.pick("game-lead", "小游戏一共玩了", "摸鱼时间累计", "你在小游戏里度过了"))
                count(g.minutes, 60, suf = "分钟")
                fact("分布在", "${g.days} 天", "calendar")
                fact("相当于", "${g.minutes / 45} 节课", "book")
                g.longestDay?.let { (d, s) -> fact("最久一天", "${d.md()} · ${s / 60} 分钟", "clock") }
                fitness?.let { fact("体测", it, "run") }
                if (g.perGame.size >= 2) {
                    val max = g.perGame.first().second.coerceAtLeast(1)
                    g.perGame.forEach { (name, n) -> hbar(name, "$n 分钟", n.toFloat() / max) }
                }
                g.records.take(2).forEach { note("trophy", it) }
                quote(when {
                    g.minutes >= 600 -> copy.pick("game-hi", "会玩的人，学起来也不差", "摸鱼也是一门手艺")
                    else -> copy.pick("game-lo", "小玩怡情，刚刚好", "偶尔放松一下，节奏感满分")
                })
            } else {
                g?.records?.take(3)?.forEach { note("trophy", it) }
                fitness?.let { fact("体测", it, "run") }
                quote(copy.pick("free-lo", "课余时间留给了更重要的事", "比起小游戏，你更忙着生活"))
            }
        })
        agent?.let { a ->
            add(slide("#233142", "#4FA3A5", "#5EEAD4", Hero.CHAT) {
                lead("chat", copy.pick("agent-lead", "你和小岱聊了", "小岱收到了你", "这学期你对小岱说了"))
                count(a.messages, 68, suf = "句")
                fact("对话", "${a.chats} 个", "chat")
                fact("来找它", "${a.days} 天", "calendar")
                if (a.tools > 0) fact("帮你查", "${a.tools} 次", "bolt")
                if (a.days > 0) fact("每次来平均", "${a.messages / a.days} 句", "chat")
                if (a.lateNight > 0) fact("深夜聊天", "${a.lateNight} 句", "moon")
                a.peakHour?.let { h ->
                    hours(a.hourly, "$h 点" + copy.pick("agent-hour", "是你最常找它的钟点", "是你和小岱的固定时段", "，小岱最常收到你的消息"))
                }
                if (a.topTools.isNotEmpty()) {
                    small("最常让它帮忙")
                    val max = a.topTools.first().second.coerceAtLeast(1)
                    a.topTools.forEach { (name, n) -> hbar(name, "$n 次", n.toFloat() / max) }
                }
                quote(copy.pick("agent-end", "谢谢你这学期的信任", "下学期也请多多关照", "有问题，随时来") +
                    (a.peakHour?.takeIf { it >= 23 || it < 4 }?.let { "，也记得早点睡呀" } ?: ""))
            })
        }
        usage?.let { u ->
            add(slide("#2D1B4E", "#6A4C93", "#C4B5FD", Hero.PHONE) {
                lead("phone", copy.pick("use-lead", "这学期你打开岱宗盒子", "你和岱宗盒子见了", "这学期你点开工具箱"))
                count(u.launches, 68, suf = "次")
                fact("用到它", "${u.days} 天", "calendar")
                if (u.days > 0) fact("平均每天", "${"%.1f".format(u.launches.toFloat() / u.days)} 次", "phone")
                if (u.payCodeOpens > 0) fact("亮付款码", "${u.payCodeOpens} 次", "qr")
                if (u.features > 0) fact("用过的功能", "${u.features} 个", "sparkle")
                u.hourly.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { (h, _) ->
                    hours(u.hourly, "$h 点最常打开" + when (h) {
                        in 7..8 -> "，出门前看一眼课表"
                        in 11..13 -> "，饭点刷卡最忙"
                        in 22..23, in 0..3 -> "，睡前再看一眼"
                        else -> ""
                    })
                }
                if (u.topFeatures.isNotEmpty()) {
                    small("最常用")
                    val max = u.topFeatures.first().second.coerceAtLeast(1)
                    u.topFeatures.forEach { (name, n) -> hbar(name, "$n 次", n.toFloat() / max) }
                }
                if (u.lateLaunches > 0) note("moon", "凌晨还打开 ${u.lateLaunches} 次，夜猫子实锤")
                if (u.days >= days * 0.6) note("heart", "学期里 ${u.days * 100 / days.coerceAtLeast(1)}% 的日子都用到了它，谢谢你")
            })
        }
        persona()?.let { p ->
            add(slide("#1A1638", "#5B3FA8", "#FBCFE8", null) {
                lead("sparkle", "你的学期人格")
                big(p.name, 40)
                radar(p.axes)
                quote(p.desc)
                p.axes.minByOrNull { it.second }?.takeIf { it.second < 0.5f }?.let { (axis, _) ->
                    note("bulb", "「$axis」是还没点满的一格，" + copy.pick("persona-low", "下学期可以多给它一点时间", "留点惊喜给下学期", "也许是下一个亮点"))
                }
            })
        }
        add(slide("#0E3B43", "#2A9D8F", "#99F6E4", Hero.PLANE) {
            lead("plane", "写给下学期")
            val next = nextTerm(copy)
            if (next.keep.isNotEmpty()) {
                section("继续保持")
                next.keep.forEach { note("check", it) }
            }
            section("可以试试")
            next.tries.forEach { note("bulb", it) }
            if (next.goals.isNotEmpty()) {
                section("下学期小目标")
                next.goals.forEach { (label, value, icon) -> fact(label, value, icon) }
            }
            quote(copy.pick("advice-end", "下学期见，祝一切顺利", "愿你下学期依旧闪闪发光", "新的学期，新的自己"))
        })
        project?.let { addAll(projectSlides(it)) }
        add(slide("#16122E", "#4B2A7B", "#FDE68A", Hero.MEDAL) {
            raw(buildString {
                append("<div class=\"poster\"><div class=\"pt\">${icon("sparkle")}<span>${esc(termName)} · 这学期的你是</span></div>")
                append("<div class=\"big\" style=\"${fontSize(38)}\">「${esc(title.name)}」</div>")
                append("<div class=\"body\">${esc(title.reason)}</div>")
                val tags = sideTitles()
                if (tags.isNotEmpty()) append("<div class=\"tags\">${tags.joinToString("") { "<span>${esc(it)}</span>" }}</div>")
                highlights().forEach { append("<div class=\"hl\">${icon("star")}<span>${esc(it)}</span></div>") }
                append("<div class=\"sign\">岱宗盒子 · 学期总结</div></div>")
            })
            if (missing.isNotEmpty()) small("生成时本机没有${missing.joinToString("、") { it.first }}的数据，加载后刷新就能补上")
        })
    }
    return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${esc(termName)} · 学期总结</title><style>$CSS</style></head>
<body><main>${slides.joinToString("")}</main><script>$JS</script></body></html>"""
}

private const val REPO_URL = "https://github.com/yeliqin666/xjtu-toolbox-android"

/**
 * 「关于岱宗盒子」：它的来历、你和它、一起做它的人、写在最后。
 * 只写数据里查得到的事；时间、人数都按 [ProjectStory] 截至那天的数。
 */
private fun TermReport.projectSlides(p: ProjectStory): List<String> = buildList {
    val born = p.bornDate
    fun day(s: String) = LocalDate.parse(s)
    add(slide("#0B1A33", "#1E3A6E", "#FDE68A", null) {
        lead("sparkle", "关于岱宗盒子")
        big("${born.year} 年 ${born.md()}", 40)
        body(buildString {
            // 2026-02-18 是农历正月初二
            if (born == LocalDate(2026, 2, 18)) append("大年初二，")
            append("晚上 ${p.born.drop(11)}，岱宗盒子的第一次提交写着：「${p.firstCommit.substringAfter(": ")}」")
        })
        p.stars.firstOrNull()?.let { first ->
            val gap = day(first).toEpochDays() - born.toEpochDays()
            note("star", when (gap) { 0L -> "当天"; 1L -> "第二天"; else -> "第 ${gap + 1} 天" } + "，它收到了第一颗星。")
        }
        installedOn?.takeIf { it >= born }?.let { d ->
            section("你和它")
            val then = p.releases.lastOrNull { day(it.d) <= d }
            // 安装日期按系统的首次安装时间，卸载重装会往后推，所以说「这一段」而不是「装上」
            note("heart", "你和它的这一段，从 ${d.year} 年 ${d.md()}开始，那是它出生后的第 ${d.toEpochDays() - born.toEpochDays() + 1} 天" +
                (then?.let { "，它还是 v${it.v}" } ?: "") + "。")
            val after = p.releases.count { day(it.d) > d }
            if (after > 0) note("plane", "从那天到现在，它又更新了 $after 个版本。")
        }
        if (p.stars.isNotEmpty()) {
            section("星星")
            val dates = p.stars.map(::day)
            val marks = (listOf(1, 10, 50, 100, 200, 500).filter { it < dates.size } + dates.size).distinct()
            starCurve(dates, born, maxOf(day(p.date), dates.last()), marks)
            chips(marks.map { "第 $it 颗 · ${dates[it - 1].md()}" })
            val inTerm = dates.count { it in start..end }
            if (inTerm > 0) note("star", "这学期，它多了 $inTerm 颗。")
        }
    })
    // 在不止一处留下名字的人，名字后面带个小点
    val groups = listOf(p.coders, p.askers, p.talkers).map { g -> g.map { it.name.lowercase() }.toSet() }
    val multi = groups.flatten().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    add(slide("#1A1033", "#3B2A6B", "#C4B5FD", null) {
        lead("user", "一起做它的人")
        count(p.people.toLong(), 64, suf = "位")
        small("截至 ${day(p.date).year} 年 ${day(p.date).md()}，在 GitHub 上留下名字的人")
        note("clock", "${p.commits} 次提交，分布在 ${p.commitDays} 天里，其中 ${p.lateCommits} 次是在凌晨 0 点到 5 点。")
        section("写代码的人 · ${p.coders.size} 位")
        wall(p.coders, multi)
        section("提问题的人 · ${p.askers.size} 位")
        small("${p.issues} 个 issue，${p.issuesClosed} 个已经关闭。")
        p.firstIssue?.let { f ->
            small("第 ${f.n} 个是 ${day(f.date).md()}提的：「${f.title}」" + (f.closeMinutes?.let { "，$it 分钟后被关闭" } ?: "") + "。")
        }
        p.hottestIssue?.takeIf { it.comments >= 5 }?.let { small("讨论最热的一个有 ${it.comments} 条回复。") }
        wall(p.askers, multi)
    })
    add(slide("#16203A", "#2B3F73", "#A5B4FC", null) {
        lead("chat", "一起做它的人")
        section("在讨论区说话的人 · ${p.talkers.size} 位")
        small("${p.discussions} 个帖子。")
        wall(p.talkers, multi)
        quote("这面墙只认得 GitHub 上的名字。QQ 群、集市、飞书表单里的同学，你们的名字不在这里，但你们说过的话，在这些版本里。")
        small("名字后的小点：在不止一处留下过名字")
    })
    add(slide("#0F0C24", "#3A2466", "#FDE68A", Hero.MEDAL) {
        lead("heart", "写在最后")
        big("泰山不让土壤，故能成其大", 30)
        small("—— 李斯《谏逐客书》")
        body("岱宗，就是泰山。一行代码、一个 issue、群里的一句「又崩了」，都是这座山上的一捧土。")
        note("doc", "岱宗盒子是开源的：代码以 GPL-3.0 协议全部公开，任何人都可以读、可以改、可以拿去用。")
        note("heart", "它免费，不以盈利为目的。由同学做给同学，属于每一个交大人。")
        note("user", "这座山没有门槛。会写代码的，来提一个 PR；不会写的，说一句话就够了。")
        quote("欢迎你，一起把它做得更好。下学期见。")
        link(REPO_URL, "☆ 去 GitHub 点亮一颗星")
    })
}

/** "2025-09" → 「9 月」 */
private fun monthName(ym: String) = "${ym.takeLast(2).trimStart('0')} 月"

private class SlideBuilder {
    val sb = StringBuilder()
    private var n = 0
    private val tiles = mutableListOf<String>()

    private fun put(html: String) {
        sb.append("<div class=\"r\" style=\"transition-delay:${n++ * 90}ms\">").append(html).append("</div>")
    }

    /** 连续的 [fact] 攒成一个两列网格。 */
    fun flush() {
        if (tiles.isEmpty()) return
        put("<div class=\"tiles\">${tiles.joinToString("")}</div>")
        tiles.clear()
    }

    fun raw(html: String) { flush(); put(html) }

    fun lead(icon: String, t: String) = raw("<div class=\"kicker\">${icon(icon)}<span>${esc(t)}</span></div>")
    fun small(t: String) = raw("<div class=\"small\">${esc(t)}</div>")
    /** 页内小标题。 */
    fun section(t: String) = raw("<div class=\"sec\">${esc(t)}</div>")
    fun body(t: String) = raw("<div class=\"body\">${esc(t)}</div>")
    fun quote(t: String) = raw("<div class=\"quote\">${esc(t)}</div>")
    fun note(icon: String, t: String) = raw("<div class=\"note\"><i>${icon(icon)}</i><span>${esc(t)}</span></div>")
    fun hint() = raw("<div class=\"hint\">上滑继续<svg viewBox=\"0 0 24 24\"><path d=\"M6 15l6-6 6 6\"/></svg></div>")
    fun big(t: String, size: Int) = raw("<div class=\"big\" style=\"${fontSize(size)}\">${esc(t)}</div>")
    fun count(value: Long, size: Int, pre: String = "", suf: String = "") = raw(
        "<div class=\"big\" style=\"${fontSize(size)}\">" +
            (if (pre.isNotEmpty()) "<span class=\"unit\">${esc(pre)}</span>" else "") +
            "<span data-count=\"$value\">0</span>" +
            (if (suf.isNotEmpty()) "<span class=\"unit\">${esc(suf)}</span>" else "") + "</div>",
    )

    fun fact(label: String, value: String, icon: String) {
        tiles += "<div class=\"tile\">${icon(icon)}<div><div class=\"l\">${esc(label)}</div><div class=\"v\">${esc(value)}</div></div></div>"
    }

    fun hbar(label: String, value: String, fraction: Float) = raw(
        "<div class=\"hbar\"><div class=\"row\"><span>${esc(label)}</span><span>${esc(value)}</span></div>" +
            "<div class=\"track\"><div class=\"fill\" style=\"--v:${frac(fraction)}\"></div></div></div>",
    )

    /** 竖条；[values] 给了就在条顶标上数。 */
    fun vbars(bars: List<Pair<String, Float>>, values: List<String>? = null) = raw(
        "<div class=\"vbars\">" + bars.withIndex().joinToString("") { (i, b) ->
            val v = values?.getOrNull(i)?.let { "<span class=\"val\">${esc(it)}</span>" }.orEmpty()
            "<div>$v<div class=\"bar\" style=\"--v:${frac(b.second)}\"></div><span class=\"small\">${esc(b.first)}</span></div>"
        } + "</div>",
    )

    /** Star 累计曲线：横轴从 [from] 到 [until]，[marks] 是要标出来的第几颗（从 1 数）。 */
    fun starCurve(dates: List<LocalDate>, from: LocalDate, until: LocalDate, marks: List<Int>) {
        if (dates.isEmpty()) return
        val w = 300.0; val h = 120.0; val top = 14.0
        val span = (until.toEpochDays() - from.toEpochDays()).coerceAtLeast(1).toDouble()
        fun x(d: LocalDate) = (d.toEpochDays() - from.toEpochDays()) / span * w
        fun y(n: Int) = h - n.toDouble() / dates.size * (h - top)
        val line = buildString {
            append("M0 %.1f".format(h))
            dates.forEachIndexed { i, d -> append(" L%.1f %.1f L%.1f %.1f".format(x(d), y(i), x(d), y(i + 1))) }
            append(" L%.1f %.1f".format(w, y(dates.size)))
        }
        val dots = marks.filter { it in 1..dates.size }.joinToString("") { n ->
            val cx = x(dates[n - 1]); val cy = y(n)
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" class=\"mk\"/><text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\">$n</text>".format(
                cx, cy, cx, cy - 8, if (cx > w - 20) "end" else if (cx < 20) "start" else "middle")
        }
        raw("<svg class=\"stars\" viewBox=\"0 0 $w $h\"><path class=\"area\" d=\"$line L$w $h Z\"/>" +
            "<path class=\"ln\" pathLength=\"100\" d=\"$line\"/>$dots</svg>")
    }

    /** 一排小标签。 */
    fun chips(items: List<String>) = raw("<div class=\"tags\">${items.joinToString("") { "<span>${esc(it)}</span>" }}</div>")

    /** 名字墙：参与越多字越大；[multi] 里的名字（不止在一处出现）带个小点。 */
    fun wall(people: List<ProjectStory.Person>, multi: Set<String>) {
        if (people.isEmpty()) return
        val max = ln(people.maxOf { it.n }.toDouble() + 1)
        raw("<div class=\"wall\">" + people.withIndex().joinToString("") { (i, p) ->
            val size = 12 + 7 * ln(p.n + 1.0) / max
            val dot = if (p.name.lowercase() in multi) "<b></b>" else ""
            "<span style=\"font-size:%.1fpx;transition-delay:${i * 30}ms\">${esc(p.name)}$dot</span>".format(size)
        } + "</div>")
    }

    fun link(url: String, text: String) = raw("<a class=\"cta\" href=\"${esc(url)}\">${esc(text)}</a>")

    /** 24 小时分布：24 根细条，最高的那根点亮。 */
    fun hours(counts: List<Long>, caption: String) {
        val max = counts.maxOrNull()?.takeIf { it > 0 } ?: return
        val peak = counts.indexOf(max)
        raw(
            "<div class=\"hrs\"><div class=\"cols\">" + counts.withIndex().joinToString("") { (h, n) ->
                "<i class=\"${if (h == peak) "pk" else ""}\" style=\"--v:${frac(n.toFloat() / max, 0.04f)};transition-delay:${h * 25}ms\"></i>"
            } + "</div><div class=\"axis\"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div>" +
                "<div class=\"small\">${esc(caption)}</div></div>",
        )
    }

    /** 学期日历：一列一周、一行星期几，颜色按当天动静分五档。[firstDow] 开学那天是星期几（0 = 周一）。 */
    fun heatmap(daily: List<Int>, firstDow: Int) {
        val nonZero = daily.filter { it > 0 }.sorted().takeIf { it.isNotEmpty() } ?: return
        // 按有动静的日子的四分位分档，少数特别忙的日子不会把其他天都压成最浅
        val cuts = listOf(0.25, 0.5, 0.75).map { nonZero[((nonZero.size - 1) * it).toInt()] }
        fun level(n: Int) = if (n <= 0) 0 else 1 + cuts.count { n > it }
        val cols = (daily.size + firstDow + 6) / 7
        val cell = 13; val gap = 3; val step = cell + gap
        val w = cols * step; val h = 7 * step
        val rects = daily.withIndex().joinToString("") { (i, n) ->
            val pos = i + firstDow
            "<rect x=\"${pos / 7 * step}\" y=\"${pos % 7 * step}\" width=\"$cell\" height=\"$cell\" rx=\"3\" class=\"l${level(n)}\" style=\"transition-delay:${pos / 7 * 40}ms\"/>"
        }
        val days = listOf(0 to "一", 2 to "三", 4 to "五", 6 to "日").joinToString("") { (r, t) ->
            "<text x=\"-6\" y=\"${r * step + cell - 2}\">$t</text>"
        }
        raw("<svg class=\"heat\" viewBox=\"-18 0 ${w + 18} $h\">$days$rects</svg>" +
            "<div class=\"legend heatlg\"><span>少</span>" + (0..4).joinToString("") { "<b class=\"l$it\"></b>" } + "<span>多</span></div>")
    }

    /** 一条分段横条，各段按门数占比，下面配图例。 */
    fun stacked(bands: List<Pair<String, Int>>) {
        val total = bands.sumOf { it.second }.takeIf { it > 0 } ?: return
        val shown = bands.withIndex().filter { it.value.second > 0 }
        val alpha = { i: Int -> 1.0 - i * 0.18 }
        raw(
            "<div class=\"stack\"><div class=\"seg\">" + shown.joinToString("") { (i, b) ->
                "<i style=\"--w:${b.second * 100.0 / total}%;opacity:${alpha(i)}\"></i>"
            } + "</div><div class=\"legend\">" + shown.joinToString("") { (i, b) ->
                "<span><b style=\"opacity:${alpha(i)}\"></b>${esc(b.first)} · ${b.second}</span>"
            } + "</div></div>",
        )
    }

    /** 24 小时表盘：从 [from] 到 [to] 点画一段弧，两端标上时间。 */
    fun clock24(from: Float, to: Float, fromLabel: String, toLabel: String, caption: String) {
        val cx = 70.0; val cy = 70.0; val r = 52.0
        fun pt(h: Float, rr: Double = r): Pair<Double, Double> {
            val a = Math.toRadians(h / 24.0 * 360 - 90)
            return cx + rr * Math.cos(a) to cy + rr * Math.sin(a)
        }
        val span = ((to - from) % 24 + 24) % 24
        val (x1, y1) = pt(from)
        val (x2, y2) = pt(from + span)
        val large = if (span > 12) 1 else 0
        val ticks = (0 until 24).joinToString("") { h ->
            val (ax, ay) = pt(h.toFloat(), r + 10)
            val (bx, by) = pt(h.toFloat(), r + (if (h % 6 == 0) 16 else 13))
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" class=\"tick${if (h % 6 == 0) " major" else ""}\"/>".format(ax, ay, bx, by)
        }
        val nums = listOf(0, 6, 12, 18).joinToString("") { h ->
            val (x, y) = pt(h.toFloat(), r - 14)
            "<text x=\"%.1f\" y=\"%.1f\">$h</text>".format(x, y + 4)
        }
        val dot = { x: Double, y: Double, cls: String -> "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"6\" class=\"$cls\"/>".format(x, y) }
        raw(
            "<div class=\"clockrow\"><svg class=\"clock\" viewBox=\"0 0 140 140\"><circle cx=\"70\" cy=\"70\" r=\"52\" class=\"face\"/>$ticks$nums" +
                "<path pathLength=\"100\" class=\"arc\" d=\"M%.1f %.1fA52 52 0 $large 1 %.1f %.1f\"/>".format(x1, y1, x2, y2) +
                dot(x1, y1, "d1") + (if (toLabel.isNotEmpty()) dot(x2, y2, "d2") else "") +
                "</svg><div><div class=\"ctime\">${esc(fromLabel)}${if (toLabel.isNotEmpty()) "<span> → </span>${esc(toLabel)}" else ""}</div>" +
                "<div class=\"small\">${esc(caption)}</div></div></div>",
        )
    }

    /** 雷达图：各维度 0–1。 */
    fun radar(axes: List<Pair<String, Float>>) {
        val cx = 130.0; val cy = 112.0; val r = 78.0
        fun pt(i: Int, v: Double): Pair<Double, Double> {
            val a = Math.PI * 2 * i / axes.size - Math.PI / 2
            return cx + r * v * Math.cos(a) to cy + r * v * Math.sin(a)
        }
        fun poly(v: (Int) -> Double) = axes.indices.joinToString(" ") { i -> pt(i, v(i)).let { (x, y) -> "%.1f,%.1f".format(x, y) } }
        val grid = listOf(0.33, 0.66, 1.0).joinToString("") { s -> "<polygon class=\"grid\" points=\"${poly { s }}\"/>" }
        val spokes = axes.indices.joinToString("") { i -> pt(i, 1.0).let { (x, y) -> "<line class=\"grid\" x1=\"$cx\" y1=\"$cy\" x2=\"%.1f\" y2=\"%.1f\"/>".format(x, y) } }
        val labels = axes.withIndex().joinToString("") { (i, a) ->
            val (x, y) = pt(i, 1.22)
            "<text x=\"%.1f\" y=\"%.1f\">${esc(a.first)}</text>".format(x, y + 4)
        }
        val dots = axes.withIndex().joinToString("") { (i, a) ->
            pt(i, a.second.toDouble()).let { (x, y) -> "<circle class=\"pt\" cx=\"%.1f\" cy=\"%.1f\" r=\"4\"/>".format(x, y) }
        }
        raw(
            "<svg class=\"radar\" viewBox=\"0 0 260 224\">$grid$spokes" +
                "<g class=\"shape\"><polygon class=\"area\" points=\"${poly { axes[it].second.toDouble() }}\"/>$dots</g>$labels</svg>",
        )
    }

    fun ring(fraction: Float, value: String, caption: String) = raw(
        "<div class=\"ringrow\"><svg class=\"ring\" viewBox=\"0 0 100 100\" style=\"--v:${frac(fraction, 0f)}\">" +
            "<circle cx=\"50\" cy=\"50\" r=\"45\" class=\"bg\"/><circle cx=\"50\" cy=\"50\" r=\"45\" class=\"fg\"/></svg>" +
            "<div><div class=\"big\" style=\"${fontSize(40)}\">${esc(value)}</div><div class=\"body\">${esc(caption)}</div></div></div>",
    )
}

/**
 * 一页：渐变底 + 漂浮装饰 + 顶部动画插画；[accent] 是本页的点缀色（图标、渐变字、进度条）。
 * 内容放在限宽的 `.wrap` 里，平板和横屏不会拉成一长条。
 */
private fun slide(from: String, to: String, accent: String, hero: Hero?, build: SlideBuilder.() -> Unit): String {
    val content = SlideBuilder().apply { build(); flush() }.sb
    return "<section style=\"background:linear-gradient(165deg,$from,$to);--a:$accent\">" +
        decor("$from$to$accent".hashCode()) +
        "<div class=\"wrap\">${hero?.let { "<div class=\"r hero\">${it.svg}</div>" }.orEmpty()}$content</div></section>"
}

/** 大字随屏宽缩放：400px 宽时正好是设计值，窄屏最多缩到 72%，宽屏不再放大。 */
private fun fontSize(px: Int) = "font-size:clamp(${(px * 0.72).toInt()}px,${px / 4.0}vw,${px}px)"

/** "06:48" → 6.8 */
private fun hourOf(hm: String): Float =
    (hm.substringBefore(':').toIntOrNull() ?: 0) + (hm.substringAfter(':', "0").toIntOrNull() ?: 0) / 60f

/** 背景里慢慢漂的小圆环、十字、星点；位置按种子固定，同一份总结每次打开都一样。 */
private fun decor(seed: Int): String {
    val rnd = Random(seed)
    val shapes = listOf(
        "<circle cx=\"12\" cy=\"12\" r=\"8\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"/>",
        "<path d=\"M12 4v16M4 12h16\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>",
        "<path d=\"M12 2C13 9 15 11 22 12 15 13 13 15 12 22 11 15 9 13 2 12 9 11 11 9 12 2z\" fill=\"currentColor\"/>",
        "<circle cx=\"12\" cy=\"12\" r=\"4\" fill=\"currentColor\"/>",
        "<path d=\"M3 14c3-6 6 6 9 0s6 6 9 0\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>",
    )
    return buildString {
        append("<div class=\"bg\"><b class=\"glow g1\"></b><b class=\"glow g2\"></b>")
        repeat(9) { i ->
            val x = (i % 3) * 33 + rnd.nextInt(4, 28)
            val y = (i / 3) * 33 + rnd.nextInt(2, 28)
            val size = rnd.nextInt(10, 26)
            val dur = rnd.nextInt(6, 13)
            val delay = rnd.nextDouble(0.0, 4.0)
            val anim = if (rnd.nextBoolean()) "fl" else "tw"
            val color = if (rnd.nextInt(3) == 0) "var(--a)" else "rgba(255,255,255,.55)"
            append("<svg viewBox=\"0 0 24 24\" class=\"$anim\" style=\"left:$x%;top:$y%;width:${size}px;height:${size}px;")
            append("color:$color;opacity:${rnd.nextInt(18, 45) / 100.0};animation-duration:${dur}s;animation-delay:-%.1fs\">".format(delay))
            append(shapes[rnd.nextInt(shapes.size)]).append("</svg>")
        }
        append("</div>")
    }
}

/** 线条图标，颜色跟随本页点缀色。 */
private fun icon(name: String) =
    "<svg class=\"ic\" viewBox=\"0 0 24 24\">${ICONS[name] ?: ICONS.getValue("sparkle")}</svg>"

private val ICONS = mapOf(
    "calendar" to "<rect x=\"3\" y=\"5\" width=\"18\" height=\"16\" rx=\"3\"/><path d=\"M3 10h18M8 3v4M16 3v4\"/>",
    "book" to "<path d=\"M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z\"/><path d=\"M4 19V5M8 7h7\"/>",
    "sun" to "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4\"/>",
    "sunrise" to "<path d=\"M12 3v4M5 10l1.5 1.5M19 10l-1.5 1.5M3 18h18M7 18a5 5 0 0 1 10 0\"/>",
    "moon" to "<path d=\"M20 14.5A8 8 0 1 1 9.5 4 6.5 6.5 0 0 0 20 14.5z\"/>",
    "building" to "<path d=\"M4 21V5l8-3 8 3v16M2 21h20M9 21v-4h6v4M8 9h.01M12 9h.01M16 9h.01M8 13h.01M12 13h.01M16 13h.01\"/>",
    "user" to "<circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21a8 8 0 0 1 16 0\"/>",
    "check" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"m8 12 3 3 5-6\"/>",
    "clock" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v5l3 2\"/>",
    "alarm" to "<circle cx=\"12\" cy=\"13\" r=\"8\"/><path d=\"M12 9v4l2 2M5 3 2 6M19 3l3 3\"/>",
    "x" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"m9 9 6 6M15 9l-6 6\"/>",
    "doc" to "<path d=\"M6 3h9l4 4v14H6z\"/><path d=\"M14 3v5h5M9 13h6M9 17h4\"/>",
    "flame" to "<path d=\"M12 22c4 0 7-3 7-7 0-5-5-7-5-12-3 2-5 5-5 8-1-1-2-2-2-4-2 2-2 5-2 8 0 4 3 7 7 7z\"/>",
    "pin" to "<path d=\"M12 22s7-6.5 7-12a7 7 0 0 0-14 0c0 5.5 7 12 7 12z\"/><circle cx=\"12\" cy=\"10\" r=\"2.5\"/>",
    "trophy" to "<path d=\"M7 4h10v5a5 5 0 0 1-10 0z\"/><path d=\"M7 6H4a3 3 0 0 0 3 4M17 6h3a3 3 0 0 1-3 4M12 14v4M8 21h8M9 18h6\"/>",
    "medal" to "<circle cx=\"12\" cy=\"15\" r=\"6\"/><path d=\"M8.5 10 5 3h4l3 5M15.5 10 19 3h-4l-3 5\"/>",
    "star" to "<path d=\"m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z\"/>",
    "chart" to "<path d=\"M4 20V10M10 20V4M16 20v-7M22 20H2\"/>",
    "list" to "<path d=\"M10 6h10M10 12h10M10 18h10\"/><path d=\"m3 6 1.5 1.5L7 5M3 12l1.5 1.5L7 11M3 18l1.5 1.5L7 17\"/>",
    "wallet" to "<rect x=\"3\" y=\"6\" width=\"18\" height=\"14\" rx=\"3\"/><path d=\"M3 10h18M16 15h2\"/>",
    "coin" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M14.5 9.5C14 8.5 13 8 12 8c-1.5 0-2.5.8-2.5 2 0 2.8 5 1.5 5 4 0 1.2-1 2-2.5 2-1.1 0-2-.5-2.5-1.5M12 6v2M12 16v2\"/>",
    "plus" to "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 8v8M8 12h8\"/>",
    "bowl" to "<path d=\"M3 11h18a9 9 0 0 1-18 0z\"/><path d=\"M8 7c0-1.5 1-2 1-3M12 7c0-1.5 1-2 1-3M16 7c0-1.5 1-2 1-3\"/>",
    "coffee" to "<path d=\"M4 9h13v5a6 6 0 0 1-6 6h-1a6 6 0 0 1-6-6z\"/><path d=\"M17 11h1.5a2.5 2.5 0 0 1 0 5H17M8 3v3M12 3v3\"/>",
    "store" to "<path d=\"M4 9 5.5 4h13L20 9M4 9h16v11H4zM10 20v-5h4v5\"/>",
    "heart" to "<path d=\"M12 20s-8-4.6-8-10.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8 2.5C20 15.4 12 20 12 20z\"/>",
    "game" to "<rect x=\"2\" y=\"7\" width=\"20\" height=\"11\" rx=\"5\"/><path d=\"M7 11v3M5.5 12.5h3M15 12h.01M18 13.5h.01\"/>",
    "film" to "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4\"/>",
    "run" to "<circle cx=\"15\" cy=\"4.5\" r=\"2\"/><path d=\"m6 21 3.5-6 3 2.5V21M5 11l3-3h5l3 4 3 1M10 15l3-7\"/>",
    "chat" to "<path d=\"M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z\"/><path d=\"M8 12h.01M12 12h.01M16 12h.01\"/>",
    "phone" to "<rect x=\"6\" y=\"2\" width=\"12\" height=\"20\" rx=\"3\"/><path d=\"M11 18h2\"/>",
    "qr" to "<rect x=\"3\" y=\"3\" width=\"7\" height=\"7\" rx=\"1\"/><rect x=\"14\" y=\"3\" width=\"7\" height=\"7\" rx=\"1\"/><rect x=\"3\" y=\"14\" width=\"7\" height=\"7\" rx=\"1\"/><path d=\"M14 14h3v3M21 14v7h-4M14 21v-3\"/>",
    "sparkle" to "<path d=\"M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z\"/><path d=\"M19 15v4M17 17h4\"/>",
    "egg" to "<path d=\"M12 3c4 0 7 6.5 7 11a7 7 0 0 1-14 0c0-4.5 3-11 7-11z\"/>",
    "plane" to "<path d=\"m22 2-7 20-4-9-9-4z\"/><path d=\"M22 2 11 13\"/>",
    "bolt" to "<path d=\"M13 2 4 14h7l-1 8 9-12h-7z\"/>",
    "bulb" to "<path d=\"M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.3h6c0-1 .4-1.8 1-2.3A7 7 0 0 0 12 2z\"/>",
)

/** 小闪光，插画周围点缀用。 */
private fun spark(x: Int, y: Int, s: Double, delay: Double, cls: String = "W") =
    "<g transform=\"translate($x $y) scale($s)\"><path class=\"tw $cls\" style=\"animation-delay:${delay}s\" " +
        "d=\"M0-8C1-2 2-1 8 0 2 1 1 2 0 8-1 2-2 1-8 0-2-1-1-2 0-8z\"/></g>"

private fun starPath(cx: Double, cy: Double, r: Double): String {
    val pts = (0 until 10).map { i ->
        val rad = if (i % 2 == 0) r else r * 0.45
        val ang = Math.PI / 5 * i - Math.PI / 2
        "%.1f %.1f".format(cx + rad * Math.cos(ang), cy + rad * Math.sin(ang))
    }
    return "M" + pts.joinToString("L") + "z"
}

/** 每页顶部的动画插画（160×120）。 */
private enum class Hero(body: String) {
    CALENDAR(
        "<g class=\"fl\"><rect x=\"38\" y=\"24\" width=\"84\" height=\"80\" rx=\"14\" class=\"W\"/>" +
            "<path class=\"A\" d=\"M38 38a14 14 0 0 1 14-14h56a14 14 0 0 1 14 14v10H38z\"/>" +
            "<rect x=\"56\" y=\"14\" width=\"7\" height=\"18\" rx=\"3.5\" class=\"K\"/><rect x=\"97\" y=\"14\" width=\"7\" height=\"18\" rx=\"3.5\" class=\"K\"/>" +
            (0 until 3).joinToString("") { r -> (0 until 4).joinToString("") { c ->
                "<rect x=\"${52 + c * 15}\" y=\"${58 + r * 14}\" width=\"9\" height=\"8\" rx=\"2\" class=\"G\"/>"
            } } +
            "<circle cx=\"86.5\" cy=\"76\" r=\"9\" class=\"A pl\"/></g>" +
            spark(24, 34, 1.0, 0.0) + spark(138, 28, 0.8, 0.7, "A") + spark(134, 92, 0.6, 1.4) + spark(22, 90, 0.7, 2.0, "A"),
    ),
    BOOK(
        "<g class=\"fl\"><path class=\"W\" d=\"M80 32c-14-8-30-9-46-6v66c16-3 32-2 46 6z\"/>" +
            "<path class=\"W2\" d=\"M80 32c14-8 30-9 46-6v66c-16-3-32-2-46 6z\"/><rect x=\"78\" y=\"30\" width=\"4\" height=\"68\" rx=\"2\" class=\"A\"/>" +
            listOf(42, 52, 62, 72).withIndex().joinToString("") { (i, y) ->
                "<path class=\"L dr\" style=\"animation-delay:${i * 0.35}s\" d=\"M44 ${y}h${if (i % 2 == 0) 26 else 20}\"/>" +
                    "<path class=\"L dr\" style=\"animation-delay:${1.4 + i * 0.35}s\" d=\"M90 ${y}h${if (i % 2 == 1) 26 else 20}\"/>"
            } + "</g>" + spark(28, 22, 0.9, 0.3, "A") + spark(136, 30, 1.0, 1.1) + spark(140, 96, 0.6, 1.8, "A"),
    ),
    CHECK(
        "<circle cx=\"80\" cy=\"60\" r=\"34\" class=\"A ripple\"/><circle cx=\"80\" cy=\"60\" r=\"34\" class=\"A ripple\" style=\"animation-delay:1.2s\"/>" +
            "<g class=\"fl\"><circle cx=\"80\" cy=\"60\" r=\"34\" class=\"A\"/><circle cx=\"80\" cy=\"60\" r=\"26\" class=\"G2\"/>" +
            "<path class=\"S dr\" style=\"--len:60\" d=\"M65 61l10 10 21-23\"/></g>" +
            spark(30, 30, 0.9, 0.2) + spark(132, 34, 0.7, 0.9, "A") + spark(128, 94, 1.0, 1.6),
    ),
    TROPHY(
        "<g class=\"fl\"><path class=\"SA5\" d=\"M58 34H46a12 12 0 0 0 14 16M102 34h12a12 12 0 0 1-14 16\"/>" +
            "<path class=\"A\" d=\"M56 24h48v22a24 24 0 0 1-48 0z\"/><path class=\"shine\" d=\"M64 26h9L63 66h-7z\"/>" +
            "<rect x=\"75\" y=\"68\" width=\"10\" height=\"14\" class=\"A\"/><rect x=\"60\" y=\"82\" width=\"40\" height=\"12\" rx=\"5\" class=\"W\"/>" +
            "<path class=\"W\" d=\"${starPath(80.0, 44.0, 9.0)}\"/></g>" +
            spark(32, 26, 1.1, 0.0, "Y") + spark(130, 24, 0.8, 0.6) + spark(136, 84, 1.0, 1.2, "Y") + spark(26, 86, 0.7, 1.8),
    ),
    CLIPBOARD(
        "<g class=\"fl\"><rect x=\"48\" y=\"20\" width=\"64\" height=\"86\" rx=\"10\" class=\"W\"/>" +
            "<rect x=\"64\" y=\"13\" width=\"32\" height=\"14\" rx=\"6\" class=\"A\"/>" +
            (0 until 3).joinToString("") { i ->
                val y = 46 + i * 20
                "<rect x=\"58\" y=\"${y - 7}\" width=\"14\" height=\"14\" rx=\"4\" class=\"G\"/>" +
                    "<path class=\"SA dr\" style=\"--len:16;animation-delay:${i * 0.6}s\" d=\"M61 ${y}l3 3.5 6-7\"/>" +
                    "<rect x=\"78\" y=\"${y - 3}\" width=\"${if (i == 1) 18 else 24}\" height=\"6\" rx=\"3\" class=\"G\"/>"
            } + "</g>" + spark(30, 40, 0.9, 0.4, "A") + spark(134, 30, 0.8, 1.0) + spark(132, 96, 0.7, 1.7, "A"),
    ),
    WALLET(
        listOf(64, 82, 100).withIndex().joinToString("") { (i, x) ->
            "<g class=\"drop\" style=\"animation-delay:${i * 0.7}s\"><circle cx=\"$x\" cy=\"20\" r=\"9\" class=\"Y\"/>" +
                "<circle cx=\"$x\" cy=\"20\" r=\"5\" fill=\"none\" stroke=\"rgba(0,0,0,.18)\" stroke-width=\"2\"/></g>"
        } +
            "<g class=\"fl\"><rect x=\"38\" y=\"46\" width=\"84\" height=\"56\" rx=\"13\" class=\"A\"/>" +
            "<rect x=\"38\" y=\"46\" width=\"84\" height=\"14\" rx=\"7\" class=\"K\"/>" +
            "<rect x=\"94\" y=\"64\" width=\"34\" height=\"22\" rx=\"9\" class=\"W\"/><circle cx=\"106\" cy=\"75\" r=\"4\" class=\"A\"/></g>" +
            spark(26, 40, 0.8, 0.5) + spark(140, 40, 1.0, 1.3, "Y"),
    ),
    BOWL(
        listOf(64, 80, 96).withIndex().joinToString("") { (i, x) ->
            "<path class=\"steam\" style=\"animation-delay:${i * 0.6}s\" d=\"M$x 44c-6-6 6-10 0-16s6-10 0-16\"/>"
        } +
            "<g class=\"fl\"><path class=\"SK\" d=\"M98 40 134 16M104 44l34-22\"/>" +
            "<path class=\"W\" d=\"M36 58h88a44 40 0 0 1-88 0z\"/><rect x=\"32\" y=\"53\" width=\"96\" height=\"10\" rx=\"5\" class=\"A\"/>" +
            "<path class=\"G\" d=\"M56 78h48a24 16 0 0 1-48 0z\"/></g>" + spark(24, 30, 0.9, 0.0, "A") + spark(140, 84, 0.8, 1.0),
    ),
    EGG(
        "<g class=\"wob\"><path class=\"W\" d=\"M80 16c18 0 32 30 32 54a32 32 0 0 1-64 0c0-24 14-54 32-54z\"/>" +
            "<path class=\"SA\" d=\"M50 68l10-8 8 8 8-8 8 8 8-8 8 8 10-8\"/>" +
            "<circle cx=\"68\" cy=\"84\" r=\"5\" class=\"A\"/><circle cx=\"92\" cy=\"88\" r=\"4\" class=\"A\"/><circle cx=\"80\" cy=\"40\" r=\"4\" class=\"A\"/></g>" +
            spark(32, 30, 1.1, 0.0, "A") + spark(128, 26, 0.8, 0.5) + spark(134, 90, 1.0, 1.0, "A") + spark(26, 92, 0.8, 1.5),
    ),
    GAMEPAD(
        "<g class=\"fl\"><rect x=\"32\" y=\"40\" width=\"96\" height=\"52\" rx=\"26\" class=\"W\"/>" +
            "<path class=\"SA5\" d=\"M58 56v20M48 66h20\"/>" +
            "<circle cx=\"102\" cy=\"58\" r=\"6\" class=\"A bn\"/><circle cx=\"114\" cy=\"72\" r=\"6\" class=\"A bn\" style=\"animation-delay:.25s\"/></g>" +
            "<g class=\"tw\"><path class=\"SW\" d=\"M28 20l6 10H22z\"/></g><g class=\"tw\" style=\"animation-delay:.8s\"><circle cx=\"136\" cy=\"24\" r=\"6\" class=\"SW\"/></g>" +
            "<g class=\"tw\" style=\"animation-delay:1.5s\"><path class=\"SW\" d=\"M132 98l8 8M140 98l-8 8\"/></g>" + spark(24, 100, 0.8, 0.4, "A"),
    ),
    CHAT(
        "<g class=\"fl\"><path class=\"W\" d=\"M28 32a12 12 0 0 1 12-12h58a12 12 0 0 1 12 12v26a12 12 0 0 1-12 12H58L42 82V70a12 12 0 0 1-14-12z\"/>" +
            listOf(50, 69, 88).withIndex().joinToString("") { (i, x) ->
                "<circle cx=\"$x\" cy=\"45\" r=\"5.5\" class=\"A bn\" style=\"animation-delay:${i * 0.18}s\"/>"
            } + "</g>" +
            "<g class=\"fl\" style=\"animation-delay:-1.5s\"><path class=\"A\" d=\"M86 74a10 10 0 0 1 10-10h30a10 10 0 0 1 10 10v14a10 10 0 0 1-10 10h-2v9l-12-9H96a10 10 0 0 1-10-10z\"/>" +
            "<path class=\"S3\" d=\"M99 81l5 0M110 81l13 0\"/></g>" + spark(140, 30, 0.9, 0.3) + spark(22, 96, 0.8, 1.2, "A"),
    ),
    PHONE(
        "<g class=\"fl\"><rect x=\"54\" y=\"10\" width=\"52\" height=\"100\" rx=\"13\" class=\"W\"/>" +
            "<rect x=\"60\" y=\"22\" width=\"40\" height=\"76\" rx=\"5\" class=\"G\"/>" +
            (0 until 3).joinToString("") { r -> (0 until 2).joinToString("") { c ->
                "<rect x=\"${65 + c * 16}\" y=\"${28 + r * 18}\" width=\"13\" height=\"13\" rx=\"4\" class=\"A\" opacity=\"${0.55 + 0.15 * ((r + c) % 3)}\"/>"
            } } +
            "<circle cx=\"87.5\" cy=\"70.5\" r=\"7\" class=\"tap\"/><circle cx=\"87.5\" cy=\"70.5\" r=\"7\" class=\"tap\" style=\"animation-delay:.9s\"/></g>" +
            spark(30, 36, 1.0, 0.2, "A") + spark(132, 28, 0.8, 0.9) + spark(134, 92, 1.0, 1.5, "A"),
    ),
    PLANE(
        "<path class=\"trail\" d=\"M14 104C44 98 52 68 88 62\"/>" +
            "<g class=\"fly\"><path class=\"W\" d=\"M88 62l54-32-20 58-12-18z\"/><path class=\"A\" d=\"M110 70l32-40-36 34z\"/></g>" +
            "<g class=\"fl\" style=\"animation-delay:-2s\"><path class=\"W2\" d=\"M24 40a10 10 0 0 1 10-10 13 13 0 0 1 25 2 9 9 0 0 1 0 18H34a10 10 0 0 1-10-10z\" opacity=\".5\"/></g>" +
            spark(130, 104, 0.8, 0.5, "A") + spark(64, 20, 0.7, 1.2),
    ),
    MEDAL(
        "<g class=\"swing\"><path class=\"A\" d=\"M60 6h16l12 34H72zM100 6H84L72 40h16z\" opacity=\".85\"/>" +
            "<circle cx=\"80\" cy=\"70\" r=\"30\" class=\"Y\"/><circle cx=\"80\" cy=\"70\" r=\"22\" class=\"A\"/>" +
            "<path class=\"W\" d=\"${starPath(80.0, 70.0, 13.0)}\"/><path class=\"shine\" d=\"M66 48h8L60 92h-6z\"/></g>" +
            spark(28, 30, 1.1, 0.0, "Y") + spark(134, 36, 0.9, 0.6) + spark(132, 98, 1.0, 1.2, "Y") + spark(26, 96, 0.8, 1.8),
    );

    val svg = "<svg class=\"art\" viewBox=\"0 0 160 120\">$body</svg>"
}

private fun frac(f: Float, min: Float = 0.03f) = f.coerceIn(min, 1f).toString()

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val CSS = """
html,body{margin:0;height:100%;background:#101426;color:#fff;font-family:-apple-system,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif;-webkit-tap-highlight-color:transparent}
main{height:100%;overflow-y:scroll;scroll-snap-type:y mandatory}
section{position:relative;overflow:hidden;min-height:100vh;min-height:100dvh;scroll-snap-align:start;box-sizing:border-box;padding:max(env(safe-area-inset-top),72px) 24px max(env(safe-area-inset-bottom),44px);display:flex;flex-direction:column;justify-content:center}
section:not(.on) *{animation-play-state:paused!important}
.r{position:relative;z-index:1;opacity:0;transform:translateY(24px);transition:opacity .6s ease,transform .6s cubic-bezier(.2,.8,.2,1)}
section.on .r{opacity:1;transform:none}
.bg{position:absolute;inset:0;z-index:0;pointer-events:none}
.bg>svg{position:absolute}
.glow{position:absolute;border-radius:50%;background:radial-gradient(circle,var(--a),transparent 68%);opacity:.22;animation:drift 14s ease-in-out infinite}
.g1{width:80vw;height:80vw;right:-30vw;top:-16vw}.g2{width:70vw;height:70vw;left:-30vw;bottom:-20vw;animation-delay:-7s;opacity:.14}
.hero{display:flex;justify-content:center;margin:0 0 6px}
.art{width:168px;height:126px;overflow:visible;filter:drop-shadow(0 10px 22px rgba(0,0,0,.28))}
.art *{transform-box:fill-box;transform-origin:center}
.W{fill:#fff}.W2{fill:rgba(255,255,255,.82)}.A{fill:var(--a)}.Y{fill:#FFD36E}.K{fill:rgba(0,0,0,.28)}.G{fill:rgba(0,0,0,.1)}.G2{fill:rgba(255,255,255,.22)}
.S,.S3,.SA,.SA5,.SK,.SW,.L{fill:none;stroke-linecap:round;stroke-linejoin:round}
.S{stroke:#fff;stroke-width:7}.S3{stroke:rgba(0,0,0,.25);stroke-width:4}.SA{stroke:var(--a);stroke-width:4}.SA5{stroke:var(--a);stroke-width:6}
.SK{stroke:rgba(255,255,255,.75);stroke-width:4}.SW{stroke:rgba(255,255,255,.7);stroke-width:3;fill:none}.L{stroke:rgba(0,0,0,.2);stroke-width:3.5}
.fl{animation:float 4s ease-in-out infinite}
.tw{animation:twinkle 2.4s ease-in-out infinite}
.pl{animation:pulse 1.8s ease-in-out infinite}
.dr{stroke-dasharray:var(--len,30);stroke-dashoffset:var(--len,30);animation:draw 3.2s ease-in-out infinite}
.ripple{animation:ripple 2.4s ease-out infinite;opacity:0}
.bn{animation:bounce 1.2s ease-in-out infinite}
.drop{animation:drop 2.1s ease-in infinite;opacity:0}
.steam{fill:none;stroke:rgba(255,255,255,.7);stroke-width:4;stroke-linecap:round;animation:steam 2.4s ease-in-out infinite;opacity:0}
.wob{transform-origin:50% 90%!important;animation:wobble 1.6s ease-in-out infinite}
.swing{transform-origin:50% 0!important;animation:swing 3s ease-in-out infinite}
.shine{fill:rgba(255,255,255,.55);animation:shine 3.4s ease-in-out infinite}
.tap{fill:none;stroke:#fff;stroke-width:2.5;animation:ripple 1.8s ease-out infinite;opacity:0}
.trail{fill:none;stroke:rgba(255,255,255,.45);stroke-width:3;stroke-linecap:round;stroke-dasharray:3 9;animation:dash 1.2s linear infinite}
.fly{animation:fly 3.6s ease-in-out infinite}
@keyframes float{0%,100%{transform:translateY(0)}50%{transform:translateY(-7px)}}
@keyframes twinkle{0%,100%{opacity:.25;transform:scale(.55) rotate(0)}50%{opacity:1;transform:scale(1) rotate(45deg)}}
@keyframes pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.25)}}
@keyframes draw{0%{stroke-dashoffset:var(--len,30)}35%,80%{stroke-dashoffset:0;opacity:1}100%{stroke-dashoffset:0;opacity:0}}
@keyframes ripple{0%{transform:scale(1);opacity:.6}100%{transform:scale(1.7);opacity:0}}
@keyframes bounce{0%,100%{transform:translateY(0)}40%{transform:translateY(-7px)}}
@keyframes drop{0%{transform:translateY(-14px);opacity:0}20%{opacity:1}70%{transform:translateY(34px);opacity:1}85%,100%{transform:translateY(40px) scale(.6);opacity:0}}
@keyframes steam{0%{transform:translateY(8px);opacity:0}40%{opacity:.85}100%{transform:translateY(-12px);opacity:0}}
@keyframes wobble{0%,100%{transform:rotate(-7deg)}50%{transform:rotate(7deg)}}
@keyframes swing{0%,100%{transform:rotate(-6deg)}50%{transform:rotate(6deg)}}
@keyframes shine{0%,60%{transform:translateX(-10px);opacity:0}70%{opacity:1}100%{transform:translateX(44px);opacity:0}}
@keyframes dash{to{stroke-dashoffset:-24}}
@keyframes fly{0%,100%{transform:translate(0,0) rotate(0)}50%{transform:translate(6px,-8px) rotate(-4deg)}}
@keyframes drift{0%,100%{transform:translate(0,0) scale(1)}50%{transform:translate(-4vw,3vw) scale(1.12)}}
@keyframes nudge{0%,100%{transform:translateY(0)}50%{transform:translateY(-5px)}}
.ic{width:18px;height:18px;flex:none;fill:none;stroke:var(--a);stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
.kicker{align-self:flex-start;display:inline-flex;align-items:center;gap:7px;font-size:14px;font-weight:600;padding:6px 13px 6px 10px;border-radius:999px;background:rgba(255,255,255,.13);border:1px solid rgba(255,255,255,.16);margin:4px 0 6px}
.big{font-weight:800;line-height:1.08;margin:6px 0 4px;letter-spacing:-.5px;font-variant-numeric:tabular-nums;background:linear-gradient(180deg,#fff 30%,var(--a));-webkit-background-clip:text;background-clip:text;color:transparent}
.unit{font-size:.36em;font-weight:700;margin:0 4px;letter-spacing:0}
.body{font-size:16px;line-height:1.6;opacity:.92;margin:2px 0}
.small{font-size:13px;opacity:.68;margin:4px 0;line-height:1.5}
.quote{position:relative;margin:12px 0 8px;padding:14px 16px 14px 20px;border-radius:18px;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.14);font-size:16px;line-height:1.6;font-weight:500}
.quote::before{content:"\201C";position:absolute;left:8px;top:-16px;font-size:46px;line-height:1;color:var(--a);font-family:Georgia,serif}
.tiles{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:10px 0}
.tile{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:16px;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.12);min-width:0}
.tile>div{min-width:0}
.tile .ic{width:20px;height:20px}
.tile .l{font-size:12px;opacity:.68}.tile .v{font-size:16px;font-weight:700;margin-top:2px;overflow-wrap:anywhere;line-height:1.35}
.note{display:flex;align-items:center;gap:11px;font-size:14px;line-height:1.45;margin:7px 0}
.note i{flex:none;width:32px;height:32px;border-radius:11px;background:rgba(255,255,255,.13);display:flex;align-items:center;justify-content:center}
.hint{align-self:center;display:flex;flex-direction:column;align-items:center;font-size:12px;opacity:.6;margin-top:22px;animation:nudge 1.6s ease-in-out infinite}
.hint svg{width:22px;height:22px;fill:none;stroke:#fff;stroke-width:2.5;stroke-linecap:round;stroke-linejoin:round}
.vbars{display:flex;align-items:flex-end;gap:8px;height:128px;margin:16px 0 8px;padding:12px 10px 8px;border-radius:18px;background:rgba(255,255,255,.07)}
.vbars>div{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;height:100%}
.vbars .bar{width:14px;height:0;border-radius:7px;background:linear-gradient(180deg,#fff,var(--a));transition:height .9s cubic-bezier(.2,.8,.2,1);margin-bottom:6px}
section.on .vbars .bar{height:calc(var(--v) * 88px)}
.hbar{margin:9px 0}.hbar .row{display:flex;justify-content:space-between;font-size:14px}
.hbar .track{height:9px;border-radius:6px;background:rgba(255,255,255,.14);margin-top:5px;overflow:hidden}
.hbar .fill{height:100%;width:0;border-radius:6px;background:linear-gradient(90deg,var(--a),#fff);transition:width .9s cubic-bezier(.2,.8,.2,1)}
section.on .hbar .fill{width:calc(var(--v) * 100%)}
.ringrow{display:flex;align-items:center;gap:20px;margin:12px 0}
.ring{width:96px;height:96px;transform:rotate(-90deg);flex:none}
.ring circle{fill:none;stroke-width:10}.ring .bg{stroke:rgba(255,255,255,.16)}
.ring .fg{stroke:var(--a);stroke-linecap:round;stroke-dasharray:283;stroke-dashoffset:283;transition:stroke-dashoffset 1.2s ease}
section.on .ring .fg{stroke-dashoffset:calc(283 * (1 - var(--v)))}
.poster{position:relative;border-radius:26px;padding:22px 20px;background:linear-gradient(140deg,rgba(255,255,255,.16),rgba(255,255,255,.05));border:1px solid rgba(255,255,255,.2);box-shadow:0 16px 40px rgba(0,0,0,.25)}
.pt{display:flex;align-items:center;gap:7px;font-size:13px;opacity:.85}
.tags{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}.tags span{font-size:13px;padding:5px 11px;border-radius:999px;background:rgba(255,255,255,.16);border:1px solid rgba(255,255,255,.14)}
.hl{display:flex;gap:8px;align-items:flex-start;font-size:15px;line-height:1.5;margin:6px 0}.hl .ic{margin-top:2px;width:16px;height:16px}
.sign{margin-top:14px;font-size:12px;opacity:.55;letter-spacing:2px}
.sec{margin:14px 0 2px;font-size:12px;font-weight:700;letter-spacing:2px;color:var(--a);opacity:.95}
.wrap{position:relative;z-index:1;width:100%;max-width:460px;margin:0 auto;display:flex;flex-direction:column}
.stack{margin:12px 0 6px}
.seg{display:flex;height:14px;border-radius:8px;overflow:hidden;background:rgba(255,255,255,.12)}
.seg i{width:0;background:linear-gradient(90deg,var(--a),#fff);transition:width 1s cubic-bezier(.2,.8,.2,1)}
.seg i+i{border-left:2px solid rgba(0,0,0,.2)}
section.on .seg i{width:var(--w)}
.legend{display:flex;flex-wrap:wrap;gap:6px 12px;margin-top:8px;font-size:12px;opacity:.85}
.legend span{display:inline-flex;align-items:center;gap:5px}.legend b{width:9px;height:9px;border-radius:3px;background:var(--a)}
.clockrow{display:flex;align-items:center;gap:16px;margin:12px 0;padding:10px 14px 10px 10px;border-radius:20px;background:rgba(255,255,255,.08)}
.clock{width:118px;height:118px;flex:none}
.clock .face{fill:rgba(0,0,0,.14);stroke:rgba(255,255,255,.16);stroke-width:1}
.clock .tick{stroke:rgba(255,255,255,.35);stroke-width:1.5;stroke-linecap:round}.clock .major{stroke:#fff;stroke-width:2.5}
.clock text{fill:rgba(255,255,255,.75);font-size:11px;text-anchor:middle;font-weight:600}
.clock .arc{fill:none;stroke:var(--a);stroke-width:10;stroke-linecap:round;stroke-dasharray:100;stroke-dashoffset:100;transition:stroke-dashoffset 1.4s ease .3s}
section.on .clock .arc{stroke-dashoffset:0}
.clock .d1{fill:#fff;stroke:var(--a);stroke-width:3}.clock .d2{fill:var(--a);stroke:#fff;stroke-width:3}
.ctime{font-size:22px;font-weight:800;font-variant-numeric:tabular-nums}.ctime span{opacity:.5;font-weight:400}
.radar{width:100%;max-width:340px;align-self:center;margin:4px auto 0;overflow:visible}
.radar .grid{fill:none;stroke:rgba(255,255,255,.2);stroke-width:1}
.radar .area{fill:var(--a);fill-opacity:.38;stroke:var(--a);stroke-width:2.5;stroke-linejoin:round}
.radar .pt{fill:#fff}
.radar text{fill:#fff;font-size:13px;font-weight:600;text-anchor:middle;opacity:.9}
.radar .shape{transform-box:view-box;transform-origin:130px 112px;transform:scale(0);transition:transform 1.1s cubic-bezier(.3,1.4,.5,1) .3s}
section.on .radar .shape{transform:scale(1)}
.vbars .val{font-size:11px;font-weight:700;opacity:.85;margin-bottom:4px;font-variant-numeric:tabular-nums;white-space:nowrap}
.hrs{margin:12px 0 6px;padding:12px 12px 8px;border-radius:18px;background:rgba(255,255,255,.07)}
.hrs .cols{display:flex;align-items:flex-end;gap:3px;height:72px}
.hrs i{flex:1;height:0;border-radius:3px;background:rgba(255,255,255,.4);transition:height .8s cubic-bezier(.2,.8,.2,1)}
.hrs i.pk{background:linear-gradient(180deg,#fff,var(--a))}
section.on .hrs i{height:calc(var(--v) * 100%)}
.hrs .axis{display:flex;justify-content:space-between;font-size:10px;opacity:.55;margin:4px 0 2px}
.heat{width:100%;margin:12px 0 4px;overflow:visible}
.heat text{fill:rgba(255,255,255,.6);font-size:9px;text-anchor:end}
.heat rect{opacity:0;transition:opacity .5s ease}
section.on .heat rect{opacity:1}
.heat .l0{fill:rgba(255,255,255,.08)}.heat .l1{fill:var(--a);fill-opacity:.3}.heat .l2{fill:var(--a);fill-opacity:.55}
.heat .l3{fill:var(--a);fill-opacity:.8}.heat .l4{fill:#fff}
.heatlg{align-items:center;justify-content:flex-end;gap:4px}.heatlg b{width:10px;height:10px;border-radius:3px}
.stars{width:100%;margin:10px 0 4px;overflow:visible}
.stars .area{fill:var(--a);fill-opacity:0;transition:fill-opacity 1s ease 1.2s}
section.on .stars .area{fill-opacity:.16}
.stars .ln{fill:none;stroke:var(--a);stroke-width:2.5;stroke-linejoin:round;stroke-dasharray:100;stroke-dashoffset:100;transition:stroke-dashoffset 2s ease .3s}
section.on .stars .ln{stroke-dashoffset:0}
.stars .mk{fill:#fff;stroke:var(--a);stroke-width:2.5}.stars text{fill:#fff;font-size:10px;font-weight:700}
.wall{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 12px;margin:8px 0 6px}
.wall span{font-weight:600;opacity:0;transform:translateY(6px);transition:opacity .5s ease,transform .5s ease}
section.on .wall span{opacity:.92;transform:none}
.wall b{display:inline-block;width:5px;height:5px;border-radius:50%;background:var(--a);margin-left:3px;vertical-align:super}
.cta{align-self:center;margin:16px 0 4px;padding:11px 22px;border-radius:999px;background:var(--a);color:#1a1530;font-weight:800;font-size:15px;text-decoration:none;box-shadow:0 8px 24px rgba(0,0,0,.25)}
.heatlg b.l0{background:rgba(255,255,255,.08)}.heatlg b.l1{opacity:.3}.heatlg b.l2{opacity:.55}.heatlg b.l3{opacity:.8}.heatlg b.l4{background:#fff}
@media (max-height:740px){
section{padding-top:max(env(safe-area-inset-top),62px);padding-bottom:max(env(safe-area-inset-bottom),32px)}
.art{width:124px;height:93px}.hero{margin:0}
.tiles{gap:8px;margin:8px 0}.tile{padding:8px 10px;gap:8px}.tile .ic{width:18px;height:18px}
.quote{margin:8px 0 6px;padding:11px 14px 11px 18px;font-size:15px}
.note{margin:5px 0}.vbars{height:108px;margin:10px 0 6px}section.on .vbars .bar{height:calc(var(--v) * 70px)}
.clock{width:100px;height:100px}.radar{max-width:280px}
}
@media (max-width:350px){.tiles{grid-template-columns:1fr}.tile .v{font-size:15px}}
@media (min-width:600px){.wrap{max-width:560px}.tiles{grid-template-columns:repeat(3,1fr)}.art{width:200px;height:150px}}
"""

private const val JS = """
function countUp(el){var to=+el.dataset.count,t0=null;
function step(t){if(!t0)t0=t;var p=Math.min(1,(t-t0)/1000);el.textContent=Math.round(to*(1-Math.pow(1-p,3)));if(p<1)requestAnimationFrame(step);}
requestAnimationFrame(step);}
var io=new IntersectionObserver(function(es){es.forEach(function(e){if(!e.isIntersecting)return;var s=e.target;if(s.classList.contains('on'))return;
s.classList.add('on');s.querySelectorAll('[data-count]').forEach(countUp);});},{threshold:0.3});
document.querySelectorAll('section').forEach(function(s){io.observe(s);});
"""
