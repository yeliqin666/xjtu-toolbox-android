package com.xjtu.toolbox.card

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.ImageBitmap
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
 * 海报用固定的纸色和墨色，不跟深浅色走：存成图发出去，在别人手机上也该是同一张。
 * 一个朱红做强调，落款盖一方「岱」字印。
 */
private val PAPER = Color(0xFFF6F1E7)
private val INK = Color(0xFF1F1B16)
private val INK_MUTED = Color(0xFF6E665B)
private val VERMILION = Color(0xFFB23A2B)
private val RULE = Color(0x331F1B16)

/**
 * 海报「存图」交给宿主：Android 存进缓存再弹系统分享，桌面弹保存对话框写 PNG。
 * 宿主不给（Web）就不显示入口。[label] 是按钮上的字。
 */
class MealReportExport(val label: String, val export: suspend (ImageBitmap) -> Unit)

/** 海报上的日期，`yyyy.MM.dd`。 */
private fun LocalDate.dotted(): String =
    "$year." + month.number.toString().padStart(2, '0') + "." + day.toString().padStart(2, '0')

/** 消费分析页里的入口：一行宋体标题加人设，点开看整张海报。 */
@Composable
internal fun MealReportEntry(report: MealReport, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PAPER)
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("MEAL REPORT", style = Editorial.kicker, color = VERMILION)
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
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
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
 * 版式按竖排杂志页来：左对齐、大留白，宋体只用在标题和数字上。
 */
@Composable
private fun MealReportPoster(report: MealReport, modifier: Modifier = Modifier) {
    fun money(v: Double) = "¥" + if (v >= 100) money0(v) else money1(v)
    fun clock(tx: Transaction) = tx.time.substringAfter(' ', "").take(5)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = INK_MUTED)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(PAPER)
            .padding(horizontal = 26.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("XJTU · MEAL REPORT", style = Editorial.kicker, color = VERMILION, modifier = Modifier.weight(1f))
            Text("${report.from.dotted()} — ${report.to.dotted()}", style = Editorial.kicker.copy(letterSpacing = 0.5.sp), color = INK_MUTED)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(INK))
        Spacer(Modifier.height(26.dp))

        Text("干饭人账单", style = Editorial.kicker.copy(fontSize = 13.sp), color = INK_MUTED)
        Spacer(Modifier.height(6.dp))
        Text(report.persona, style = Editorial.masthead.copy(fontSize = 38.sp, lineHeight = 46.sp), color = INK)
        Spacer(Modifier.height(22.dp))

        Text("这段时间，你在学校吃了", style = body)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${report.meals}", style = Editorial.figure.copy(fontSize = 64.sp, lineHeight = 70.sp), color = VERMILION)
            Spacer(Modifier.width(6.dp))
            Text("顿", style = Editorial.headline, color = INK, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(
            "花了 ${money(report.mealSpend)}，平均一顿 ${money(report.avgMeal)}，" +
                "占全部支出的 ${percent0(report.mealShare * 100)}",
            style = body,
        )

        Spacer(Modifier.height(24.dp))
        report.canteen?.let { (name, n) -> PosterItem("最常去", name, "去了 $n 次") }
        report.stall?.let { (name, n) -> PosterItem("最爱的档口", name, "点了 $n 回") }
        report.earliest?.let { PosterItem("最早的一顿", clock(it), "${it.time.take(10)} · ${it.displayMerchant}") }
        report.latest?.let { PosterItem("最晚的一顿", clock(it), "${it.time.take(10)} · ${it.displayMerchant}") }
        PosterItem("吃早饭", "${report.breakfastDays} 天", "有饭吃的 ${report.days} 天里")
        if (report.lateNights > 0 || report.drinks > 0) {
            PosterItem("夜宵与续命", "${report.lateNights} 次 · ${report.drinks} 杯", "21 点后的夜宵 · 咖啡奶茶")
        }
        report.priciest?.let { PosterItem("最豪的一顿", money(-it.amount), it.displayMerchant) }

        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("岱宗盒子", style = Editorial.headline.copy(fontSize = 16.sp), color = INK)
                Text("数据只在手机上算，不上传", style = body.copy(fontSize = 11.sp))
            }
            Seal()
        }
    }
}

/** 一栏：小标签、宋体的值、一行说明，上面一道发丝线。 */
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

/** 落款的方印：朱红底、白色宋体「岱」。 */
@Composable
private fun Seal() {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(4.dp)).background(VERMILION),
        contentAlignment = Alignment.Center,
    ) {
        Text("岱", style = Editorial.headline.copy(fontSize = 22.sp, lineHeight = 24.sp), color = PAPER)
    }
}
