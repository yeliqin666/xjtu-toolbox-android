package com.xjtu.toolbox.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCard
import com.xjtu.toolbox.ui.components.AppInsetColor
import com.xjtu.toolbox.ui.components.PidaiChip
import com.xjtu.toolbox.ui.components.PidaiChipRow
import com.xjtu.toolbox.ui.components.PidaiInsightCard
import com.xjtu.toolbox.ui.components.rememberInsight
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** 「今天吃啥」：照近三周的吃饭习惯、余额和今天的课，推荐今天还没吃的几顿。 */
private val MEAL_PICKS = InsightSpec(
    id = "meal_picks",
    tools = setOf("get_schedule", "app_guide", "campus_map"),
    ttlMs = 3 * 60 * 60 * 1000L,
    busyHint = "在翻你的饭卡流水…",
    instruction = """
任务：推荐用户今天接下来几顿（早饭 / 午饭 / 晚饭 / 夜宵里还没过点也还没吃的）去哪吃、吃什么。
- 依据给你的近三周就餐习惯（常去哪、花多少、几点吃）和今天已经吃过的。可以调 get_schedule 看今天的课在哪栋楼，饭点前后在哪上课就推离那儿近的食堂；app_guide(campus) 有食堂分布和开放时间。
- 换着吃：最近三天连着吃的那家往后放，推一家他去过但不常去的，或同一食堂的另一个档口。
- 看钱：余额撑不了几天就推便宜的，价格按他在那家的历史均价估。
- 不知道具体菜名就别编菜名，说档口或品类。
- 「这次想要」里写了的（省钱、换口味、吃得快…）优先照办。
只输出：
{"meals":[{"slot":"午饭","time":"11:50","where":"食堂（档口）","what":"不超过 12 字","price":"约 15 元","why":"不超过 20 字"}],"quip":"一句俏皮吐槽他的吃饭习惯，不超过 30 字，可以没有"}
    """.trimIndent(),
    card = PidaiCard.MEAL,
)

/** 「今天吃啥」的偏好：按账号记在本机。 */
private object MealPrefs {
    val OPTIONS = listOf("省钱", "换口味", "吃得快", "清淡", "多吃肉", "少排队")
    private val store get() = com.xjtu.toolbox.platform.keyValueStore("meal_picks" + com.xjtu.toolbox.account.AccountContext.safeSuffix())

    fun load(): Set<String> = store.getString("prefs").orEmpty().split(',').filter { it in OPTIONS }.toSet()
    fun save(prefs: Set<String>) = store.putString("prefs", prefs.joinToString(","))
}

/**
 * 首页头卡的一句饭点推荐（纯本地，不发请求）：饭点内从近 20 天的餐饮流水里，挑这一顿常去、
 * 且近两天没去过的档口；没有数据或不在饭点返回 null。
 */
fun mealHomeHint(transactions: List<Transaction>, today: LocalDate, hour: Int): String? {
    val (slot, hours) = when (hour) {
        in 11..13 -> "午饭" to 11..13
        in 17..19 -> "晚饭" to 17..19
        else -> return null
    }
    val since = today.minus(20, DateTimeUnit.DAY).toString()
    val recent = today.minus(2, DateTimeUnit.DAY).toString()
    val meals = transactions.filter { it.amount < 0 && it.time.take(10) >= since && categoryOf(it) == "餐饮" }
    val pick = meals.groupBy { it.displayMerchant.ifBlank { MealReport.canteenOf(it) ?: "" } }
        .filterKeys { it.isNotBlank() }
        .mapNotNull { (place, txs) ->
            val inSlot = txs.count { MealReport.clockOf(it)?.let { m -> m / 60 in hours } == true }
            // 这一顿没去过、或近两天刚吃过的不推，让它轮换
            if (inSlot == 0 || txs.any { it.time.take(10) >= recent }) null
            else Triple(place, inSlot, txs.sumOf { -it.amount } / txs.size)
        }
        .maxByOrNull { it.second } ?: return null
    val avg = (pick.third * 10).roundToInt() / 10.0
    return "${slot}可以试试${pick.first}，你常在这顿吃 · 均价 $avg 元"
}

/** 交给屁岱的就餐摘要：近 21 天按食堂/档口聚合 + 时段 + 今天吃过的 + 余额。纯本地计算。 */
internal fun mealPicksInput(transactions: List<Transaction>, balance: Double?, dailyRate: Double?): String? {
    val today = todayOnCampus()
    val since = today.minus(20, DateTimeUnit.DAY).toString()
    val meals = transactions.filter { it.amount < 0 && it.time.take(10) >= since && categoryOf(it) == "餐饮" }
    if (meals.isEmpty()) return null
    fun slotOf(tx: Transaction): String {
        val min = MealReport.clockOf(tx) ?: return "其他"
        return when (min / 60) {
            in 5..10 -> "早饭"
            in 11..15 -> "午饭"
            in 16..20 -> "晚饭"
            else -> "夜宵"
        }
    }
    return buildString {
        append("今天：$today\n")
        balance?.let { append("余额：${(it * 10).roundToInt() / 10.0} 元") }
        runwayDays(balance ?: -1.0, dailyRate)?.let { append("，照现在的吃法约够 $it 天") }
        append("\n近 21 天常去（地点｜次数｜均价｜多在哪顿）：\n")
        meals.groupBy { it.displayMerchant.ifBlank { MealReport.canteenOf(it) ?: "未知档口" } }
            .entries.sortedByDescending { it.value.size }.take(10)
            .forEach { (place, txs) ->
                val avg = txs.sumOf { -it.amount } / txs.size
                val slot = txs.groupingBy(::slotOf).eachCount().maxByOrNull { it.value }?.key
                append("- $place｜${txs.size}｜${(avg * 10).roundToInt() / 10.0} 元｜$slot\n")
            }
        append("最近 8 顿（时间｜地点｜金额）：\n")
        meals.sortedByDescending { it.time }.take(8).forEach {
            append("- ${it.time.take(16)}｜${it.displayMerchant}｜${-it.amount}\n")
        }
        val todays = meals.filter { it.time.startsWith(today.toString()) }
        append("今天已吃：")
        append(if (todays.isEmpty()) "还没有" else todays.joinToString("、") { "${slotOf(it)}（${it.displayMerchant}）" })
    }
}

@Composable
internal fun MealPicksCard(transactions: List<Transaction>, balance: Double?, dailyRate: Double?, modifier: Modifier = Modifier) {
    var prefs by remember { mutableStateOf(MealPrefs.load()) }
    val base = remember(transactions, balance) { mealPicksInput(transactions, balance, dailyRate) } ?: return
    val input = remember(base, prefs) { base + "\n这次想要：" + prefs.joinToString("、").ifBlank { "没特别要求" } }
    val state = rememberInsight(MEAL_PICKS, input)
    PidaiInsightCard(
        title = "今天吃啥",
        state = state,
        modifier = modifier,
        subtitle = "按你常去的档口、余额和今天的课推荐",
        askPrompt = { "今天吃啥你再帮我想想，换几个没吃过的？" },
        controls = {
            PidaiChipRow {
                MealPrefs.OPTIONS.forEach { p ->
                    PidaiChip(p, selected = p in prefs, onClick = {
                        prefs = if (p in prefs) prefs - p else prefs + p
                        MealPrefs.save(prefs)
                    })
                }
            }
        },
    ) { json ->
        ((json["meals"] as? JsonArray).orEmpty()).mapNotNull { runCatching { it.jsonObject }.getOrNull() }.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppInsetColor)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(52.dp)) {
                    Text(m["slot"].safeStringOrNull().orEmpty(), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary)
                    Text(m["time"].safeStringOrNull().orEmpty(), style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        listOfNotNull(m["where"].safeStringOrNull(), m["what"].safeStringOrNull()).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                    )
                    m["why"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                m["price"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        json["quip"].safeStringOrNull()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            Text("「$it」", style = MiuixTheme.textStyles.footnote1, fontStyle = FontStyle.Italic,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
