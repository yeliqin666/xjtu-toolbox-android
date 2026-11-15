package com.xjtu.toolbox.card

import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth
import kotlin.math.abs

data class RangeSpendSummary(
    val title: String,
    val subtitle: String?,
    val totalSpend: Double,
    val totalIncome: Double,
    val transactionCount: Int,
    val avgDailySpend: Double,
    val changePercent: Double?,
    val changeCaption: String?,
)

object CampusCardAnalysis {
    fun calendarDays(start: LocalDate, end: LocalDate): Int =
        // `ChronoUnit.DAYS.between(start, end)` → `start.daysUntil(end)`（同一个含头不含尾的天数）
        (start.daysUntil(end) + 1).coerceAtLeast(1)

    fun spansYears(start: LocalDate, end: LocalDate): Boolean =
        start.year != end.year

    fun periodTitle(start: LocalDate, end: LocalDate, today: LocalDate = todayOnCampus()): String {
        val thisMonth = today.yearMonth
        val startMonth = start.yearMonth
        val endMonth = end.yearMonth
        val thisMonthStart = thisMonth.firstDay
        val thisMonthEnd = minOf(thisMonth.lastDay, today)
        if (start == thisMonthStart && end == thisMonthEnd) return "本月消费"
        if (startMonth == endMonth && start == startMonth.firstDay && end == minOf(endMonth.lastDay, today)) {
            return if (startMonth == thisMonth) "本月消费" else "${start.year}年${start.month.number}月消费"
        }
        return "区间消费"
    }

    fun periodSubtitle(start: LocalDate, end: LocalDate, today: LocalDate = todayOnCampus()): String? {
        if (periodTitle(start, end, today) == "本月消费") return null
        return "${start.year}/${start.month.number}/${start.day}–${end.year}/${end.month.number}/${end.day}"
    }

    fun monthName(
        month: YearMonth,
        rangeEnd: LocalDate,
        today: LocalDate = todayOnCampus(),
    ): String {
        val thisMonth = today.yearMonth
        val rangeEndMonth = rangeEnd.yearMonth
        return when {
            month == thisMonth && rangeEndMonth == thisMonth -> "本月"
            month == thisMonth.minus(1, DateTimeUnit.MONTH) && rangeEndMonth == thisMonth -> "上月"
            else -> "${month.year}年${month.month.number}月"
        }
    }

    fun formatPeakDay(dateStr: String, spanYears: Boolean): String {
        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return dateStr
        return if (spanYears || date.year != todayOnCampus().year) {
            "${date.year}/${date.month.number}/${date.day}"
        } else {
            "${date.month.number}/${date.day}"
        }
    }

    fun summarizeRange(
        stats: List<MonthlyStats>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = todayOnCampus(),
    ): RangeSpendSummary {
        val days = calendarDays(start, end)
        val change = monthChange(stats)
        return RangeSpendSummary(
            title = periodTitle(start, end, today),
            subtitle = periodSubtitle(start, end, today),
            totalSpend = stats.sumOf { it.totalSpend },
            totalIncome = stats.sumOf { it.totalIncome },
            transactionCount = stats.sumOf { it.transactionCount },
            avgDailySpend = stats.sumOf { it.totalSpend } / days,
            changePercent = change?.third,
            changeCaption = change?.let { "日均比${monthName(it.second.month, end, today)}" },
        )
    }

    fun monthChangeInsight(
        stats: List<MonthlyStats>,
        end: LocalDate,
        today: LocalDate = todayOnCampus(),
    ): String? {
        val (latest, prev, percent) = monthChange(stats) ?: return null
        val direction = if (percent > 0) "增长" else "减少"
        return "${monthName(latest.month, end, today)}刷卡日均比${monthName(prev.month, end, today)}$direction " +
            "${percent0(abs(percent))}（¥${money1(prev.perSpendDay)} → ¥${money1(latest.perSpendDay)}）"
    }

    /** 每顿均价：各顿（同一天同一顿的几笔算一顿）的花费 ÷ 顿数。 */
    fun perMeal(meals: Map<String, MealTimeStats>): Double? {
        val n = meals.values.sumOf { it.count }
        return if (n == 0) null else meals.values.sumOf { it.totalAmount } / n
    }

    /**
     * 吃饭画像，最多四个。不只抓极端：口味分散给「什么都吃」，饭点集中给「饭点很准」，
     * 某一顿总去同一家给「常去」，规律的人也能拿到几个像样的标签。
     * [regular] 是 [slotRegular] 的结果。
     */
    fun personaTags(
        food: Map<String, Double>,
        meals: Map<String, MealTimeStats>,
        activeDays: Int,
        hourly: List<Int>,
        regular: Pair<String, String>?,
    ): List<Pair<String, String>> {
        if (activeDays < 7) return emptyList()
        val tags = mutableListOf<Pair<String, String>>()
        val foodTotal = food.values.sum()
        if (foodTotal > 0) {
            val (top, amount) = food.maxBy { it.value }
            when {
                amount / foodTotal >= 0.3 -> FOOD_PERSONA[top]?.let(tags::add)
                food.values.count { it / foodTotal >= 0.08 } >= 4 -> tags += "🍽️" to "什么都吃"
            }
        }
        val breakfastRate = (meals["早餐"]?.count ?: 0).toDouble() / activeDays
        when {
            breakfastRate >= 0.6 -> tags += "🌅" to "早饭从不落"
            breakfastRate <= 0.2 -> tags += "😴" to "基本不吃早饭"
        }
        val night = meals["夜宵"]?.count ?: 0
        if (night >= 5 && night >= activeDays * 0.15) tags += "🌙" to "夜宵常客"
        val avg = perMeal(meals)
        if (avg != null && meals.values.sumOf { it.count } >= 10) {
            if (avg < 9) tags += "🪙" to "精打细算"
            else if (avg > 20) tags += "🍱" to "吃得不含糊"
        }
        // 每顿里最集中的那个钟点占几成；早饭常在两个钟点间摆动，三顿平均六成就算准
        if (hourly.size == 24) {
            val shares = MEAL_HOURS.values.mapNotNull { r ->
                val n = r.sumOf { hourly[it] }
                if (n < 10) null else r.maxOf { hourly[it] }.toDouble() / n
            }
            if (shares.size >= 2 && shares.average() >= 0.6) tags += "⏰" to "饭点很准"
        }
        regular?.let { (slot, name) -> tags += "📌" to "${slot}常去「${name.take(6)}」" }
        return tags.take(4)
    }

    private val FOOD_PERSONA = mapOf(
        "面食" to ("🍜" to "面食派"),
        "米饭" to ("🍚" to "米饭党"),
        "自选" to ("🥗" to "自选党"),
        "汤粥" to ("🥣" to "汤粥党"),
        "饺包" to ("🥟" to "饺子包子党"),
        "小吃" to ("🍢" to "小吃党"),
        "饮品" to ("🧋" to "奶茶续命"),
    )

    private fun consecutiveTail(stats: List<MonthlyStats>): Pair<MonthlyStats, MonthlyStats>? {
        val sorted = stats.sortedByDescending { it.month }
        if (sorted.size < 2) return null
        val latest = sorted[0]
        val prev = sorted[1]
        if (latest.month != prev.month.plus(1, DateTimeUnit.MONTH)) return null
        return latest to prev
    }

    /**
     * 相邻两月按「刷卡日均」比：学生一放假整月只刷几天，按总额或自然日摊都会把假期算成「省了六成」。
     * 任一个月刷卡不足 5 天不比。
     */
    private fun monthChange(stats: List<MonthlyStats>): Triple<MonthlyStats, MonthlyStats, Double>? {
        val (latest, prev) = consecutiveTail(stats) ?: return null
        if (latest.spendDays < 5 || prev.spendDays < 5 || prev.totalSpend <= 0) return null
        val percent = (latest.perSpendDay - prev.perSpendDay) / prev.perSpendDay * 100
        return Triple(latest, prev, percent)
    }
}

// ==================== 纯统计 ====================

/** 食堂档口/品牌名碎片。顺序不重要；「超市」类必须在 classify 里先判。 */
private val FOOD_MERCHANT_KEYS = arrayOf(
    "食", "餐", "食堂", "面", "饭", "粥", "菜", "吧台", "咖啡",
    "饮", "小面", "米线", "饸络", "凉皮", "卤", "削筋", "称量",
    "自助", "档口", "窗口", "烧烤", "奶茶", "豆浆", "包子", "饺子",
    "炒", "烩", "煮", "蒸", "时光", "美食", "小吃", "麻辣", "烤", "煎",
    "馒头", "饼", "糕", "果汁", "茶", "鸡", "鱼", "肉", "蛋",
    // 2026-08 缓存里漏进「其他」的档口
    "苑", "粉", "粉丝", "瓦罐", "寿司", "日料", "小笼", "馄饨", "汤包",
    // 「饺」而不是「饺子」：珍念水饺、轻食水饺这类写的是「水饺」，
    // 只配「饺子」会整家店漏进「其他」（实测 8 笔 ¥144 就是这么漏的）
    "饺", "自选", "豆花", "豆苗", "江记", "旧迹", "丸子", "肠粉",
    "迈德思客", "麦当劳", "肯德基", "汉堡", "披萨", "必胜客",
    "风味", "拉面", "米皮", "凉粉", "胡辣汤", "砂锅", "麻食",
    "瑞幸", "库迪", "蜜雪", "星巴克",
)

/**
 * 餐饮内部的主食分类，按顺序取第一个命中的。饺包排在汤粥前（砂锅饺子、汤包），
 * 米饭排在饮品前（茶泡饭）；饮品不用单字「饮」，它会吃掉「饮食中心」。
 */
private val FOOD_SUB_RULES: List<Pair<String, Array<String>>> = listOf(
    "饺包" to arrayOf("饺", "包子", "汤包", "小笼", "生煎", "馒头"),
    "汤粥" to arrayOf("粥", "汤", "瓦罐", "砂锅", "胡辣", "馄饨", "丸子"),
    "自选" to arrayOf("自选", "自助", "称量", "智盘", "菜组", "小碗菜", "蒸菜"),
    "米饭" to arrayOf("饭", "盖浇", "烧腊", "煲"),
    "饮品" to arrayOf("水吧", "吧台", "咖啡", "奶茶", "茶", "果汁", "豆浆", "饮品", "瑞幸", "库迪", "蜜雪", "星巴克"),
    "面食" to arrayOf("面", "粉", "米线", "饸络", "凉皮", "米皮", "麻食", "削筋"),
    // 小吃排在最后，当兜底用：这里的「卤」「鱼」「炒」不会抢走前面的分类，
    // 卤肉饭/酸菜鱼米饭先被「米饭」接走，五谷鱼粉先被「面食」接走。
    "小吃" to arrayOf(
        "小吃", "肉夹馍", "肠粉", "饼", "糕", "烧烤", "串", "寿司", "日料", "豆花",
        "快餐", "炒", "卤", "鱼", "迈德思客",
    ),
)

/** 这笔餐饮属于哪类主食。 */
fun foodSubCategory(tx: Transaction): String? {
    if (classifyMerchant(tx.displayMerchant, tx.description, tx.time) != "餐饮") return null
    val m = tx.displayMerchant.lowercase()
    return FOOD_SUB_RULES.firstOrNull { (_, keys) -> keys.any { m.contains(it) } }?.first
}

/** 餐饮支出按主食分类汇总，金额降序。 */
fun breakdownFood(transactions: List<Transaction>): Map<String, Double> {
    val out = mutableMapOf<String, Double>()
    for (tx in transactions) {
        if (tx.amount >= 0) continue
        val sub = foodSubCategory(tx) ?: continue
        out[sub] = (out[sub] ?: 0.0) + (-tx.amount)
    }
    return out.toList().sortedByDescending { it.second }.toMap()
}

/** 按月汇总。 */
fun calculateMonthlyStats(
    transactions: List<Transaction>,
    rangeStart: LocalDate? = null,
    rangeEnd: LocalDate? = null,
): List<MonthlyStats> {
    val byMonth = linkedMapOf<YearMonth, MutableList<Transaction>>()
    for (tx in transactions) {
        // `LocalDate.parse(tx.time.substringBefore(" "), dateFormat)` → kotlinx-datetime 的
        // 单参 `LocalDate.parse`：流水时间本来就是 `yyyy-MM-dd HH:mm:ss`，与 `dateFormat` 同一个形状。
        val date = runCatching { LocalDate.parse(tx.time.substringBefore(" ")) }.getOrNull() ?: continue
        byMonth.getOrPut(date.yearMonth) { mutableListOf() }.add(tx)
    }

    val inferredStart = rangeStart
        ?: byMonth.keys.minOrNull()?.firstDay
        ?: return emptyList()
    val inferredEnd = rangeEnd
        ?: byMonth.keys.maxOrNull()?.lastDay
        ?: inferredStart
    val startMonth = inferredStart.yearMonth
    val endMonth = inferredEnd.yearMonth

    val months = generateSequence(startMonth) { current ->
        val next = current.plus(1, DateTimeUnit.MONTH)
        if (next > endMonth) null else next
    }

    return months.map { month ->
        val txList = byMonth[month].orEmpty()
        val spending = txList.filter { it.amount < 0 }
        val income = txList.filter { it.amount > 0 }
        val merchantStats = spending.groupBy { it.displayMerchant }
            .map { (name, txs) ->
                MerchantStat(
                    name = name,
                    totalAmount = -txs.sumOf { it.amount },
                    count = txs.size
                )
            }
            .sortedByDescending { it.totalAmount }
            .take(10)
        // 逐笔取反再求和，不写 -sumOf：没有消费的月份 -(0.0) 是 -0.0，趋势图上印成「¥-0」。
        val dailySpend = spending.groupBy { it.time.substringBefore(" ") }
            .mapValues { (_, txs) -> -txs.sumOf { it.amount } }
        val peakEntry = dailySpend.maxByOrNull { it.value }
        MonthlyStats(
            month = month,
            totalSpend = spending.sumOf { -it.amount },
            totalIncome = income.sumOf { it.amount },
            transactionCount = txList.size,
            topMerchants = merchantStats,
            peakDay = peakEntry?.key ?: "",
            peakDayAmount = peakEntry?.value ?: 0.0,
            spendDays = dailySpend.size,
        )
    }.toList().sortedByDescending { it.month }
}

/** 消费类别分析（根据商户名 + 交易描述智能分类） */
fun categorizeSpending(transactions: List<Transaction>): Map<String, Double> {
    val categories = mutableMapOf<String, Double>()
    for (tx in transactions) {
        if (tx.amount >= 0) continue  // 只分析支出
        val category = classifyMerchant(tx.displayMerchant, tx.description, tx.time)
        categories[category] = (categories[category] ?: 0.0) + (-tx.amount)
    }
    return categories.toList().sortedByDescending { it.second }.toMap()
}

/** 餐饮消费按钟点计笔数，下标即 0–23 点。 */
fun hourlyMeals(transactions: List<Transaction>): List<Int> {
    val out = IntArray(24)
    for (tx in transactions) {
        if (tx.amount >= 0) continue
        if (classifyMerchant(tx.displayMerchant, tx.description, tx.time) != "餐饮") continue
        tx.hour()?.let { out[it]++ }
    }
    return out.toList()
}

/** 三顿正餐的钟点。15、16 点是下午茶不算正餐，22 点到凌晨 4 点是夜宵。全模块分顿都用这一份。 */
internal val MEAL_HOURS = linkedMapOf("早餐" to 5..10, "午餐" to 11..14, "晚餐" to 17..21)

internal fun mealSlotOf(hour: Int): String? =
    MEAL_HOURS.entries.firstOrNull { hour in it.value }?.key ?: if (hour >= 22 || hour <= 4) "夜宵" else null

private fun Transaction.hour(): Int? = time.substringAfter(" ").substringBefore(":").toIntOrNull()?.takeIf { it in 0..23 }

/**
 * 哪一顿最认一家店：这一顿至少 20 次、那家占两成半以上，几顿都够格取占比最高的。
 * 返回（顿，店名）。没带商户名的「电子账户」不算一家店。
 */
fun slotRegular(transactions: List<Transaction>): Pair<String, String>? =
    transactions.filter { it.amount < 0 && categoryOf(it) == "餐饮" && "电子账户" !in it.displayMerchant }
        .groupBy { tx -> tx.hour()?.let(::mealSlotOf) }
        .mapNotNull { (slot, txs) ->
            if (slot == null || txs.size < 20) return@mapNotNull null
            val (name, n) = txs.groupingBy { it.displayMerchant }.eachCount().maxBy { it.value }
            Triple(slot, name, n.toDouble() / txs.size).takeIf { it.third >= 0.25 }
        }
        .maxByOrNull { it.third }
        ?.let { it.first to it.second }

/** 用餐时段分析（早/中/晚/夜宵），按天数统计次数，并返回在校天数。 */
fun analyzeMealTimes(transactions: List<Transaction>): Pair<Map<String, MealTimeStats>, Int> {
    // 先按时段收集所有交易，再按日期聚合
    val rawMeals = mutableMapOf<String, MutableMap<String, MutableList<Double>>>()
    for (period in listOf("早餐", "午餐", "晚餐", "夜宵")) {
        rawMeals[period] = mutableMapOf()
    }

    for (tx in transactions) {
        if (tx.amount >= 0) continue
        val category = classifyMerchant(tx.displayMerchant, tx.description, tx.time)
        if (category != "餐饮") continue
        val period = tx.hour()?.let(::mealSlotOf) ?: continue
        val date = tx.time.substringBefore(" ")
        rawMeals[period]?.getOrPut(date) { mutableListOf() }?.add(-tx.amount)
    }

    // 在校天数 = 任意正餐时段（早/午/晚）有消费记录的 distinct 日期数
    val activeDates = (rawMeals["早餐"]?.keys.orEmpty() +
            rawMeals["午餐"]?.keys.orEmpty() +
            rawMeals["晚餐"]?.keys.orEmpty()).toSet()
    val activeCampusDays = activeDates.size

    val mealStats = rawMeals.filter { it.value.isNotEmpty() }.mapValues { (_, dateMap) ->
        val dayCount = dateMap.size  // 有该时段用餐的天数
        val totalAmount = dateMap.values.sumOf { it.sum() }
        val avgPerDay = if (dayCount > 0) totalAmount / dayCount else 0.0
        MealTimeStats(
            count = dayCount,
            totalAmount = totalAmount,
            avgAmount = avgPerDay
        )
    }
    return mealStats to activeCampusDays
}

/** 工作日 vs 周末消费分析 */
fun analyzeWeekdayVsWeekend(transactions: List<Transaction>): Pair<DayTypeStats, DayTypeStats> {
    val weekday = mutableListOf<Pair<LocalDate, Double>>()
    val weekend = mutableListOf<Pair<LocalDate, Double>>()

    for (tx in transactions) {
        if (tx.amount >= 0) continue
        val date = try {
            LocalDate.parse(tx.time.substringBefore(" "))
        } catch (_: Exception) { continue }

        val amount = -tx.amount
        // `java.time.DayOfWeek.value`（1=周一…7=周日）→ kotlinx-datetime 的 `isoDayNumber`，同一个编号。
        when (date.dayOfWeek.isoDayNumber) {
            in 1..5 -> weekday.add(date to amount)
            else -> weekend.add(date to amount)
        }
    }

    return DayTypeStats.from("工作日", weekday) to DayTypeStats.from("周末", weekend)
}

/** 没有商户名的「电子账户消费」按时段判。 */
private fun mealHour(time: String): Boolean {
    val h = time.substringAfter(" ").substringBefore(":").toIntOrNull() ?: return false
    return h in 6..9 || h in 11..13 || h in 17..19
}

/** 单笔流水归哪一类（餐饮、超市……），和分类统计同一套规则。 */
fun categoryOf(tx: Transaction): String = classifyMerchant(tx.displayMerchant, tx.description, tx.time)

private fun classifyMerchant(merchant: String, description: String, time: String = ""): String {
    val m = merchant.lowercase()
    val d = description.lowercase()
    fun hit(haystack: String, keys: Array<String>): Boolean = keys.any { haystack.contains(it) }
    return when {
        hit(m, arrayOf("浴室", "澡堂", "淋浴", "浴池")) -> "洗浴"
        hit(m, arrayOf("能源", "电控", "水控", "电量")) ||
            hit(d, arrayOf("电费", "水费", "能源")) -> "水电"
        // 「超级市场」不含连续「超市」二字（松林超级市场曾漏进其他）
        hit(m, arrayOf("超市", "超级市场", "便利", "商店", "售卖", "小卖", "便民", "百货", "卖场")) -> "超市"
        hit(m, arrayOf("图书", "打印", "复印", "文印", "书店", "文具")) -> "学习"
        hit(m, arrayOf("洗衣", "洗涤", "干洗", "洗鞋")) -> "洗衣"
        hit(m, arrayOf("班车", "通勤", "校车")) -> "交通"
        hit(m, FOOD_MERCHANT_KEYS) -> "餐饮"
        hit(m, arrayOf("医院", "药", "诊所", "卫生")) -> "医疗"
        hit(d, arrayOf("圈存", "充值", "转账")) -> "充值"
        // 只有渠道名、没有档口名，且发生在饭点，见 [mealHour]
        hit(m, arrayOf("电子账户")) && mealHour(time) -> "餐饮"
        else -> "其他"
    }
}


private val UTILITY_KEYS = listOf("电控", "水控", "能源", "电量", "电费", "水费")

/** 从流水里算今天的消费汇总。 */
fun todaySummaryOf(transactions: List<Transaction>, today: LocalDate = todayOnCampus()): TodaySpendSummary {
    val todayStr = today.toString()
    val spends = transactions.filter { it.time.startsWith(todayStr) && it.amount < 0 }
    fun sumIn(slot: String) = spends.filter { tx -> tx.hour()?.let(::mealSlotOf) == slot }.sumOf { -it.amount }
    return TodaySpendSummary(
        total = spends.sumOf { -it.amount },
        breakfast = sumIn("早餐"),
        lunch = sumIn("午餐"),
        dinner = sumIn("晚餐"),
    )
}

/** 近 30 天的在校日均：支出 ÷ 有消费的天数。 */
fun dailySpendRate(transactions: List<Transaction>, today: LocalDate = todayOnCampus()): Double? {
    val since = today.minus(29, DateTimeUnit.DAY).toString()
    val until = today.toString()
    val spends = transactions.filter { tx ->
        val date = tx.time.take(10)
        tx.amount < 0 && date >= since && date <= until &&
            UTILITY_KEYS.none { tx.merchant.contains(it) || tx.description.contains(it) }
    }
    val days = spends.map { it.time.take(10) }.distinct().size
    if (days < 7) return null
    return spends.sumOf { -it.amount } / days
}

/** 余额按日均 [rate] 还能撑几天；算不出来返回 null。 */
fun runwayDays(balance: Double, rate: Double?): Int? =
    if (rate == null || rate <= 0 || balance < 0) null else (balance / rate).toInt()

/** 「照现在的吃法约够 N 天」。三天以内改口催充值。 */
fun runwayText(days: Int): String =
    if (days <= 2) "照现在的吃法撑不过 3 天" else "照现在的吃法约够 $days 天"


/** `"%.2f".format(value)` —— 金额两位小数。 */
fun money2(value: Double): String = fixed(value, 2)

/** `"%.1f".format(value)` —— 金额一位小数。 */
fun money1(value: Double): String = fixed(value, 1)

/** `"%.0f".format(value)` —— 金额取整。 */
fun money0(value: Double): String = fixed(value, 0)

/** `"%.0f%%".format(value)` —— 百分比取整。 */
fun percent0(value: Double): String = fixed(value, 0) + "%"

// Float 重载：`RollingNumberText` 的 `format` 回调收的是 `Float`（滚动动画在 Float 上跑），
// 而 `String.format` 那边是先把 Float 提升成 Double 再格式化 —— 同一个结果，所以直接转过去。
/** 见 [money2]。 */
fun money2(value: Float): String = money2(value.toDouble())

/** 见 [money1]。 */
fun money1(value: Float): String = money1(value.toDouble())

/** 见 [money0]。 */
fun money0(value: Float): String = money0(value.toDouble())

private fun fixed(value: Double, digits: Int): String {
    if (!value.isFinite()) return value.toString()
    val factor = when (digits) {
        1 -> 10L
        2 -> 100L
        else -> 1L
    }
    val negative = value < 0
    val scaled = kotlin.math.round(kotlin.math.abs(value) * factor).toLong()
    if (digits == 0) return if (negative) "-$scaled" else "$scaled"
    val whole = scaled / factor
    val frac = (scaled % factor).toString().padStart(digits, '0')
    return if (negative) "-$whole.$frac" else "$whole.$frac"
}
