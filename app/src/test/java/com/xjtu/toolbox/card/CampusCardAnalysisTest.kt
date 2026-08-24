package com.xjtu.toolbox.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CampusCardAnalysisTest {

    private val today = LocalDate.of(2026, 8, 24)

    @Test
    fun periodTitle_thisMonthVsHistoricalVsSpan() {
        assertEquals(
            "本月消费",
            CampusCardAnalysis.periodTitle(LocalDate.of(2026, 8, 1), today, today)
        )
        assertEquals(
            "2023年3月消费",
            CampusCardAnalysis.periodTitle(
                LocalDate.of(2023, 3, 1),
                LocalDate.of(2023, 3, 31),
                today
            )
        )
        assertEquals(
            "区间消费",
            CampusCardAnalysis.periodTitle(
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2025, 6, 30),
                today
            )
        )
        assertEquals(
            "区间消费",
            CampusCardAnalysis.periodTitle(today.minusMonths(1), today, today)
        )
    }

    @Test
    fun monthName_doesNotCallOldRangeThisMonth() {
        assertEquals(
            "2023年6月",
            CampusCardAnalysis.monthName(YearMonth.of(2023, 6), LocalDate.of(2023, 12, 31), today)
        )
        assertEquals("本月", CampusCardAnalysis.monthName(YearMonth.of(2026, 8), today, today))
        assertEquals("上月", CampusCardAnalysis.monthName(YearMonth.of(2026, 7), today, today))
    }

    @Test
    fun monthChangeInsight_usesDatedMonthsForHistory() {
        val june = MonthlyStats(
            month = YearMonth.of(2023, 6),
            totalSpend = 400.0,
            totalIncome = 0.0,
            transactionCount = 10,
            topMerchants = emptyList(),
            avgDailySpend = 400.0 / 30,
            daysCovered = 30
        )
        val may = june.copy(month = YearMonth.of(2023, 5), totalSpend = 200.0, avgDailySpend = 200.0 / 31, daysCovered = 31)
        val line = CampusCardAnalysis.monthChangeInsight(
            listOf(june, may),
            LocalDate.of(2023, 5, 1),
            LocalDate.of(2023, 6, 30),
            today
        )
        assertTrue(line!!.contains("2023年6月"))
        assertTrue(line.contains("2023年5月"))
        assertFalse(line.contains("本月"))
        assertTrue(line.contains("增长"))
    }

    @Test
    fun dailyInsight_usesSelectedDayCount() {
        val line = CampusCardAnalysis.dailyInsight(
            totalSpend = 3650.0,
            start = LocalDate.of(2023, 1, 1),
            end = LocalDate.of(2023, 12, 31),
            monthCount = 12
        )
        assertTrue(line!!.contains("365天"))
        assertTrue(line.contains("月均"))
        assertNull(
            CampusCardAnalysis.dailyInsight(
                100.0,
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2023, 1, 5),
                1
            )
        )
    }

    @Test
    fun monthLabel_showsYearWhenRangeCrossesYears() {
        assertEquals("3月", CampusCardAnalysis.monthLabel(YearMonth.of(2023, 3), spanYears = false))
        assertEquals("2023年3月", CampusCardAnalysis.monthLabel(YearMonth.of(2023, 3), spanYears = true))
    }
}
