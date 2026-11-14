package com.xjtu.toolbox.card

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.HeroMesh
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.rememberRevealProgress
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 分析页各卡统一的标题行：主题色小方块图标 + 标题 + 右侧一句小字。 */
@Composable
internal fun AnalyticsHeader(icon: ImageVector, title: String, trailing: String? = null) {
    val tint = MiuixTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/**
 * 分析页的头卡：区间总支出滚动出来，下面三格在校天数 / 日均 / 每顿，再往下是吃饭画像标签，
 * 一个接一个弹出来。底色是和「我的」页头卡同一套流动网格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpendingHeroCard(
    totalSpend: Double,
    activeDays: Int,
    perMeal: Double?,
    tags: List<Pair<String, String>>,
    rangeLabel: String,
) {
    val shape = RoundedCornerShape(20.dp)
    val primary = MiuixTheme.colorScheme.primary
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        Modifier
            .fillMaxWidth()
            .appCardShadow(shape = shape, strong = true)
            .clip(shape)
            .background(AppCardColor)
    ) {
        HeroMesh(base = AppCardColor, accent = primary, modifier = Modifier.matchParentSize())
        Column(Modifier.padding(20.dp)) {
            Text(
                if (rangeLabel.isNotEmpty()) "$rangeLabel · 总支出" else "总支出",
                style = MiuixTheme.textStyles.footnote1, color = summary,
            )
            Spacer(Modifier.height(2.dp))
            RollingNumberText(
                value = totalSpend,
                format = { "¥" + money0(it) },
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                durationMillis = 800,
            )
            val facts = buildList {
                if (activeDays > 0) {
                    add("在校" to "$activeDays 天")
                    add("在校日均" to "¥" + money1(totalSpend / activeDays))
                }
                perMeal?.let { add("每顿" to "¥" + money1(it)) }
            }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.forEach { (label, value) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(label, style = MiuixTheme.textStyles.footnote2, color = summary)
                            Text(value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEachIndexed { i, (emoji, text) -> PersonaChip(emoji, text, i, primary) }
                }
            }
        }
    }
}

@Composable
private fun PersonaChip(emoji: String, text: String, index: Int, tint: Color) {
    val pop = rememberRevealProgress(text, 250L + index * 110L, tween(420, easing = FastOutSlowInEasing))
    Row(
        Modifier
            .graphicsLayer {
                val v = pop.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
            }
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 15.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
            color = tint, maxLines = 1,
        )
    }
}

/**
 * 一天的饭点：24 根柱子是各钟点刷卡吃饭的笔数，按饭点上色，从左往右依次长起来；
 * 下面四格是各餐的均价与天数（替换了原来那张四根横条的「用餐分析」）。
 */
@Composable
internal fun MealRhythmCard(hourly: List<Int>, mealStats: Map<String, MealTimeStats>) {
    if (hourly.size != 24 || hourly.all { it == 0 }) return
    val palette = campusCardPalette()
    val mealColors = linkedMapOf("早餐" to palette[3], "午餐" to palette[7], "晚餐" to palette[2], "夜宵" to palette[4])
    val max = hourly.max().toFloat()
    val peak = hourly.indices.maxBy { hourly[it] }
    val grow = rememberRevealProgress(hourly, spec = tween(600, easing = FastOutSlowInEasing))
    val idle = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
    val track = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.06f)
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            AnalyticsHeader(Icons.Default.Schedule, "一天的饭点", "最常 $peak 点刷卡")
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .drawBehind {
                        val slot = size.width / 24f
                        val barW = slot * 0.62f
                        val r = CornerRadius(barW / 2f, barW / 2f)
                        for (h in 0 until 24) {
                            val x = h * slot + (slot - barW) / 2f
                            // 左边先长、右边后长，整条曲线像是被从左往右描出来
                            val local = ((grow.value - h / 24f * 0.45f) / 0.55f).coerceIn(0f, 1f)
                            val full = if (hourly[h] == 0) 0f else (hourly[h] / max).coerceAtLeast(0.06f)
                            val barH = size.height * full * local
                            drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), r)
                            if (barH > 0f) {
                                val c = mealSlotOf(h)?.let { mealColors[it] } ?: idle
                                drawRoundRect(c, Offset(x, size.height - barH), Size(barW, barH), r)
                            }
                        }
                    }
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "6", "12", "18", "24").forEach {
                    Text(it, style = MiuixTheme.textStyles.footnote2, color = summary)
                }
            }
            val present = mealColors.keys.filter { mealStats[it] != null }
            if (present.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    present.forEach { name ->
                        val stat = mealStats.getValue(name)
                        val c = mealColors.getValue(name)
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.copy(alpha = 0.14f))
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(name, style = MiuixTheme.textStyles.footnote1, color = c, fontWeight = FontWeight.Medium)
                            Text(
                                "¥" + money1(stat.avgAmount),
                                style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold,
                            )
                            Text("${stat.count} 天", style = MiuixTheme.textStyles.footnote2, color = summary)
                        }
                    }
                }
            }
        }
    }
}

private val PODIUM = listOf(
    Triple("🥇", Color(0xFFB59A63), 76.dp),
    Triple("🥈", Color(0xFFB0BEC5), 54.dp),
    Triple("🥉", Color(0xFFD7A07A), 40.dp),
)

/** 消费排行：前三上领奖台（二、一、三的站位），第四第五名跟在下面。 */
@Composable
internal fun TopMerchantsCard(monthlyStats: List<MonthlyStats>) {
    val merchants = remember(monthlyStats) {
        monthlyStats.flatMap { it.topMerchants }
            .groupBy { it.name }
            .map { (name, s) -> MerchantStat(name, s.sumOf { it.totalAmount }, s.sumOf { it.count }) }
            .sortedByDescending { it.totalAmount }
            .take(5)
    }
    if (merchants.isEmpty()) return
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            AnalyticsHeader(Icons.Default.EmojiEvents, "消费排行", "按金额")
            Spacer(Modifier.height(16.dp))
            val onPodium = if (merchants.size >= 3) 3 else 0
            if (onPodium == 3) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    listOf(1, 0, 2).forEach { rank ->
                        PodiumColumn(merchants[rank], rank, Modifier.weight(1f))
                    }
                }
            }
            merchants.drop(onPodium).forEachIndexed { i, m ->
                Row(
                    Modifier.fillMaxWidth().padding(top = if (i == 0 && onPodium > 0) 14.dp else 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${onPodium + i + 1}", style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold, color = summary,
                        modifier = Modifier.width(24.dp), textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        m.name, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${m.count} 次", style = MiuixTheme.textStyles.footnote1, color = summary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "¥" + money0(m.totalAmount), style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun PodiumColumn(m: MerchantStat, rank: Int, modifier: Modifier = Modifier) {
    val (medal, color, height) = PODIUM[rank]
    // 台子从地面升起来，冠军最后到
    val rise = rememberRevealProgress(m.name to m.totalAmount, listOf(260L, 120L, 0L)[rank], tween(520, easing = FastOutSlowInEasing))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(medal, fontSize = if (rank == 0) 28.sp else 22.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            m.name, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            minLines = 2,
        )
        Text("¥" + money0(m.totalAmount), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
        Text(
            "${m.count} 次", style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // 高度在布局阶段读：升起时只重排这一根柱子，不重组整列
                    val h = (height.toPx() * rise.value.coerceAtLeast(0f)).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0.22f)))),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                "${rank + 1}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
