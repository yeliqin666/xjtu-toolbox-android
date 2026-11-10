package com.xjtu.toolbox.emptyroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.DaySlotBreaks
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 其它使用（没排课但有人）：琥珀色，和空闲的主色、上课中的错误色都分得开。 */
internal val IN_USE_COLOR = Color(0xFFD9822B)

/** 一栋楼每节的空闲间数。 */
internal class BuildingFree(val name: String, val total: Int, val free: List<Int>)

/** 一排节次格，分段处留宽缝，和 [com.xjtu.toolbox.ui.components.SlotStripe] 对齐。格子自己加 weight。 */
@Composable
private fun PeriodCells(
    count: Int,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    cell: @Composable RowScope.(Int) -> Unit,
) {
    Row(modifier, verticalAlignment = verticalAlignment) {
        for (i in 0 until count) {
            cell(i)
            if (i < count - 1) Spacer(Modifier.width(if (count == 11 && i in DaySlotBreaks) 8.dp else 3.dp))
        }
    }
}

/** 节次滑条上方的柱子：每节有多少间空着，点一根就只看这一节。 */
@Composable
internal fun PeriodFreeBars(
    counts: List<Int>,
    selected: IntRange,
    current: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    PeriodCells(counts.size, modifier.fillMaxWidth().height(50.dp), Alignment.Bottom) { i ->
        val n = counts[i]
        val inRange = i in selected
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).clickable { onPick(i) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                "$n",
                fontSize = 9.sp,
                color = if (inRange) primary else muted,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(3.dp + 30.dp * (n.toFloat() / max))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(primary.copy(alpha = if (inRange) 0.85f else 0.22f)),
            )
        }
    }
}

/** 楼栋 × 节次的空闲热力图，默认收起；点一格只看那一节。 */
@Composable
internal fun BuildingHeatmap(
    buildings: List<BuildingFree>,
    selected: IntRange,
    current: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val periods = buildings.firstOrNull()?.free?.size ?: 0
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("各楼空闲分布", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (open) Text("越深空教室越多", style = MiuixTheme.textStyles.footnote1, color = muted)
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (open) "收起" else "展开",
                tint = muted,
                modifier = Modifier.padding(start = 4.dp).size(18.dp).graphicsLayer { rotationZ = if (open) 180f else 0f },
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Row {
                    Spacer(Modifier.width(HEATMAP_LABEL_WIDTH))
                    PeriodCells(periods, Modifier.weight(1f)) { i ->
                        Text(
                            "${i + 1}",
                            modifier = Modifier.weight(1f),
                            fontSize = 9.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (i in selected) primary else muted,
                            fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                buildings.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            b.name,
                            modifier = Modifier.width(HEATMAP_LABEL_WIDTH).padding(end = 6.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PeriodCells(periods, Modifier.weight(1f).fillMaxHeight()) { i ->
                            val n = b.free[i]
                            val ratio = if (b.total > 0) n.toFloat() / b.total else 0f
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(primary.copy(alpha = 0.06f + 0.84f * ratio))
                                    .clickable { onPick(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$n",
                                    fontSize = 9.sp,
                                    color = if (ratio > 0.5f) Color.White else primary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val HEATMAP_LABEL_WIDTH = 58.dp

/**
 * 实时状态的分段条：空闲 / 其它使用 / 上课中按间数分段，点一段只看这一类，再点回到全部。
 * 平台没给状态的算进「全部」，画成灰段、不能点。
 */
@Composable
internal fun LiveStatusBar(
    free: Int,
    inUse: Int,
    inClass: Int,
    total: Int,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val segments = listOf(
        Triple("空闲", free, MiuixTheme.colorScheme.primary),
        Triple("其它使用", inUse, IN_USE_COLOR),
        Triple("上课中", inClass, MiuixTheme.colorScheme.error),
        Triple(null, (total - free - inUse - inClass).coerceAtLeast(0), MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ).filter { it.second > 0 }
    if (segments.isEmpty()) return
    // 太窄的段也要点得到、写得下数字
    val minWeight = total * 0.1f
    val weights = segments.map { maxOf(it.second.toFloat(), minWeight) }
    val weightSum = weights.sum()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(10.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.forEachIndexed { i, (label, n, color) ->
                val dimmed = selected != "全部" && label != selected
                Box(
                    Modifier
                        .weight(weights[i])
                        .fillMaxHeight()
                        .background(color.copy(alpha = color.alpha * if (dimmed) 0.3f else 1f))
                        .then(if (label != null) Modifier.clickable { onSelect(if (selected == label) "全部" else label) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (label != null && weights[i] / weightSum >= 0.24f) "$label $n" else "$n",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = if (label != null) Color.White else muted,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected == "全部") "共 $total 间，点一段只看这一类" else "只看「$selected」· 共 $total 间",
                style = MiuixTheme.textStyles.footnote1,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            if (selected != "全部") {
                Text(
                    "显示全部",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onSelect("全部") }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 楼栋标题旁的小分段条，配色同 [LiveStatusBar]。 */
@Composable
internal fun LiveMiniBar(rooms: List<LiveRoom>, modifier: Modifier = Modifier) {
    if (rooms.isEmpty()) return
    val parts = listOf(
        rooms.count { it.isFree } to MiuixTheme.colorScheme.primary,
        rooms.count { it.isInUse } to IN_USE_COLOR,
        rooms.count { it.isInClass } to MiuixTheme.colorScheme.error,
    )
    val rest = rooms.size - parts.sumOf { it.first }
    Row(modifier.width(48.dp).height(5.dp).clip(RoundedCornerShape(50))) {
        (parts + (rest to MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))).forEach { (n, color) ->
            if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(color))
        }
    }
}
