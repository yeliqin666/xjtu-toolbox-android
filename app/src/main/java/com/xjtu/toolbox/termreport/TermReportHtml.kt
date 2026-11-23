package com.xjtu.toolbox.termreport

/**
 * 把学期总结排成一份自包含的 HTML（年终总结那样的整屏竖滑页）。
 * 不引外部资源，存下来以后用任何 WebView 打开都一样。
 */
fun TermReport.toHtml(): String {
    val copy = StoryCopy(term.code)
    val title = mainTitle()
    val slides = buildList {
        add(slide("#1B1F4B", "#3A2F7A") {
            small(termName)
            small(copy.pick("cover-lead", "这学期一共", "从开学到最后一周，一共", "这一段旅程，一共"))
            count(days.toLong(), 72, suf = " 天")
            body("$start — $end")
            body(copy.pick("cover", "学期结束啦，一起看看这段时间", "辛苦了，来看看这学期的你", "翻篇之前，先回头看一眼", "这学期的你，都在这里了"))
            small("上滑继续 ↑")
        })
        courses?.let { c ->
            add(slide("#0F3D5E", "#1F7A8C") {
                small(copy.pick("course-lead", "这学期你上了", "课表上一共排了", "你和这些课朝夕相处："))
                count(c.courses.toLong(), 56, suf = " 门课")
                body("一共 ${c.totalSections} 节，折合 ${Equivalents.classHours(c.totalSections)} 小时，平均每周 ${c.weeklyAvg} 节")
                val max = (c.perWeekday.maxOrNull() ?: 0).coerceAtLeast(1)
                vbars(c.perWeekday.mapIndexed { i, n -> weekdayName(i).drop(1) to n.toFloat() / max })
                c.perWeekday.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let {
                    body(copy.pick("course-busy", "${weekdayName(it.index)}是你最满的一天", "${weekdayName(it.index)}的课表最挤", "每到${weekdayName(it.index)}，你都格外忙"))
                }
                when {
                    c.earlyClasses >= 40 -> body(copy.pick("early-hi", "早八 ${c.earlyClasses} 次，你是真正的早八战士", "${c.earlyClasses} 个早八，太阳都没你起得早", "早八 ${c.earlyClasses} 次，致敬每一个挣扎起床的清晨"))
                    c.earlyClasses > 0 -> body(copy.pick("early-mid", "其中早八 ${c.earlyClasses} 次，每一次起床都算数", "早八 ${c.earlyClasses} 次，被窝没能留住你", "${c.earlyClasses} 个早八，辛苦啦"))
                    else -> body(copy.pick("early-none", "一个早八都没有，排课运气拉满", "零早八，懂得生活"))
                }
                c.topBuilding?.let { (b, n) -> small("在${b}上了 $n 节，比宿舍还熟") }
                c.topTeacher?.let { (t, n) -> small("${t}老师陪你上了 $n 节") }
                if (c.customEvents > 0) small("你还自己排了 ${c.customEvents} 条日程，时间管理有一套")
            })
        }
        attendance?.let { a ->
            add(slide("#14324A", "#2C7DA0") {
                small(copy.pick("att-lead", "这学期你打卡", "考勤机记下了你", "你出现在教室里"))
                count(a.total.toLong(), 56, suf = " 次")
                big("${(a.rate * 100).toInt()}%", 40)
                body(when {
                    a.late == 0 && a.absent == 0 -> copy.pick("att-perfect", "零迟到零缺勤，全勤模范就是你", "一次没落下，老师都记住你了", "满勤！这份自律值得一个大大的赞")
                    a.rate >= 0.95f -> copy.pick("att-hi", "到场率超高，靠谱本谱", "几乎每节都在，稳", "出勤这块，你没让人操心")
                    a.rate >= 0.8f -> copy.pick("att-mid", "大部分时候都在线，不错", "偶尔掉线，整体靠谱", "出勤还行，下学期再稳一点")
                    else -> copy.pick("att-lo", "自由的灵魂不被打卡定义——下学期给老师一点面子？", "出勤有点佛系，重要的课先保住", "下学期多去几次，说不定会爱上某门课")
                })
                fact("正常", "${a.normal} 次")
                if (a.late > 0) fact("迟到", "${a.late} 次")
                if (a.absent > 0) fact("缺勤", "${a.absent} 次")
                if (a.leave > 0) fact("请假", "${a.leave} 次")
                if (a.perfectCourses > 0) body("有 ${a.perfectCourses} 门课一次没迟到")
                if (a.longestStreak >= 10) body("最长连续准时 ${a.longestStreak} 次")
                a.mostLate?.takeIf { it.second >= 2 }?.let { small("最容易迟到的是「${it.first}」，${it.second} 次") }
                a.topPlace?.let { small("最常去的教室：${it.first}") }
            })
        }
        grades?.let { g ->
            add(slide("#3B1E54", "#9B3675") {
                small(copy.pick("gpa-lead", "这学期的 GPA", "这学期你交出的答卷", "成绩单上的数字是"))
                big("%.2f".format(g.gpa), 72)
                g.previousGpa?.let { p ->
                    val d = g.gpa - p
                    body(when {
                        d >= 0.3 -> copy.pick("gpa-up-big", "比上学期高了 %.2f，这是一次漂亮的逆袭".format(d), "猛涨 %.2f，努力被看见了".format(d))
                        d > 0.005 -> copy.pick("gpa-up", "比上学期高了 %.2f，进步看得见".format(d), "又往上走了 %.2f，稳步上分".format(d))
                        d < -0.005 -> copy.pick("gpa-down", "比上学期低了 %.2f，下学期再找回来".format(-d), "小滑坡 %.2f 而已，调整一下就好".format(-d))
                        else -> copy.pick("gpa-flat", "和上学期持平，稳", "纹丝不动，稳定输出")
                    })
                } ?: body(when {
                    g.gpa >= 3.8 -> copy.pick("gpa-hi", "这个成绩，可以骄傲一下", "学霸认证，没有疑问")
                    g.gpa >= 3.3 -> copy.pick("gpa-mid", "扎实的一学期", "稳稳当当，值得点赞")
                    else -> copy.pick("gpa-lo", "分数只是一部分，你还收获了别的", "下学期挑几门重点突破")
                })
                body("${g.count} 门课 · %.1f 学分 · 均分 %.1f".format(g.credits, g.average))
                val max = g.bands.maxOf { it.second }.coerceAtLeast(1)
                g.bands.filter { it.second > 0 }.forEach { (label, n) -> hbar(label, "$n 门", n.toFloat() / max) }
                g.best?.let { body("最高分：${it.courseName} ${it.score}") }
                if (g.top95 > 0) small("${g.top95} 门 95 分以上，满分猎手")
                if (g.failed > 0) body(copy.pick("failed", "有 ${g.failed} 门没过，补考和重修都还来得及", "${g.failed} 门暂时没过，别慌，机会还在"))
            })
        }
        todos?.let { t ->
            add(slide("#113D2E", "#2E8B57") {
                small(copy.pick("todo-lead", "你给自己定了", "待办清单上写过", "这学期你计划了"))
                count(t.created.toLong(), 56, suf = " 件事")
                ring(t.rate, "${(t.rate * 100).toInt()}%", "完成了 ${t.done} 件")
                body(when {
                    t.rate >= 0.85f -> copy.pick("todo-hi", "说到做到，执行力拉满", "清单杀手，勾勾勾", "你定的事，几乎都办成了")
                    t.rate >= 0.5f -> copy.pick("todo-mid", "大半都搞定了，不赖", "一半以上打了勾，继续保持")
                    else -> copy.pick("todo-lo", "记下来就是开始，下学期挑几件最要紧的先做", "计划比做完多一点，正常，慢慢来")
                })
                if (t.overdue > 0) small("还有 ${t.overdue} 件过了截止没勾掉，可能早做完只是忘了点")
            })
        }
        card?.let { c ->
            add(slide("#4A2511", "#C0632B") {
                small(copy.pick("card-lead", "校园卡一共花了", "这学期一卡通刷出去", "钱包这学期流出了"))
                count(c.spend.toLong(), 60, pre = "¥")
                body("相当于 ${Equivalents.noodles(c.spend)} 碗牛肉面，刷卡的日子有 ${c.activeDays} 天")
                if (c.from > start) small("本机流水从 ${c.from} 起，之前的没算进来")
                val maxMonth = c.monthly.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                vbars(c.monthly.map { (m, v) -> "${m.takeLast(2).trimStart('0')}月" to (v / maxMonth).toFloat() })
                val maxCat = c.categories.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                c.categories.forEach { (name, v) -> hbar(name, "¥%.0f".format(v), (v / maxCat).toFloat()) }
                c.topWeekday?.let { (d, _) -> small("${weekdayName(d)}是你最舍得花钱的日子") }
                c.priciestDay?.let { (d, v) -> small("花得最多的一天：${d.monthNumber} 月 ${d.dayOfMonth} 日，¥%.0f".format(v)) }
                c.priciest?.let { small("单笔最大：¥%.0f · ${it.displayMerchant}".format(-it.amount)) }
                if (c.recharges > 0) small("充值 ${c.recharges} 次")
            })
            c.meal?.let { m ->
                add(slide("#5A1E1E", "#D9534F") {
                    small(copy.pick("meal-lead", "这学期你在学校吃了", "食堂记住了你", "一日三餐，你一共吃了"))
                    count(m.meals.toLong(), 64, suf = " 顿")
                    body("平均一顿 ¥%.1f，吃饭占了支出的 %d%%".format(m.avgMeal, (m.mealShare * 100).toInt()))
                    m.canteen?.let { fact("最常去", "${it.first} · ${it.second} 次") }
                    m.stall?.let { fact("最爱档口", "${it.first} · ${it.second} 次") }
                    m.earliest?.let { fact("最早一顿", "${it.time.drop(11).take(5)} ${it.displayMerchant}") }
                    m.latest?.let { fact("最晚一顿", "${it.time.drop(11).take(5)} ${it.displayMerchant}") }
                    if (m.breakfastDays > 0) fact("吃了早饭", "${m.breakfastDays} 天")
                    if (m.lateNights > 0) fact("夜宵", "${m.lateNights} 次")
                    if (m.drinks > 0) fact("饮品", "${m.drinks} 杯")
                    body(when {
                        m.lateNights >= 15 -> copy.pick("meal-night", "夜宵是你和校园最温柔的约定", "深夜的食堂灯光，你最熟")
                        m.breakfastDays >= 40 -> copy.pick("meal-bf", "早饭从不缺席，自律从胃开始", "早餐打卡王，上午的你一定很清醒")
                        m.avgMeal > 0 && m.avgMeal <= 11 -> copy.pick("meal-cheap", "精打细算，吃得好也吃得省", "性价比之王")
                        else -> copy.pick("meal-mid", "好好吃饭的人，运气都不会太差", "吃饱了，才有力气学习")
                    })
                })
            }
        }
        val egg = easterEgg(copy)
        if (egg != null) add(slide("#2A0F3D", "#B83280") {
            small("🥚 彩蛋")
            big(egg.first, 30)
            body(egg.second)
        })
        add(slide("#1E2A5A", "#4F6BD9") {
            small("课余时间")
            val g = games
            if (g != null && g.minutes > 0) {
                body(copy.pick("game-lead", "小游戏一共玩了", "摸鱼时间累计", "你在小游戏里度过了"))
                count(g.minutes, 52, suf = " 分钟")
                body("分布在 ${g.days} 天里" + (g.favorite?.let { "，最爱「$it」" } ?: "") +
                    (Equivalents.movies(g.minutes).takeIf { it > 0 }?.let { "，够看 $it 部电影" } ?: ""))
                g.longestDay?.let { (d, s) -> small("玩得最久的一天是 ${d.monthNumber} 月 ${d.dayOfMonth} 日，${s / 60} 分钟") }
            } else {
                body("小游戏时长从 6.0.3 开始记录，下学期就能看到")
            }
            g?.records?.take(3)?.forEach { small(it) }
            body("体测")
            big(fitness ?: "这次没读到", if (fitness != null) 32 else 20)
        })
        agent?.let { a ->
            add(slide("#233142", "#4FA3A5") {
                small(copy.pick("agent-lead", "你和小岱聊了", "小岱收到了你", "这学期你对小岱说了"))
                count(a.messages, 60, suf = " 句")
                body("${a.chats} 个对话，${a.days} 天里来找过它")
                if (a.topTools.isNotEmpty()) {
                    small("最常让它帮忙")
                    val max = a.topTools.first().second.coerceAtLeast(1)
                    a.topTools.forEach { (name, n) -> hbar(name, "$n 次", n.toFloat() / max) }
                }
                a.peakHour?.let { body("最常在 $it 点找它" + if (it >= 23 || it < 4) "，早点睡呀" else "") }
                if (a.lateNight > 0) small("深夜还聊了 ${a.lateNight} 句，它一直都在")
                body(copy.pick("agent-end", "谢谢你这学期的信任", "下学期也请多多关照", "有问题，随时来"))
            })
        }
        usage?.let { u ->
            add(slide("#2D1B4E", "#6A4C93") {
                small(copy.pick("use-lead", "这学期你打开岱宗盒子", "你和岱宗盒子见了", "这学期你点开工具箱"))
                count(u.launches, 56, suf = " 次")
                body("${u.days} 天里都用到了它")
                if (u.topFeatures.isNotEmpty()) {
                    small("最常用")
                    val max = u.topFeatures.first().second.coerceAtLeast(1)
                    u.topFeatures.forEach { (name, n) -> hbar(name, "$n 次", n.toFloat() / max) }
                }
                if (u.payCodeOpens > 0) small("亮了 ${u.payCodeOpens} 次付款码")
                if (u.lateLaunches > 0) small("凌晨还打开过 ${u.lateLaunches} 次，夜猫子实锤")
            })
        }
        add(slide("#0E3B43", "#2A9D8F") {
            small("写给下学期")
            advice(copy).forEach { body("· $it") }
            body(copy.pick("advice-end", "下学期见，祝一切顺利", "愿你下学期依旧闪闪发光", "新的学期，新的自己"))
        })
        add(slide("#16122E", "#4B2A7B") {
            raw(buildString {
                append("<div class=\"poster\"><div class=\"small\">${esc(termName)}</div><div class=\"small\">这学期的你是</div>")
                append("<div class=\"big\" style=\"font-size:40px\">「${esc(title.name)}」</div>")
                append("<div class=\"small\">${esc(title.reason)}</div>")
                val tags = sideTitles()
                if (tags.isNotEmpty()) append("<div class=\"tags\">${tags.joinToString("") { "<span>${esc(it)}</span>" }}</div>")
                highlights().forEach { append("<div class=\"body\">· ${esc(it)}</div>") }
                append("<div class=\"small\" style=\"margin-top:12px\">岱宗盒子 · 学期总结</div></div>")
            })
            if (missing.isNotEmpty()) small("生成时本机没有${missing.joinToString("、") { it.first }}的数据，加载后刷新就能补上")
        })
    }
    return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${esc(termName)} · 学期总结</title><style>$CSS</style></head>
<body><main>${slides.joinToString("")}</main><script>$JS</script></body></html>"""
}

/** 彩蛋：命中才出现，挑最有意思的一个。 */
private fun TermReport.easterEgg(copy: StoryCopy): Pair<String, String>? {
    card?.longestMealDay?.let { (d, first, last) ->
        return "${d.monthNumber} 月 ${d.dayOfMonth} 日" to copy.pick("egg-meal",
            "你从 $first 吃到了 $last，这一天是怎么熬过来的？", "从 $first 的第一口到 $last 的最后一口，这一天你一定很拼")
    }
    attendance?.takeIf { it.late == 0 && it.absent == 0 && it.total >= 30 }?.let {
        return "满勤" to "${it.total} 次打卡，一次没落下。这份坚持，比任何分数都珍贵"
    }
    courses?.takeIf { it.busiestDay.second >= 10 }?.let {
        return "最满的一天" to "有一个${weekdayName(it.busiestDay.first)}，你上满了 ${it.busiestDay.second} 节课。那天的你，值得一顿好吃的"
    }
    return null
}

private class SlideBuilder {
    val sb = StringBuilder()
    private var n = 0

    fun raw(html: String) {
        sb.append("<div class=\"r\" style=\"transition-delay:${n++ * 110}ms\">").append(html).append("</div>")
    }

    fun small(t: String) = raw("<div class=\"small\">${esc(t)}</div>")
    fun body(t: String) = raw("<div class=\"body\">${esc(t)}</div>")
    fun big(t: String, size: Int) = raw("<div class=\"big\" style=\"font-size:${size}px\">${esc(t)}</div>")
    fun count(value: Long, size: Int, pre: String = "", suf: String = "") = raw(
        "<div class=\"big\" style=\"font-size:${size}px\" data-count=\"$value\" data-pre=\"${esc(pre)}\" data-suf=\"${esc(suf)}\">" +
            "${esc(pre)}0${esc(suf)}</div>",
    )
    fun fact(label: String, value: String) = raw("<div class=\"fact\"><b>${esc(label)}</b><span>${esc(value)}</span></div>")

    fun hbar(label: String, value: String, fraction: Float) = raw(
        "<div class=\"hbar\"><div class=\"row\"><span>${esc(label)}</span><span>${esc(value)}</span></div>" +
            "<div class=\"track\"><div class=\"fill\" style=\"--v:${frac(fraction)}\"></div></div></div>",
    )

    fun vbars(bars: List<Pair<String, Float>>) = raw(
        "<div class=\"vbars\">" + bars.joinToString("") { (label, f) ->
            "<div><div class=\"bar\" style=\"--v:${frac(f)}\"></div><span class=\"small\">${esc(label)}</span></div>"
        } + "</div>",
    )

    fun ring(fraction: Float, value: String, caption: String) = raw(
        "<div class=\"ringrow\"><svg class=\"ring\" viewBox=\"0 0 100 100\" style=\"--v:${frac(fraction, 0f)}\">" +
            "<circle cx=\"50\" cy=\"50\" r=\"45\" class=\"bg\"/><circle cx=\"50\" cy=\"50\" r=\"45\" class=\"fg\"/></svg>" +
            "<div><div class=\"big\" style=\"font-size:40px\">${esc(value)}</div><div class=\"body\">${esc(caption)}</div></div></div>",
    )
}

private fun slide(from: String, to: String, build: SlideBuilder.() -> Unit) =
    "<section style=\"background:linear-gradient(180deg,$from,$to)\">${SlideBuilder().apply(build).sb}</section>"

private fun frac(f: Float, min: Float = 0.03f) = f.coerceIn(min, 1f).toString()

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val CSS = """
html,body{margin:0;height:100%;background:#101426;color:#fff;font-family:-apple-system,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif;-webkit-tap-highlight-color:transparent}
main{height:100%;overflow-y:scroll;scroll-snap-type:y mandatory}
section{min-height:100vh;min-height:100dvh;scroll-snap-align:start;box-sizing:border-box;padding:max(env(safe-area-inset-top),76px) 28px max(env(safe-area-inset-bottom),48px);display:flex;flex-direction:column;justify-content:center}
.r{opacity:0;transform:translateY(24px);transition:opacity .6s ease,transform .6s ease}
section.on .r{opacity:1;transform:none}
.big{font-weight:800;line-height:1.15;margin:4px 0}
.body{font-size:16px;line-height:1.6;opacity:.92;margin:2px 0}
.small{font-size:13px;opacity:.7;margin:2px 0}
.vbars{display:flex;align-items:flex-end;gap:8px;height:136px;margin:18px 0}
.vbars>div{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;height:100%}
.vbars .bar{width:16px;height:0;border-radius:6px;background:rgba(255,255,255,.85);transition:height .8s ease;margin-bottom:6px}
section.on .vbars .bar{height:calc(var(--v) * 104px)}
.hbar{margin:8px 0}.hbar .row{display:flex;justify-content:space-between;font-size:14px}
.hbar .track{height:10px;border-radius:6px;background:rgba(255,255,255,.15);margin-top:4px;overflow:hidden}
.hbar .fill{height:100%;width:0;border-radius:6px;background:rgba(255,255,255,.85);transition:width .8s ease}
section.on .hbar .fill{width:calc(var(--v) * 100%)}
.ringrow{display:flex;align-items:center;gap:20px;margin:18px 0}
.ring{width:96px;height:96px;transform:rotate(-90deg)}
.ring circle{fill:none;stroke-width:10}.ring .bg{stroke:rgba(255,255,255,.18)}
.ring .fg{stroke:#fff;stroke-linecap:round;stroke-dasharray:283;stroke-dashoffset:283;transition:stroke-dashoffset 1s ease}
section.on .ring .fg{stroke-dashoffset:calc(283 * (1 - var(--v)))}
.fact{display:flex;font-size:15px;margin:6px 0}.fact b{width:84px;flex:none;font-weight:400;opacity:.7}
.poster{border-radius:24px;padding:22px;background:linear-gradient(135deg,#2B2F77,#7A3E9D)}
.tags{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}.tags span{font-size:13px;padding:4px 10px;border-radius:999px;background:rgba(255,255,255,.18)}
"""

private const val JS = """
function countUp(el){var to=+el.dataset.count,pre=el.dataset.pre||'',suf=el.dataset.suf||'',t0=null;
function step(t){if(!t0)t0=t;var p=Math.min(1,(t-t0)/900);el.textContent=pre+Math.round(to*(1-Math.pow(1-p,3)))+suf;if(p<1)requestAnimationFrame(step);}
requestAnimationFrame(step);}
var io=new IntersectionObserver(function(es){es.forEach(function(e){if(!e.isIntersecting)return;var s=e.target;if(s.classList.contains('on'))return;
s.classList.add('on');s.querySelectorAll('[data-count]').forEach(countUp);});},{threshold:0.55});
document.querySelectorAll('section').forEach(function(s){io.observe(s);});
"""
