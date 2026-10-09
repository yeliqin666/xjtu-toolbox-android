package com.xjtu.toolbox.card

import com.xjtu.toolbox.util.todayInSystemZone
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
    val peakDay: String,
    val peakDayAmount: Double,
    val topMerchants: List<MerchantStat>,
    val changePercent: Double?,
    val changeCaption: String?,
)

// 从 :app 搬进 commonMain：`java.time` 换成了 kotlinx-datetime（commonMain 没有 `java.time`）。
// 逐处对应见下面各处注释与 [MonthlyStats] 的 KDoc；日期/金额的格式化也换成了本文件里的手写实现。
object CampusCardAnalysis {
    fun calendarDays(start: LocalDate, end: LocalDate): Int =
        // `ChronoUnit.DAYS.between(start, end)` → `start.daysUntil(end)`（同一个含头不含尾的天数）
        (start.daysUntil(end) + 1).coerceAtLeast(1)

    fun spansYears(start: LocalDate, end: LocalDate): Boolean =
        start.year != end.year

    fun periodTitle(start: LocalDate, end: LocalDate, today: LocalDate = todayInSystemZone()): String {
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

    fun periodSubtitle(start: LocalDate, end: LocalDate, today: LocalDate = todayInSystemZone()): String? {
        if (periodTitle(start, end, today) == "本月消费") return null
        return "${start.year}/${start.month.number}/${start.day}–${end.year}/${end.month.number}/${end.day}"
    }

    fun monthLabel(month: YearMonth, spanYears: Boolean): String =
        if (spanYears) "${month.year}年${month.month.number}月" else "${month.month.number}月"

    fun monthName(
        month: YearMonth,
        rangeEnd: LocalDate,
        today: LocalDate = todayInSystemZone(),
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
        return if (spanYears || date.year != todayInSystemZone().year) {
            "${date.year}/${date.month.number}/${date.day}"
        } else {
            "${date.month.number}/${date.day}"
        }
    }

    fun monthFullyInRange(month: YearMonth, start: LocalDate, end: LocalDate): Boolean {
        val monthStart = month.firstDay
        val monthEnd = month.lastDay
        return monthStart >= start && monthEnd <= end
    }

    fun summarizeRange(
        stats: List<MonthlyStats>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = todayInSystemZone(),
    ): RangeSpendSummary {
        val days = calendarDays(start, end)
        val topMerchants = stats.flatMap { it.topMerchants }
            .groupBy { it.name }
            .map { (name, items) -> MerchantStat(name, items.sumOf { it.totalAmount }, items.sumOf { it.count }) }
            .sortedByDescending { it.totalAmount }
            .take(3)
        val peak = stats.maxByOrNull { it.peakDayAmount }
        val change = monthChange(stats, start, end, today)
        return RangeSpendSummary(
            title = periodTitle(start, end, today),
            subtitle = periodSubtitle(start, end, today),
            totalSpend = stats.sumOf { it.totalSpend },
            totalIncome = stats.sumOf { it.totalIncome },
            transactionCount = stats.sumOf { it.transactionCount },
            avgDailySpend = stats.sumOf { it.totalSpend } / days,
            peakDay = peak?.peakDay.orEmpty(),
            peakDayAmount = peak?.peakDayAmount ?: 0.0,
            topMerchants = topMerchants,
            changePercent = change?.first,
            changeCaption = change?.second,
        )
    }

    fun monthChangeInsight(
        stats: List<MonthlyStats>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = todayInSystemZone(),
    ): String? {
        val change = monthChange(stats, start, end, today) ?: return null
        val consecutive = consecutiveTail(stats) ?: return null
        val (latest, prev) = consecutive
        val latestName = monthName(latest.month, end, today)
        val prevName = monthName(prev.month, end, today)
        val latestFull = monthFullyInRange(latest.month, start, end) ||
            (latest.month == today.yearMonth && end == today)
        val direction = if (change.first > 0) "增长" else "减少"
        return if (latestFull && monthFullyInRange(prev.month, start, end)) {
            "${latestName}消费比${prevName}${direction} ${percent0(abs(change.first))}（¥${money0(prev.totalSpend)} → ¥${money0(latest.totalSpend)}）"
        } else {
            "${latestName}日均比${prevName}${direction} ${percent0(abs(change.first))}（¥${money1(prev.avgDailySpend)} → ¥${money1(latest.avgDailySpend)}）"
        }
    }

    /**
     * 吃饭画像：从主食构成、各餐天数、每顿均价里挑几个够显著的特征，最多四个。
     * 阈值都偏保守——宁可少贴一个标签，也别给只吃过两次夜宵的人贴「夜宵常客」。
     */
    fun personaTags(
        food: Map<String, Double>,
        meals: Map<String, MealTimeStats>,
        activeDays: Int,
        foodSpend: Double,
        topMerchant: MerchantStat?,
        spendCount: Int,
    ): List<Pair<String, String>> {
        val tags = mutableListOf<Pair<String, String>>()
        val foodTotal = food.values.sum()
        food.maxByOrNull { it.value }?.let { (name, amount) ->
            if (foodTotal > 0 && amount / foodTotal >= 0.3) {
                FOOD_PERSONA[name]?.let(tags::add)
            }
        }
        if (activeDays >= 7) {
            val breakfastRate = (meals["早餐"]?.count ?: 0).toDouble() / activeDays
            when {
                breakfastRate >= 0.6 -> tags += "🌅" to "早饭从不落"
                breakfastRate <= 0.2 -> tags += "😴" to "基本不吃早饭"
            }
            val night = meals["夜宵"]?.count ?: 0
            if (night >= 5 && night >= activeDays * 0.15) tags += "🌙" to "夜宵常客"
        }
        val mealCount = meals.values.sumOf { it.count }
        if (mealCount >= 10 && foodSpend > 0) {
            val perMeal = foodSpend / mealCount
            when {
                perMeal < 9 -> tags += "🪙" to "精打细算"
                perMeal > 20 -> tags += "🍱" to "吃得不含糊"
            }
        }
        // 「电子账户」是没带商户名的扫码付，不是哪一家店
        if (topMerchant != null && "电子账户" !in topMerchant.name &&
            spendCount >= 20 && topMerchant.count >= spendCount * 0.2
        ) {
            tags += "📌" to "「${topMerchant.name.take(6)}」老主顾"
        }
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

    private fun monthChange(
        stats: List<MonthlyStats>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
    ): Pair<Double, String>? {
        val (latest, prev) = consecutiveTail(stats) ?: return null
        val latestFull = monthFullyInRange(latest.month, start, end) ||
            (latest.month == today.yearMonth && end == today)
        val percent = if (latestFull && monthFullyInRange(prev.month, start, end)) {
            if (prev.totalSpend <= 0) return null
            (latest.totalSpend - prev.totalSpend) / prev.totalSpend * 100
        } else {
            if (prev.avgDailySpend <= 0) return null
            (latest.avgDailySpend - prev.avgDailySpend) / prev.avgDailySpend * 100
        }
        val caption = "比${monthName(prev.month, end, today)}"
        return percent to caption
    }
}

// ==================== 从 `CampusCardApi` 搬来的纯统计 ====================
//
// 下面这一批原来挂在 :app 的 `CampusCardApi` 上（它自己也只用这些函数算东西，不碰网络），
// 但**屏与 ViewModel 在 :core**，所以它们必须跟过来 —— 留在 :app 就等于「取数在两端、算数只有
// Android 会算」。搬过来时一行逻辑未改，只换了「住址」：`CampusCardApi.xxx(...)` → `CampusCardAnalysis.xxx(...)`。

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
)

/**
 * 餐饮内部的主食分类。
 *
 * 顶层分类（餐饮/超市/洗浴/水电）对食堂党没有信息量——实测一份 600 条的流水里
 * 餐饮占 97.7%，那张饼图等于只有一块。真正有区分度的是「今天吃面还是吃饭」，
 * 所以在餐饮内部再切一层。
 *
 * 顺序即优先级，从上往下第一个命中的生效：
 * 「临沂炒鸡拌饭」既有「炒」也有「饭」，归米饭比归小吃贴切，所以米饭排在前面；
 * 「丸子粉丝汤」有「粉」也有「汤」，它是汤粉不是拌面，靠汤羹在面食之前拦下。
 */
private val FOOD_SUB_RULES: List<Pair<String, Array<String>>> = listOf(
    "饮品" to arrayOf("水吧", "吧台", "咖啡", "奶茶", "茶", "果汁", "豆浆", "饮"),
    "汤粥" to arrayOf("粥", "汤", "瓦罐", "砂锅", "胡辣", "馄饨", "丸子"),
    "饺包" to arrayOf("饺", "包子", "小笼", "生煎", "馒头"),
    "自选" to arrayOf("自选", "自助", "称量", "智盘", "菜组", "小碗菜", "蒸菜"),
    "米饭" to arrayOf("饭", "盖浇", "烧腊", "煲"),
    "面食" to arrayOf("面", "粉", "米线", "饸络", "凉皮", "米皮", "麻食", "削筋"),
    // 小吃排在最后，当兜底用：这里的「卤」「鱼」「炒」不会抢走前面的分类，
    // 卤肉饭/酸菜鱼米饭先被「米饭」接走，五谷鱼粉先被「面食」接走。
    "小吃" to arrayOf(
        "小吃", "肉夹馍", "肠粉", "饼", "糕", "烧烤", "串", "寿司", "日料", "豆花",
        "快餐", "炒", "卤", "鱼", "迈德思客",
    ),
)

/**
 * 这笔餐饮属于哪类主食。不是餐饮、或认不出来时返回 null，调用方自行归入「其他」。
 */
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

/**
 * 按月汇总。传入查询起止日后：日均按该月落在区间内的天数摊，
 * 区间内没有流水的月份也会占一位（支出为 0），避免跨年趋势把空月藏掉。
 */
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
        val totalSpend = spending.sumOf { -it.amount }
        val overlapStart = maxOf(month.firstDay, inferredStart)
        val overlapEnd = minOf(month.lastDay, inferredEnd)
        val daysCovered = overlapStart.daysUntil(overlapEnd) + 1
        val safeDays = daysCovered.coerceAtLeast(1)
        val dailySpend = spending.groupBy { it.time.substringBefore(" ") }
            .mapValues { (_, txs) -> -txs.sumOf { it.amount } }
        val peakEntry = dailySpend.maxByOrNull { it.value }
        MonthlyStats(
            month = month,
            totalSpend = totalSpend,
            totalIncome = income.sumOf { it.amount },
            transactionCount = txList.size,
            topMerchants = merchantStats,
            avgDailySpend = totalSpend / safeDays,
            peakDay = peakEntry?.key ?: "",
            peakDayAmount = peakEntry?.value ?: 0.0,
            daysCovered = safeDays
        )
    }.toList().sortedByDescending { it.month }
}

/**
 * 消费类别分析（根据商户名 + 交易描述智能分类）
 */
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
        val hour = tx.time.substringAfter(" ").substringBefore(":").toIntOrNull() ?: continue
        if (hour in 0..23) out[hour]++
    }
    return out.toList()
}

/**
 * 用餐时段分析（早/中/晚/夜宵）
 * 返回每个时段的次数由【天数】统计（同一天同时段多笔交易算一天）
 * 同时返回"在校天数"——至少有一顿正餐记录的自然日数（用于早餐率分母）
 */
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

        val hour = try {
            tx.time.substringAfter(" ").substringBefore(":").toInt()
        } catch (_: Exception) { continue }

        // 时段划分：下午3-4点（15/16时）不归入正餐，避免把"买杯下午茶"算成晚餐
        val period = when (hour) {
            in 5..10 -> "早餐"   // 5am-10am
            in 11..14 -> "午餐"  // 11am-2pm
            in 17..21 -> "晚餐"  // 5pm-9pm
            in 22..23, in 0..4 -> "夜宵"  // 10pm-4am
            else -> null        // 3pm-4pm(15/16时) — 下午茶/零食，不计入
        }
        if (period == null) continue
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

/**
 * 工作日 vs 周末消费分析
 */
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

/**
 * 没有商户名的「电子账户消费」按时段判。
 *
 * 扫码点餐这类不回传档口名，只给一句「电子账户消费」，名字里没有任何餐饮特征词，
 * 全部落进「其他」。但它们清一色出现在饭点、金额也在一餐的量级，当成餐饮比当成
 * 「其他」贴近事实。落在饭点之外的仍旧算不出来，保持「其他」。
 */
private fun mealHour(time: String): Boolean {
    val h = time.substringAfter(" ").substringBefore(":").toIntOrNull() ?: return false
    return h in 6..9 || h in 11..13 || h in 17..19
}

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

// ==================== 从 `CampusCardCache` 搬来的纯计算 ====================

private val UTILITY_KEYS = listOf("电控", "水控", "能源", "电量", "电费", "水费")

/**
 * 从流水里算今天的消费汇总。流水时间形如 `2026-09-18 12:03:45`，支出金额为负。
 *
 * 首页卡片刷新（refreshCampusCardCache）和校园卡页各算一遍、各写一遍同一组缓存 key，
 * 以前是两段一字不差的复制粘贴，改一处漏一处小组件就对不上，现在都走这里。
 *
 * 「今天」的默认值从 `LocalDate.now()` 换成 [todayInSystemZone]（同一个系统默认时区）。
 */
fun todaySummaryOf(transactions: List<Transaction>, today: LocalDate = todayInSystemZone()): TodaySpendSummary {
    val todayStr = today.toString()
    val spends = transactions.filter { it.time.startsWith(todayStr) && it.amount < 0 }
    fun sumInHours(hours: IntRange) = spends.filter { tx ->
        tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { it in hours } == true
    }.sumOf { -it.amount }
    return TodaySpendSummary(
        total = spends.sumOf { -it.amount },
        breakfast = sumInHours(5..10),
        lunch = sumInHours(11..14),
        dinner = sumInHours(17..21),
    )
}

/**
 * 近 30 天的在校日均：支出 ÷ 有消费的天数。
 *
 * 分母只数刷过卡的日子——放假整周不刷卡，那些天摊进来日均会被压低、可用天数被高估，
 * 正好在快没钱的时候给出最乐观的数。水电一次充几十上百，不是「吃法」，不算进来。
 * 有消费的天数不足 7 天时样本太少，返回 null。
 *
 * 首页、概览、分析三处的「约够几天」都从这里来，数字才对得上。
 */
fun dailySpendRate(transactions: List<Transaction>, today: LocalDate = todayInSystemZone()): Double? {
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

// ── `String.format("%.Nf")` 的跨端等价实现（JVM 专属，且是默认导入，import 判据看不见） ──
//
// 原实现遍布 `"%.2f".format(x)` / `"%.0f%%".format(x)`。这里手写，口径与原实现逐个对齐：
// 四舍五入到分位（`round` 是「远离零」的半数进位，与 `String.format` 的 HALF_UP 同）、
// 负零照样给 `-0`（Java 的 `"%.0f".format(-0.4)` 就是 `-0`）。非有限值原样给出。

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
