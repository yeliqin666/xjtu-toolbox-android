package com.xjtu.toolbox.card

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import com.xjtu.toolbox.ui.components.JiaoxiaoxiAvatar
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * 两套纸墨（#473）：
 * - 入口卡片跟着系统深浅色走，暗色下不再是一块刺眼的白纸；
 * - 海报默认仍是浅纸深墨（存成图发出去，在别人手机上也该是同一张），弹窗里可以手动切成暗色那张。
 * 强调色（数字、条形、落款印）取主题色，纸面也略带一点主题色。
 */
@Immutable
private class PosterColors(
    val ink: Color,
    val inkMuted: Color,
    val rule: Color,
    val paper: Color,
    val paperInset: Color,
    val accent: Color,
)

private val PAPER_BASE = Color(0xFFF7F6F3)
private val PAPER_BASE_DARK = Color(0xFF1C1A17)

@Composable
private fun posterColors(dark: Boolean): PosterColors {
    val primary = MiuixTheme.colorScheme.primary
    return remember(dark, primary) {
        if (!dark) {
            // 深色模式下的主题色偏亮，在浅纸上压暗一些保证对比
            val accent = if (primary.luminance() > 0.4f) lerp(primary, Color.Black, 0.3f) else primary
            PosterColors(
                ink = Color(0xFF1F1B16),
                inkMuted = Color(0xFF6E665B),
                rule = Color(0x331F1B16),
                paper = lerp(PAPER_BASE, accent, 0.05f),
                paperInset = lerp(PAPER_BASE, accent, 0.11f),
                accent = accent,
            )
        } else {
            // 反过来：偏暗的主题色在深纸上提亮一些
            val accent = if (primary.luminance() < 0.25f) lerp(primary, Color.White, 0.35f) else primary
            PosterColors(
                ink = Color(0xFFEDE8E0),
                inkMuted = Color(0xFFA39A8D),
                rule = Color(0x33EDE8E0),
                paper = lerp(PAPER_BASE_DARK, accent, 0.06f),
                paperInset = lerp(PAPER_BASE_DARK, accent, 0.13f),
                accent = accent,
            )
        }
    }
}

/** 入口卡片和海报各自在最外层提供，里面的小组件都从这里取色。 */
private val LocalPosterColors = staticCompositionLocalOf<PosterColors> { error("PosterColors not provided") }

private val INK: Color @Composable get() = LocalPosterColors.current.ink
private val INK_MUTED: Color @Composable get() = LocalPosterColors.current.inkMuted
private val RULE: Color @Composable get() = LocalPosterColors.current.rule
private val Accent: Color @Composable get() = LocalPosterColors.current.accent
private val Paper: Color @Composable get() = LocalPosterColors.current.paper
private val PaperInset: Color @Composable get() = LocalPosterColors.current.paperInset

/**
 * 海报「存图」交给宿主：Android 存进缓存再弹系统分享，桌面弹保存对话框写 PNG。
 * 宿主不给（Web）就不显示入口。[label] 是按钮上的字。
 */
class MealReportExport(val label: String, val export: suspend (ImageBitmap) -> Unit)

/** 海报上的日期，`yyyy.MM.dd`。 */
private fun LocalDate.dotted(): String =
    "$year." + month.number.toString().padStart(2, '0') + "." + day.toString().padStart(2, '0')

/** 消费分析页里的入口：一行标题加人设，点开看整张海报。 */
@Composable
internal fun MealReportEntry(report: MealReport, onOpen: () -> Unit) {
    CompositionLocalProvider(LocalPosterColors provides posterColors(LocalIsDarkTheme.current)) {
        MealReportEntryContent(report, onOpen)
    }
}

@Composable
private fun MealReportEntryContent(report: MealReport, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Paper)
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("MEAL REPORT", style = Editorial.kicker, color = Accent)
            Spacer(Modifier.height(4.dp))
            Text("干饭人账单", style = Editorial.headline, color = INK)
            Text(
                "${report.meals} 顿 · ${report.persona} · 生成一张能分享的海报",
                style = MiuixTheme.textStyles.footnote1,
                color = INK_MUTED,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MealBadge(48.dp)
    }
}

/** 全屏看海报，底下一个按钮存成图片分享出去。 */
@Composable
internal fun MealReportDialog(report: MealReport, exporter: MealReportExport, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    // 默认浅色那张（与以前一致），想要暗色的自己切；切了存图也是暗色那张
    var darkPoster by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 底色不透明：半透明会把底下的页面（图表、小岱球）透上来；这一层固定深色，按钮和切换条也跟着用深色配色
        MiuixTheme(colors = top.yukonga.miuix.kmp.theme.darkColorScheme()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0C0D10)).systemBarsPadding()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                MealReportPoster(
                    report,
                    Modifier.drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
                    dark = darkPoster,
                )
            }
            AppSegmentedTabs(
                tabs = listOf("浅色海报", "暗色海报"),
                selectedTabIndex = if (darkPoster) 1 else 0,
                onTabSelected = { darkPoster = it == 1 },
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
                compact = true,
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f), colors = com.xjtu.toolbox.ui.components.appTonalButtonColors()) { Text("关闭") }
                Button(
                    onClick = {
                        scope.launch {
                            exporter.export(layer.toImageBitmap())
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) { Text(exporter.label, color = MiuixTheme.colorScheme.onPrimary) }
            }
        }
        }
    }
}

/**
 * 海报本体：刊头 → 人设大字 → 顿数大数字 → 发丝线分隔的几栏数据 → 落款印章。
 * 版式按竖排杂志页来：左对齐、大留白。
 */
@Composable
fun MealReportPoster(report: MealReport, modifier: Modifier = Modifier, dark: Boolean = false) {
    CompositionLocalProvider(LocalPosterColors provides posterColors(dark)) {
        MealReportPosterContent(report, modifier)
    }
}

@Composable
private fun MealReportPosterContent(report: MealReport, modifier: Modifier) {
    fun money(v: Double) = "¥" + if (v >= 100) money0(v) else money1(v)
    fun clock(tx: Transaction) = tx.time.substringAfter(' ', "").take(5)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = INK_MUTED)
    val accent = Accent

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Paper)
            .padding(horizontal = 26.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("XJTU · MEAL REPORT", style = Editorial.kicker, color = accent, modifier = Modifier.weight(1f))
            Text("${report.from.dotted()} — ${report.to.dotted()}", style = Editorial.kicker.copy(letterSpacing = 0.5.sp), color = INK_MUTED)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(INK))
        Spacer(Modifier.height(26.dp))

        Text("干饭人账单", style = Editorial.kicker.copy(fontSize = 13.sp), color = INK_MUTED)
        Spacer(Modifier.height(6.dp))
        Text(report.persona, style = Editorial.masthead.copy(fontSize = 38.sp, lineHeight = 46.sp), color = INK)
        Spacer(Modifier.height(4.dp))
        Text("「${report.personaLine}」", style = body.copy(color = accent))
        Spacer(Modifier.height(20.dp))

        Text("这段时间的餐饮消费", style = body)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${report.meals}", style = Editorial.figure.copy(fontSize = 64.sp, lineHeight = 70.sp), color = accent)
            Spacer(Modifier.width(6.dp))
            Text("笔", style = Editorial.headline, color = INK, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(
            "共 ${money(report.mealSpend)}，平均每笔 ${money(report.avgMeal)}，" +
                "占全部支出的 ${percent0(report.mealShare * 100)}",
            style = body,
        )
        if (report.noodleBowls > 0) {
            Text("够吃 ${report.noodleBowls} 碗 ${MealReport.NOODLE_PRICE} 元的牛肉面", style = body)
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportMetric("用餐天数", "${report.days}", "天", Modifier.weight(1f))
            ReportMetric("早餐记录", "${report.breakfastDays}", "天", Modifier.weight(1f))
            ReportMetric("夜宵记录", "${report.lateNights}", "笔", Modifier.weight(1f))
        }
        if (report.hourlyCounts.any { it > 0 }) {
            Spacer(Modifier.height(26.dp))
            Text("你的干饭生物钟", style = Editorial.headline.copy(fontSize = 19.sp), color = INK)
            Spacer(Modifier.height(4.dp))
            Text(
                report.peak?.let { (hour, quip) -> "高峰在 $hour 点：$quip" } ?: "各时段餐饮消费笔数",
                style = body.copy(fontSize = 11.sp),
            )
            Spacer(Modifier.height(12.dp))
            MealRhythm(report.hourlyCounts, accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                    Text(it, style = body.copy(fontSize = 9.sp))
                }
            }
        }
        if (report.favoriteMerchants.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text("常去的三家", style = Editorial.headline.copy(fontSize = 19.sp), color = INK)
            Spacer(Modifier.height(10.dp))
            report.favoriteMerchants.forEachIndexed { index, (name, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("0${index + 1}", style = Editorial.figure.copy(fontSize = 22.sp), color = accent, modifier = Modifier.width(38.dp))
                    Text(name, modifier = Modifier.weight(1f), style = body.copy(color = INK), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$count 次", style = body.copy(color = accent), modifier = Modifier.padding(start = 8.dp))
                }
                val ratio = count.toFloat() / report.favoriteMerchants.first().second.coerceAtLeast(1)
                Box(Modifier.fillMaxWidth().height(3.dp).background(RULE)) {
                    Box(Modifier.fillMaxWidth(ratio).height(3.dp).background(accent))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${report.favoriteMerchants.first().first}应该给你发张会员卡", style = body.copy(fontSize = 11.sp))
        }

        Spacer(Modifier.height(24.dp))
        report.canteen?.let { (name, n) -> PosterItem("最常去", name, "去了 $n 次") }
        report.stall?.let { (name, n) -> PosterItem("最爱的档口", name, "点了 $n 回") }
        report.earliest?.let { PosterItem("最早消费", clock(it), "${it.time.take(10)} · ${it.displayMerchant}") }
        report.latest?.let {
            // 凌晨才吃的那一顿配一句
            val lateNote = if ((MealReport.clockOf(it) ?: 0) >= 24 * 60) " · 那晚发生了什么？" else ""
            PosterItem("最晚消费", clock(it), "${it.time.take(10)} · ${it.displayMerchant}$lateNote")
        }
        if (report.lateNights > 0 || report.drinks > 0) {
            PosterItem("夜宵与饮品", "${report.lateNights} 笔 · ${report.drinks} 笔", "22 点后消费 · 咖啡奶茶等饮品")
        }
        report.priciest?.let { PosterItem("最高单笔", money(-it.amount), it.displayMerchant) }

        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("岱宗盒子", style = Editorial.headline.copy(fontSize = 16.sp), color = INK)
                Text("本地统计，不上传", style = body.copy(fontSize = 11.sp))
            }
            MealBadge(60.dp)
        }
    }
}

@Composable
private fun ReportMetric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(PaperInset).padding(10.dp)) {
        Text(label, style = TextStyle(fontSize = 10.sp, color = INK_MUTED))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Editorial.figure.copy(fontSize = 24.sp), color = INK)
            Text(unit, style = TextStyle(fontSize = 10.sp, color = INK_MUTED), modifier = Modifier.padding(start = 3.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun MealRhythm(counts: List<Int>, accent: Color) {
    Canvas(Modifier.fillMaxWidth().height(82.dp)) {
        val peak = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val step = size.width / 24
        for (hour in 0..23) {
            val value = counts.getOrElse(hour) { 0 }
            val height = (size.height * value / peak).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent)),
                topLeft = Offset(hour * step + step * 0.12f, size.height - height),
                size = Size(step * 0.76f, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                alpha = if (value == 0) 0.12f else 1f,
            )
        }
    }
}

/** 一栏：小标签、大号的值、一行说明，上面一道发丝线。 */
@Composable
private fun PosterItem(label: String, value: String, note: String) {
    Box(Modifier.fillMaxWidth().height(0.8.dp).background(RULE))
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(fontSize = 12.sp, color = INK_MUTED, fontWeight = FontWeight.Medium), modifier = Modifier.width(78.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = Editorial.figure.copy(fontSize = 19.sp), color = INK, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, color = INK_MUTED), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 落款：App 形象「交小西」从一碗米饭里探出头，碗是主题色，后面插一双筷子。 */
@Composable
private fun MealBadge(size: Dp = 52.dp) {
    val bowl = Accent
    val chopsticks = INK.copy(alpha = 0.75f)
    val rim = Paper
    Box(Modifier.size(size)) {
        // 筷子和米饭在头像后面
        Canvas(Modifier.matchParentSize()) {
            val s = this.size.width
            val stroke = s * 0.045f
            drawLine(chopsticks, Offset(s * 0.70f, s * 0.04f), Offset(s * 0.56f, s * 0.62f), stroke, StrokeCap.Round)
            drawLine(chopsticks, Offset(s * 0.86f, s * 0.10f), Offset(s * 0.62f, s * 0.64f), stroke, StrokeCap.Round)
            drawOval(RICE, Offset(s * 0.10f, s * 0.44f), Size(s * 0.80f, s * 0.26f))
        }
        JiaoxiaoxiAvatar(Modifier.size(size * 0.62f).align(Alignment.TopCenter).offset(y = size * 0.04f))
        // 碗挡住头像下半截，像从饭里探出来
        Canvas(Modifier.matchParentSize()) {
            val s = this.size.width
            val body = Path().apply {
                moveTo(s * 0.04f, s * 0.58f)
                lineTo(s * 0.96f, s * 0.58f)
                cubicTo(s * 0.94f, s * 0.82f, s * 0.74f, s * 0.92f, s * 0.5f, s * 0.92f)
                cubicTo(s * 0.26f, s * 0.92f, s * 0.06f, s * 0.82f, s * 0.04f, s * 0.58f)
                close()
            }
            drawPath(body, bowl)
            drawRoundRect(bowl, Offset(s * 0.34f, s * 0.88f), Size(s * 0.32f, s * 0.10f), CornerRadius(s * 0.03f))
            drawLine(rim.copy(alpha = 0.55f), Offset(s * 0.14f, s * 0.64f), Offset(s * 0.86f, s * 0.64f), s * 0.025f, StrokeCap.Round)
        }
    }
}

private val RICE = Color(0xFFFFFCF4)
