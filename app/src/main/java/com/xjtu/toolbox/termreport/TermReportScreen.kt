package com.xjtu.toolbox.termreport

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.agent.AgentForward
import com.xjtu.toolbox.card.shareImage
import com.xjtu.toolbox.error.FriendlyError
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 学期总结（#282）：年终总结那样的全屏竖滑页。先确认再生成，免得一进来就被数据吓到；
 * 读不到的那块直接不出那一页。
 */
@Composable
fun TermReportScreen(onBack: () -> Unit, onAskAgent: (AgentForward) -> Unit) {
    val context = LocalContext.current
    var started by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<TermReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(started) {
        if (!started || report != null) return@LaunchedEffect
        error = null
        runCatching { loadTermReport(context) }
            .onSuccess { report = it }
            .onFailure { error = FriendlyError.of(it, "生成学期总结"); started = false }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF101426))) {
        val r = report
        when {
            r != null -> ReportPager(r, onAskAgent)
            started -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Intro(error) { started = true }
        }
        Box(
            Modifier.statusBarsPadding().padding(12.dp).size(40.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White) }
    }
}

@Composable
private fun Intro(error: String?, onStart: () -> Unit) {
    Slide(PALETTES[0]) {
        Small("学期总结")
        Spacer(Modifier.height(12.dp))
        Big("这学期，\n过得怎么样？", size = 44)
        Spacer(Modifier.height(20.dp))
        Body("用这台手机上已有的课表、成绩、待办、校园卡、体测，以及小游戏和小岱的使用记录拼一份回顾。只在本机计算，不会上传。")
        error?.let {
            Spacer(Modifier.height(12.dp))
            Body(it, color = Color(0xFFFFB4A9))
        }
        Spacer(Modifier.height(32.dp))
        Pill("开始回顾", onClick = onStart)
        Spacer(Modifier.height(12.dp))
        Small("没准备好也没关系，想看的时候再来")
    }
}

private enum class Slides { COVER, COURSES, GRADES, TODOS, CARD, MEAL, PLAY, AGENT, END }

/** 当前页是否在屏幕上：进场动画只在翻到这页时播。 */
private val LocalSlideVisible = compositionLocalOf { true }

@Composable
private fun ReportPager(r: TermReport, onAskAgent: (AgentForward) -> Unit) {
    val slides = remember(r) {
        Slides.entries.filter {
            when (it) {
                Slides.COURSES -> r.courses != null
                Slides.GRADES -> r.grades != null
                Slides.TODOS -> r.todos != null
                Slides.CARD -> r.card != null
                Slides.MEAL -> r.card?.meal != null
                Slides.AGENT -> r.agent != null
                else -> true
            }
        }
    }
    val pager = rememberPagerState(pageCount = { slides.size })
    Box(Modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            CompositionLocalProvider(LocalSlideVisible provides (pager.settledPage == page)) {
                when (slides[page]) {
                    Slides.COVER -> CoverSlide(r)
                    Slides.COURSES -> CourseSlide(r.courses!!)
                    Slides.GRADES -> GradeSlide(r.grades!!)
                    Slides.TODOS -> TodoSlide(r.todos!!)
                    Slides.CARD -> CardSlide(r.card!!, r)
                    Slides.MEAL -> MealSlide(r.card!!.meal!!)
                    Slides.PLAY -> PlaySlide(r)
                    Slides.AGENT -> AgentSlide(r.agent!!)
                    Slides.END -> EndingSlide(r, onAskAgent)
                }
            }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(slides.size) { i ->
                Box(
                    Modifier.width(4.dp).height(if (i == pager.currentPage) 18.dp else 6.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.9f else 0.35f)),
                )
            }
        }
    }
}

// ── 各页 ─────────────────────────────────────────────────────────────

@Composable
private fun CoverSlide(r: TermReport) = Slide(PALETTES[0]) {
    Small(r.termName)
    Spacer(Modifier.height(16.dp))
    Small("这学期已经过去")
    CountUp(r.days, suffix = " 天", size = 72)
    Spacer(Modifier.height(12.dp))
    Body("${r.start} — ${r.until}")
    Body(if (r.ended) "学期结束啦，一起看看这段时间" else "学期还在进行，这是到今天为止的样子")
    Spacer(Modifier.height(40.dp))
    Small("上滑继续 ↑")
}

@Composable
private fun CourseSlide(c: CourseStats) = Slide(PALETTES[1]) {
    Small("这学期你上了")
    CountUp(c.courses, suffix = " 门课", size = 56)
    Body("一共 ${c.totalSections} 节，平均每周 ${c.weeklyAvg} 节")
    Spacer(Modifier.height(24.dp))
    val max = c.perWeekday.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        c.perWeekday.forEachIndexed { i, n ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                GrowBar(n.toFloat() / max, vertical = true, order = i)
                Spacer(Modifier.height(6.dp))
                Small(weekdayName(i).drop(1))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    if (c.earlyClasses > 0) Body("其中早八 ${c.earlyClasses} 次，每一次起床都算数")
    if (c.customEvents > 0) Body("你还自己排了 ${c.customEvents} 条日程")
}

@Composable
private fun GradeSlide(g: GradeStats) = Slide(PALETTES[2]) {
    Small("这学期的 GPA")
    Reveal(0) { Big("%.2f".format(g.gpa), size = 72) }
    g.previousGpa?.let { p ->
        val d = g.gpa - p
        Body(when {
            d > 0.005 -> "比上学期高了 %.2f，进步看得见".format(d)
            d < -0.005 -> "比上学期低了 %.2f，下学期再找回来".format(-d)
            else -> "和上学期持平，稳"
        })
    }
    Body("${g.count} 门课 · %.1f 学分 · 均分 %.1f".format(g.credits, g.average))
    Spacer(Modifier.height(20.dp))
    val max = g.bands.maxOf { it.second }.coerceAtLeast(1)
    g.bands.filter { it.second > 0 }.forEachIndexed { i, (label, n) -> LabeledBar(label, "$n 门", n.toFloat() / max, i) }
    Spacer(Modifier.height(16.dp))
    g.best?.let { Body("最高分：${it.courseName} ${it.score}") }
    if (g.failed > 0) Body("有 ${g.failed} 门没过，补考和重修都还来得及")
}

@Composable
private fun TodoSlide(t: TodoStats) = Slide(PALETTES[3]) {
    Small("你给自己定了")
    CountUp(t.created, suffix = " 件事", size = 56)
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Ring(t.rate)
        Spacer(Modifier.width(20.dp))
        Column {
            Big("${(t.rate * 100).toInt()}%", size = 40)
            Body("完成了 ${t.done} 件")
        }
    }
    Spacer(Modifier.height(16.dp))
    Body(when {
        t.rate >= 0.85f -> "说到做到，执行力拉满"
        t.rate >= 0.5f -> "大半都搞定了，不赖"
        else -> "记下来就是开始，下学期挑几件最要紧的先做"
    })
    if (t.overdue > 0) Small("还有 ${t.overdue} 件过了截止没勾掉，可能早做完只是忘了点")
}

@Composable
private fun CardSlide(c: CardStats, r: TermReport) = Slide(PALETTES[4]) {
    Small("校园卡一共花了")
    CountUp(c.spend.toInt(), prefix = "¥", size = 60)
    if (c.from > r.start) Small("本机流水从 ${c.from} 起，之前的没算进来")
    Spacer(Modifier.height(20.dp))
    val maxMonth = c.monthly.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        c.monthly.forEachIndexed { i, (m, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                GrowBar((v / maxMonth).toFloat(), vertical = true, order = i)
                Spacer(Modifier.height(6.dp))
                Small("${m.takeLast(2).trimStart('0')}月")
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    val maxCat = c.categories.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    c.categories.forEachIndexed { i, (name, v) -> LabeledBar(name, "¥%.0f".format(v), (v / maxCat).toFloat(), i) }
    c.priciest?.let { Body("单笔最大：¥%.0f · ${it.displayMerchant}".format(-it.amount)) }
}

@Composable
private fun MealSlide(m: com.xjtu.toolbox.card.MealReport) = Slide(PALETTES[5]) {
    Small("这学期你在学校吃了")
    CountUp(m.meals, suffix = " 顿", size = 64)
    Body("平均一顿 ¥%.1f，吃饭占了支出的 %d%%".format(m.avgMeal, (m.mealShare * 100).toInt()))
    Spacer(Modifier.height(20.dp))
    m.canteen?.let { Fact("最常去", "${it.first} · ${it.second} 次") }
    m.stall?.let { Fact("最爱档口", "${it.first} · ${it.second} 次") }
    m.earliest?.let { Fact("最早一顿", "${it.time.drop(11).take(5)} ${it.displayMerchant}") }
    m.latest?.let { Fact("最晚一顿", "${it.time.drop(11).take(5)} ${it.displayMerchant}") }
    if (m.breakfastDays > 0) Fact("吃了早饭", "${m.breakfastDays} 天")
    if (m.lateNights > 0) Fact("夜宵", "${m.lateNights} 次")
}

@Composable
private fun PlaySlide(r: TermReport) = Slide(PALETTES[6]) {
    Small("课余时间")
    Spacer(Modifier.height(12.dp))
    val g = r.games
    if (g != null) {
        Body("小游戏一共玩了")
        CountUp(g.minutes.toInt(), suffix = " 分钟", size = 52)
        Body("分布在 ${g.days} 天里" + (g.favorite?.let { "，最爱「$it」" } ?: ""))
        g.longestDay?.let { (d, s) -> Small("玩得最久的一天是 $d，${s / 60} 分钟") }
    } else {
        Body("小游戏时长从这一版开始记录，下学期就能看到")
    }
    Spacer(Modifier.height(28.dp))
    Body("体测")
    Big(r.fitness ?: "去体测页刷新一次就能显示", size = if (r.fitness != null) 32 else 18)
}

@Composable
private fun AgentSlide(a: AgentStats) = Slide(PALETTES[7]) {
    Small("你和小岱聊了")
    CountUp(a.messages.toInt(), suffix = " 句", size = 60)
    Body("${a.chats} 个对话，${a.days} 天里来找过它")
    Spacer(Modifier.height(20.dp))
    if (a.topTools.isNotEmpty()) {
        Small("最常让它帮忙")
        val max = a.topTools.first().second.coerceAtLeast(1)
        a.topTools.forEachIndexed { i, (name, n) -> LabeledBar(name, "$n 次", n.toFloat() / max, i) }
    }
    Spacer(Modifier.height(12.dp))
    a.peakHour?.let { Body("最常在 $it 点找它" + if (it >= 23 || it < 4) "，早点睡呀" else "") }
    if (a.lateNight > 0) Small("深夜还聊了 ${a.lateNight} 句")
}

@Composable
private fun EndingSlide(r: TermReport, onAskAgent: (AgentForward) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    Slide(PALETTES[8]) {
        Column(
            Modifier.fillMaxWidth()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2B2F77), Color(0xFF7A3E9D))))
                .padding(20.dp),
        ) {
            Small(r.termName)
            Spacer(Modifier.height(8.dp))
            Small("这学期的你是")
            Reveal(0) { Big("「${r.title()}」", size = 40) }
            Spacer(Modifier.height(16.dp))
            r.highlights().forEachIndexed { i, h -> Reveal(i + 1) { Body("· $h") } }
            Spacer(Modifier.height(12.dp))
            Small("岱宗盒子 · 学期总结")
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("存图分享") { scope.launch { shareImage(context, layer.toImageBitmap(), "term-report.png", "分享学期总结") } }
            Pill("让小岱聊聊这学期", filled = false) {
                onAskAgent(AgentForward(text = "帮我回顾一下这学期", snapshot = r.agentSnapshot(), label = "学期总结"))
            }
        }
        if (r.missing.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Small("${r.missing.joinToString("、")}没读到：打开对应页面刷新一次，再来就有了")
        }
    }
}

// ── 积木 ─────────────────────────────────────────────────────────────

private val PALETTES = listOf(
    listOf(Color(0xFF1B1F4B), Color(0xFF3A2F7A)),
    listOf(Color(0xFF0F3D5E), Color(0xFF1F7A8C)),
    listOf(Color(0xFF3B1E54), Color(0xFF9B3675)),
    listOf(Color(0xFF113D2E), Color(0xFF2E8B57)),
    listOf(Color(0xFF4A2511), Color(0xFFC0632B)),
    listOf(Color(0xFF5A1E1E), Color(0xFFD9534F)),
    listOf(Color(0xFF1E2A5A), Color(0xFF4F6BD9)),
    listOf(Color(0xFF233142), Color(0xFF4FA3A5)),
    listOf(Color(0xFF16122E), Color(0xFF4B2A7B)),
)

@Composable
private fun Slide(colors: List<Color>, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 72.dp),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/** 第 [order] 个元素稍晚一点淡入上移。 */
@Composable
private fun Reveal(order: Int, content: @Composable () -> Unit) {
    val shown = LocalSlideVisible.current
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(500, delayMillis = if (shown) 120 * order else 0), label = "reveal")
    Box(Modifier.graphicsLayer { alpha = p; translationY = (1 - p) * 40f }) { content() }
}

@Composable
private fun CountUp(value: Int, prefix: String = "", suffix: String = "", size: Int) {
    val shown = LocalSlideVisible.current
    val n by animateIntAsState(if (shown) value else 0, tween(900), label = "count")
    Big("$prefix$n$suffix", size = size)
}

@Composable
private fun GrowBar(fraction: Float, vertical: Boolean, order: Int) {
    val shown = LocalSlideVisible.current
    val p by animateFloatAsState(if (shown) fraction.coerceIn(0.03f, 1f) else 0f, tween(700, delayMillis = 60 * order), label = "bar")
    val shape = RoundedCornerShape(6.dp)
    if (vertical) {
        Box(Modifier.width(18.dp).height(100.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(p).clip(shape).background(Color.White.copy(alpha = 0.85f)))
        }
    } else {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(shape).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(shape).background(Color.White.copy(alpha = 0.85f)))
        }
    }
}

@Composable
private fun LabeledBar(label: String, value: String, fraction: Float, order: Int) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        GrowBar(fraction, vertical = false, order = order)
    }
}

@Composable
private fun Ring(fraction: Float) {
    val shown = LocalSlideVisible.current
    val p by animateFloatAsState(if (shown) fraction else 0f, tween(1000), label = "ring")
    Canvas(Modifier.size(96.dp)) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(Color.White, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun Big(text: String, size: Int) =
    Text(text, color = Color.White, fontSize = size.sp, lineHeight = (size * 1.15f).sp, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String, color: Color = Color.White.copy(alpha = 0.92f)) =
    Text(text, color = color, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Small(text: String) = Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)

@Composable
private fun Pill(text: String, filled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (filled) Color(0xFF1B1F4B) else Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
