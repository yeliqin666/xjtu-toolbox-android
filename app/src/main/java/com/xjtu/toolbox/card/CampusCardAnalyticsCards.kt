package com.xjtu.toolbox.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.HeroMesh
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.appCardShadow
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 四个饭点的固定配色，饭点节奏图和下面的小格子共用。 */
private val MEAL_COLORS = linkedMapOf(
    "早餐" to Color(0xFFFFB74D),
    "午餐" to Color(0xFFFF8A65),
    "晚餐" to Color(0xFF7986CB),
    "夜宵" to Color(0xFF5C6BC0),
)

/** 与 [CampusCardApi.analyzeMealTimes] 的时段划分一致；15、16 点不算正餐，给灰色。 */
private fun mealOfHour(hour: Int): String? = when (hour) {
    in 5..10 -> "早餐"
    in 11..14 -> "午餐"
    in 17..21 -> "晚餐"
    in 22..23, in 0..4 -> "夜宵"
    else -> null
}

/**
 * 分析页的头卡：区间总支出滚动出来，下面一行在校天数 / 日均 / 每顿，再往下是吃饭画像标签，
 * 一个接一个弹出来。底色是和「我的」页头卡同一套流动网格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpendingHeroCard(
    totalSpend: Double,
    activeDays: Int,
    foodSpend: Double,
    mealCount: Int,
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
                format = { "¥%.0f".format(it) },
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                durationMillis = 800,
            )
            val facts = buildList {
                if (activeDays > 0) {
                    add("在校 $activeDays 天")
                    add("日均 ¥%.1f".format(totalSpend / activeDays))
                }
                if (mealCount > 0 && foodSpend > 0) add("每顿 ¥%.1f".format(foodSpend / mealCount))
            }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(facts.joinToString("  ·  "), style = MiuixTheme.textStyles.footnote1, color = summary)
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
    val pop = remember { Animatable(0f) }
    LaunchedEffect(text) {
        pop.snapTo(0f)
        delay(250L + index * 110L)
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f))
    }
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
    val max = hourly.max().toFloat()
    val peak = hourly.indices.maxBy { hourly[it] }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(hourly) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val idle = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
    val track = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.06f)
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule, null,
                    tint = MiuixTheme.colorScheme.primaryVariant, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("一天的饭点", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("最常 $peak 点刷卡", style = MiuixTheme.textStyles.footnote1, color = summary)
            }
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
                                val c = mealOfHour(h)?.let { MEAL_COLORS[it] } ?: idle
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
            val present = MEAL_COLORS.keys.filter { mealStats[it] != null }
            if (present.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    present.forEach { name ->
                        val stat = mealStats.getValue(name)
                        val c = MEAL_COLORS.getValue(name)
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
                                "¥%.1f".format(stat.avgAmount),
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
    Triple("🥇", Color(0xFFFFC940), 76.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.EmojiEvents, null,
                    tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("消费排行", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium)
            }
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
                        "¥%.0f".format(m.totalAmount), style = MiuixTheme.textStyles.body2,
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
    val rise = remember { Animatable(0f) }
    LaunchedEffect(m.name, m.totalAmount) {
        rise.snapTo(0f)
        delay(listOf(260L, 120L, 0L)[rank])
        rise.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 260f))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(medal, fontSize = if (rank == 0) 28.sp else 22.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            m.name, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            minLines = 2,
        )
        Text("¥%.0f".format(m.totalAmount), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
        Text(
            "${m.count} 次", style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height * rise.value.coerceAtLeast(0f))
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
