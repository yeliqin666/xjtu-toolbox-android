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
    fun personaTags_onlyTagsStrongSignals() {
        val meals = mapOf(
            "早餐" to MealTimeStats(count = 2, totalAmount = 10.0, avgAmount = 5.0),
            "午餐" to MealTimeStats(count = 40, totalAmount = 480.0, avgAmount = 12.0),
            "夜宵" to MealTimeStats(count = 12, totalAmount = 120.0, avgAmount = 10.0),
        )
        val tags = CampusCardAnalysis.personaTags(
            food = mapOf("面食" to 400.0, "米饭" to 200.0),
            meals = meals,
            activeDays = 40,
            foodSpend = 610.0,
            topMerchant = MerchantStat("康桥苑一楼面食", 300.0, 30),
            spendCount = 100,
        ).map { it.second }
        assertEquals(listOf("面食派", "基本不吃早饭", "夜宵常客", "「康桥苑一楼面」老主顾"), tags)

        // 样本太少：什么都不贴
        assertTrue(
            CampusCardAnalysis.personaTags(
                food = mapOf("面食" to 10.0, "米饭" to 10.0, "自选" to 10.0, "汤粥" to 10.0),
                meals = mapOf("夜宵" to MealTimeStats(2, 20.0, 10.0)),
                activeDays = 3, foodSpend = 40.0, topMerchant = null, spendCount = 4,
            ).isEmpty()
        )
    }

    @Test
    fun monthLabel_showsYearWhenRangeCrossesYears() {
        assertEquals("3月", CampusCardAnalysis.monthLabel(YearMonth.of(2023, 3), spanYears = false))
        assertEquals("2023年3月", CampusCardAnalysis.monthLabel(YearMonth.of(2023, 3), spanYears = true))
    }
}
