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

    fun dailyInsight(
        totalSpend: Double,
        start: LocalDate,
        end: LocalDate,
        monthCount: Int,
    ): String? {
        val days = calendarDays(start, end)
        if (totalSpend <= 0 || days < 8) return null
        val monthDivisor = monthCount.coerceAtLeast(1)
        return "统计区间（${days}天）日均消费 ¥%.1f，月均 ¥%.0f".format(
            totalSpend / days,
            totalSpend / monthDivisor
        )
    }

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
