package com.xjtu.toolbox.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.ui.theme.Editorial
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 窄卡按完整文本的测量高度选条目，机构名、号码和帖子不再逐行截断。 */
@Composable
internal fun FittedLines(
    lines: List<HomeLine>, ink: Color, onLink: ((String) -> Unit)?, actions: Boolean = false,
    timeline: Boolean = false, wrap: Boolean = false, modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp)
    val detailStyle = TextStyle(fontSize = 10.sp, lineHeight = 13.sp)
    val padding = if (actions) 7.dp else 4.dp
    BoxWithConstraints(modifier) {
        val textWidth = with(density) { (maxWidth - (if (actions) 28.dp else if (timeline) 12.dp else 0.dp)).roundToPx().coerceAtLeast(1) }
        val constraints = Constraints(maxWidth = textWidth)
        var remaining = with(density) { maxHeight.toPx() }
        val shown = lines.takeWhile { line ->
            val primary = measurer.measure(AnnotatedString(line.text), style, maxLines = if (wrap) Int.MAX_VALUE else 1, constraints = constraints).size.height
            val secondary = line.detail?.let { measurer.measure(AnnotatedString(it), detailStyle, constraints = constraints).size.height } ?: 0
            val height = primary + secondary + with(density) { (padding * 2 + 2.dp).toPx() }
            (height <= remaining).also { if (it) remaining -= height }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEachIndexed { index, line ->
                val link = line.link
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(if (actions) 9.dp else 4.dp))
                        .then(if (actions) Modifier.background(ink.copy(alpha = 0.075f)) else Modifier)
                        .then(if (link != null && onLink != null) Modifier.clickable { onLink(link) } else Modifier)
                        .padding(horizontal = if (actions) 8.dp else 0.dp, vertical = padding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (timeline) Box(Modifier.padding(end = 7.dp).size(4.dp).background(ink.copy(alpha = if (index == 0) 0.8f else 0.3f), CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(line.text, style = style, color = ink.copy(alpha = 0.9f),
                            maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                        line.detail?.let { Text(it, style = detailStyle, color = ink.copy(alpha = 0.65f)) }
                    }
                    if (actions && link != null && onLink != null) Text("↗", fontSize = 12.sp, color = ink.copy(alpha = 0.6f))
                }
            }
            if (shown.isEmpty() && lines.isNotEmpty()) {
                Text("打开查看完整内容", fontSize = 11.sp, lineHeight = 16.sp, color = ink.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
internal fun GamesWidgetContent(row: HomeServiceRow, size: WidgetSize, ink: Color, onLink: ((String) -> Unit)?) {
    val large = size == WidgetSize.LARGE
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("最近玩过", fontSize = 10.sp, lineHeight = 13.sp, color = ink.copy(alpha = 0.65f))
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (large) 2 else 1
            val density = LocalDensity.current
            val itemHeight = with(density) { 26.dp + 29.sp.toDp() }
            val count = (maxHeight / itemHeight).toInt().coerceIn(0, 2) * columns
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                row.lines.take(count).chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.forEach { game ->
                            val route = game.link?.removePrefix("app:")?.let(::appRouteOf)
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .then(if (game.link != null && onLink != null) Modifier.clickable { onLink(game.link) } else Modifier)
                                    .padding(vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(route?.let(HomeIcons::of) ?: row.icon, null, tint = ink, modifier = Modifier.size(22.dp))
                                Text(game.text, fontSize = 10.5.sp, lineHeight = 14.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                game.detail?.let { Text(it, fontSize = 9.sp, lineHeight = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        if (group.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun FitnessWidgetContent(row: HomeServiceRow, size: WidgetSize, ink: Color, onLink: ((String) -> Unit)?) {
    val large = size == WidgetSize.LARGE
    val number = row.stat?.toFloatOrNull()
    Column(Modifier.fillMaxSize().padding(if (large) 12.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (large) Icon(row.icon, null, tint = ink, modifier = Modifier.size(16.dp))
            Text("体测成绩", fontSize = 10.sp, lineHeight = 13.sp, color = ink.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(ink.copy(alpha = 0.12f), -90f, 360f, false, style = stroke)
                    if (number != null) drawArc(ink.copy(alpha = 0.85f), -90f, number.coerceIn(0f, 100f) * 3.6f, false, style = stroke)
                }
                Text(if (number != null) row.stat!!.removeSuffix(".00") else row.stat.orEmpty(),
                    style = Editorial.figure.copy(fontSize = if (number != null) 18.sp else 10.sp, lineHeight = 22.sp), color = ink, maxLines = 1)
            }
            if (large) Text(row.statDetail.orEmpty(), fontSize = 10.sp, lineHeight = 14.sp, color = ink.copy(alpha = 0.7f), maxLines = 3)
        }
        if (!large) {
            Spacer(Modifier.height(6.dp))
            Text(row.statDetail.orEmpty(), fontSize = 9.sp, lineHeight = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        if (large) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val rowHeight = with(density) { 27.sp.toDp() + 4.dp }
            val count = (maxHeight / rowHeight).toInt().coerceIn(0, 2) * 2
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.lines.take(count).chunked(2).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { item ->
                            Column(Modifier.weight(1f)) {
                                Text(item.detail.orEmpty().substringBefore(" · "), fontSize = 12.sp, lineHeight = 15.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.text, fontSize = 9.sp, lineHeight = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (group.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else FittedLines(row.lines, ink, onLink, wrap = true, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
