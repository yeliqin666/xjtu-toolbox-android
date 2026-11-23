package com.xjtu.toolbox.termreport

/**
 * 把学期总结排成一份自包含的 HTML（年终总结那样的整屏竖滑页）。
 * 不引外部资源，存下来以后用任何 WebView 打开都一样。
 */
fun TermReport.toHtml(): String {
    val slides = buildList {
        add(slide("#1B1F4B", "#3A2F7A") {
            small(termName)
            small("这学期一共")
            count(days.toLong(), 72, suf = " 天")
            body("$start — $end")
            body("学期结束啦，一起看看这段时间")
            small("上滑继续 ↑")
        })
        courses?.let { c ->
            add(slide("#0F3D5E", "#1F7A8C") {
                small("这学期你上了")
                count(c.courses.toLong(), 56, suf = " 门课")
                body("一共 ${c.totalSections} 节，平均每周 ${c.weeklyAvg} 节")
                val max = (c.perWeekday.maxOrNull() ?: 0).coerceAtLeast(1)
                vbars(c.perWeekday.mapIndexed { i, n -> weekdayName(i).drop(1) to n.toFloat() / max })
                if (c.earlyClasses > 0) body("其中早八 ${c.earlyClasses} 次，每一次起床都算数")
                if (c.customEvents > 0) body("你还自己排了 ${c.customEvents} 条日程")
            })
        }
        grades?.let { g ->
            add(slide("#3B1E54", "#9B3675") {
                small("这学期的 GPA")
                big("%.2f".format(g.gpa), 72)
                g.previousGpa?.let { p ->
                    val d = g.gpa - p
                    body(when {
                        d > 0.005 -> "比上学期高了 %.2f，进步看得见".format(d)
                        d < -0.005 -> "比上学期低了 %.2f，下学期再找回来".format(-d)
                        else -> "和上学期持平，稳"
                    })
                }
                body("${g.count} 门课 · %.1f 学分 · 均分 %.1f".format(g.credits, g.average))
                val max = g.bands.maxOf { it.second }.coerceAtLeast(1)
                g.bands.filter { it.second > 0 }.forEach { (label, n) -> hbar(label, "$n 门", n.toFloat() / max) }
                g.best?.let { body("最高分：${it.courseName} ${it.score}") }
                if (g.failed > 0) body("有 ${g.failed} 门没过，补考和重修都还来得及")
            })
        }
        todos?.let { t ->
            add(slide("#113D2E", "#2E8B57") {
                small("你给自己定了")
                count(t.created.toLong(), 56, suf = " 件事")
                ring(t.rate, "${(t.rate * 100).toInt()}%", "完成了 ${t.done} 件")
                body(when {
                    t.rate >= 0.85f -> "说到做到，执行力拉满"
                    t.rate >= 0.5f -> "大半都搞定了，不赖"
                    else -> "记下来就是开始，下学期挑几件最要紧的先做"
                })
                if (t.overdue > 0) small("还有 ${t.overdue} 件过了截止没勾掉，可能早做完只是忘了点")
            })
        }
        card?.let { c ->
            add(slide("#4A2511", "#C0632B") {
                small("校园卡一共花了")
                count(c.spend.toLong(), 60, pre = "¥")
                if (c.from > start) small("本机流水从 ${c.from} 起，之前的没算进来")
                val maxMonth = c.monthly.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                vbars(c.monthly.map { (m, v) -> "${m.takeLast(2).trimStart('0')}月" to (v / maxMonth).toFloat() })
                val maxCat = c.categories.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                c.categories.forEach { (name, v) -> hbar(name, "¥%.0f".format(v), (v / maxCat).toFloat()) }
                c.priciest?.let { body("单笔最大：¥%.0f · ${it.displayMerchant}".format(-it.amount)) }
            })
            c.meal?.let { m ->
                add(slide("#5A1E1E", "#D9534F") {
                    small("这学期你在学校吃了")
                    count(m.meals.toLong(), 64, suf = " 顿")
                    body("平均一顿 ¥%.1f，吃饭占了支出的 %d%%".format(m.avgMeal, (m.mealShare * 100).toInt()))
                    m.canteen?.let { fact("最常去", "${it.first} · ${it.second} 次") }
                    m.stall?.let { fact("最爱档口", "${it.first} · ${it.second} 次") }
                    m.earliest?.let { fact("最早一顿", "${it.time.drop(11).take(5)} ${it.displayMerchant}") }
                    m.latest?.let { fact("最晚一顿", "${it.time.drop(11).take(5)} ${it.displayMerchant}") }
                    if (m.breakfastDays > 0) fact("吃了早饭", "${m.breakfastDays} 天")
                    if (m.lateNights > 0) fact("夜宵", "${m.lateNights} 次")
                })
            }
        }
        add(slide("#1E2A5A", "#4F6BD9") {
            small("课余时间")
            val g = games
            if (g != null) {
                body("小游戏一共玩了")
                count(g.minutes, 52, suf = " 分钟")
                body("分布在 ${g.days} 天里" + (g.favorite?.let { "，最爱「$it」" } ?: ""))
                g.longestDay?.let { (d, s) -> small("玩得最久的一天是 $d，${s / 60} 分钟") }
            } else {
                body("小游戏时长从 6.0.3 开始记录，下学期就能看到")
            }
            body("体测")
            big(fitness ?: "这次没读到", if (fitness != null) 32 else 20)
        })
        agent?.let { a ->
            add(slide("#233142", "#4FA3A5") {
                small("你和小岱聊了")
                count(a.messages, 60, suf = " 句")
                body("${a.chats} 个对话，${a.days} 天里来找过它")
                if (a.topTools.isNotEmpty()) {
                    small("最常让它帮忙")
                    val max = a.topTools.first().second.coerceAtLeast(1)
                    a.topTools.forEach { (name, n) -> hbar(name, "$n 次", n.toFloat() / max) }
                }
                a.peakHour?.let { body("最常在 $it 点找它" + if (it >= 23 || it < 4) "，早点睡呀" else "") }
                if (a.lateNight > 0) small("深夜还聊了 ${a.lateNight} 句")
            })
        }
        add(slide("#16122E", "#4B2A7B") {
            raw(buildString {
                append("<div class=\"poster\"><div class=\"small\">${esc(termName)}</div><div class=\"small\">这学期的你是</div>")
                append("<div class=\"big\" style=\"font-size:40px\">「${esc(title())}」</div>")
                highlights().forEach { append("<div class=\"body\">· ${esc(it)}</div>") }
                append("<div class=\"small\" style=\"margin-top:12px\">岱宗盒子 · 学期总结</div></div>")
            })
            if (missing.isNotEmpty()) small("生成时本机没有${missing.joinToString("、") { it.first }}的数据，这几页没出现")
        })
    }
    return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${esc(termName)} · 学期总结</title><style>$CSS</style></head>
<body><main>${slides.joinToString("")}</main><script>$JS</script></body></html>"""
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
"""

private const val JS = """
function countUp(el){var to=+el.dataset.count,pre=el.dataset.pre||'',suf=el.dataset.suf||'',t0=null;
function step(t){if(!t0)t0=t;var p=Math.min(1,(t-t0)/900);el.textContent=pre+Math.round(to*(1-Math.pow(1-p,3)))+suf;if(p<1)requestAnimationFrame(step);}
requestAnimationFrame(step);}
var io=new IntersectionObserver(function(es){es.forEach(function(e){if(!e.isIntersecting)return;var s=e.target;if(s.classList.contains('on'))return;
s.classList.add('on');s.querySelectorAll('[data-count]').forEach(countUp);});},{threshold:0.55});
document.querySelectorAll('section').forEach(function(s){io.observe(s);});
"""
