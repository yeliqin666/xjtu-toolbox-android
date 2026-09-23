package com.xjtu.toolbox.card

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
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

object CampusCardAnalysis {
    fun calendarDays(start: LocalDate, end: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, end).toInt() + 1).coerceAtLeast(1)

    fun spansYears(start: LocalDate, end: LocalDate): Boolean =
        start.year != end.year

    fun periodTitle(start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now()): String {
        val thisMonth = YearMonth.from(today)
        val startMonth = YearMonth.from(start)
        val endMonth = YearMonth.from(end)
        val thisMonthStart = thisMonth.atDay(1)
        val thisMonthEnd = minOf(thisMonth.atEndOfMonth(), today)
        if (start == thisMonthStart && end == thisMonthEnd) return "本月消费"
        if (startMonth == endMonth && start == startMonth.atDay(1) && end == minOf(endMonth.atEndOfMonth(), today)) {
            return if (startMonth == thisMonth) "本月消费" else "${start.year}年${start.monthValue}月消费"
        }
        return "区间消费"
    }

    fun periodSubtitle(start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now()): String? {
        if (periodTitle(start, end, today) == "本月消费") return null
        return "${start.year}/${start.monthValue}/${start.dayOfMonth}–${end.year}/${end.monthValue}/${end.dayOfMonth}"
    }

    fun monthLabel(month: YearMonth, spanYears: Boolean): String =
        if (spanYears) "${month.year}年${month.monthValue}月" else "${month.monthValue}月"

    fun monthName(
        month: YearMonth,
        rangeEnd: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): String {
        val thisMonth = YearMonth.from(today)
        val rangeEndMonth = YearMonth.from(rangeEnd)
        return when {
            month == thisMonth && rangeEndMonth == thisMonth -> "本月"
            month == thisMonth.minusMonths(1) && rangeEndMonth == thisMonth -> "上月"
            else -> "${month.year}年${month.monthValue}月"
        }
    }

    fun formatPeakDay(dateStr: String, spanYears: Boolean): String {
        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return dateStr
        return if (spanYears || date.year != LocalDate.now().year) {
            "${date.year}/${date.monthValue}/${date.dayOfMonth}"
        } else {
            "${date.monthValue}/${date.dayOfMonth}"
        }
    }

    fun monthFullyInRange(month: YearMonth, start: LocalDate, end: LocalDate): Boolean {
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        return !monthStart.isBefore(start) && !monthEnd.isAfter(end)
    }

    fun summarizeRange(
        stats: List<MonthlyStats>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = LocalDate.now(),
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
        today: LocalDate = LocalDate.now(),
    ): String? {
        val change = monthChange(stats, start, end, today) ?: return null
        val consecutive = consecutiveTail(stats) ?: return null
        val (latest, prev) = consecutive
        val latestName = monthName(latest.month, end, today)
        val prevName = monthName(prev.month, end, today)
        val latestFull = monthFullyInRange(latest.month, start, end) ||
            (latest.month == YearMonth.from(today) && end == today)
        val direction = if (change.first > 0) "增长" else "减少"
        return if (latestFull && monthFullyInRange(prev.month, start, end)) {
            "${latestName}消费比${prevName}${direction} %.0f%%（¥%.0f → ¥%.0f）".format(
                abs(change.first), prev.totalSpend, latest.totalSpend
            )
        } else {
            "${latestName}日均比${prevName}${direction} %.0f%%（¥%.1f → ¥%.1f）".format(
                abs(change.first), prev.avgDailySpend, latest.avgDailySpend
            )
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
        if (latest.month != prev.month.plusMonths(1)) return null
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
            (latest.month == YearMonth.from(today) && end == today)
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
