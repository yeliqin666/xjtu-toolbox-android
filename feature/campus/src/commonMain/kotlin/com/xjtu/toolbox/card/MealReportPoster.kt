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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
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
import com.xjtu.toolbox.ui.theme.Editorial
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * 海报始终是浅纸深墨，不跟深浅色走：存成图发出去，在别人手机上也该是同一张。
 * 强调色（数字、条形、落款印）取主题色，纸面也略带一点主题色。
 */
private val INK = Color(0xFF1F1B16)
private val INK_MUTED = Color(0xFF6E665B)
private val RULE = Color(0x331F1B16)
private val PAPER_BASE = Color(0xFFF7F6F3)

/** 主题色；深色模式下的主题色偏亮，在浅纸上压暗一些保证对比。 */
private val Accent: Color
    @Composable get() = MiuixTheme.colorScheme.primary.let { if (it.luminance() > 0.4f) lerp(it, Color.Black, 0.3f) else it }
private val Paper: Color @Composable get() = lerp(PAPER_BASE, Accent, 0.05f)
private val PaperInset: Color @Composable get() = lerp(PAPER_BASE, Accent, 0.11f)

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
        Seal()
    }
}

/** 全屏看海报，底下一个按钮存成图片分享出去。 */
@Composable
internal fun MealReportDialog(report: MealReport, exporter: MealReportExport, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).systemBarsPadding()) {
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
                )
            }
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

/**
 * 海报本体：刊头 → 人设大字 → 顿数大数字 → 发丝线分隔的几栏数据 → 落款印章。
 * 版式按竖排杂志页来：左对齐、大留白。
 */
@Composable
fun MealReportPoster(report: MealReport, modifier: Modifier = Modifier) {
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
        Spacer(Modifier.height(22.dp))

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
            Text("各时段餐饮消费笔数", style = body.copy(fontSize = 11.sp))
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
        }

        Spacer(Modifier.height(24.dp))
        report.canteen?.let { (name, n) -> PosterItem("最常去", name, "去了 $n 次") }
        report.stall?.let { (name, n) -> PosterItem("最爱的档口", name, "点了 $n 回") }
        report.earliest?.let { PosterItem("最早消费", clock(it), "${it.time.take(10)} · ${it.displayMerchant}") }
        report.latest?.let { PosterItem("最晚消费", clock(it), "${it.time.take(10)} · ${it.displayMerchant}") }
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
            Seal()
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

/** 落款的方印：主题色底、纸色「岱」。 */
@Composable
private fun Seal() {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(4.dp)).background(Accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("岱", style = Editorial.headline.copy(fontSize = 22.sp, lineHeight = 24.sp), color = Paper)
    }
}
