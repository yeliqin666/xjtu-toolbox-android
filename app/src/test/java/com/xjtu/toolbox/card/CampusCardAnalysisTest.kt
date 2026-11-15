package com.xjtu.toolbox.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus

class CampusCardAnalysisTest {

    private val today = LocalDate(2026, 8, 24)

    @Test
    fun periodTitle_thisMonthVsHistoricalVsSpan() {
        assertEquals(
            "本月消费",
            CampusCardAnalysis.periodTitle(LocalDate(2026, 8, 1), today, today)
        )
        assertEquals(
            "2023年3月消费",
            CampusCardAnalysis.periodTitle(
                LocalDate(2023, 3, 1),
                LocalDate(2023, 3, 31),
                today
            )
        )
        assertEquals(
            "区间消费",
            CampusCardAnalysis.periodTitle(
                LocalDate(2023, 1, 1),
                LocalDate(2025, 6, 30),
                today
            )
        )
        assertEquals(
            "区间消费",
            CampusCardAnalysis.periodTitle(today.minus(1, DateTimeUnit.MONTH), today, today)
        )
    }

    @Test
    fun monthName_doesNotCallOldRangeThisMonth() {
        assertEquals(
            "2023年6月",
            CampusCardAnalysis.monthName(YearMonth(2023, 6), LocalDate(2023, 12, 31), today)
        )
        assertEquals("本月", CampusCardAnalysis.monthName(YearMonth(2026, 8), today, today))
        assertEquals("上月", CampusCardAnalysis.monthName(YearMonth(2026, 7), today, today))
    }

    @Test
    fun monthChangeInsight_usesDatedMonthsForHistory() {
        val june = MonthlyStats(
            month = YearMonth(2023, 6),
            totalSpend = 400.0,
            totalIncome = 0.0,
            transactionCount = 10,
            topMerchants = emptyList(),
            spendDays = 20,
        )
        val may = june.copy(month = YearMonth(2023, 5), totalSpend = 200.0)
        val line = CampusCardAnalysis.monthChangeInsight(listOf(june, may), LocalDate(2023, 6, 30), today)
        assertTrue(line!!.contains("2023年6月"))
        assertTrue(line.contains("2023年5月"))
        assertFalse(line.contains("本月"))
        assertTrue(line.contains("增长"))

        // 放假只刷了几天：总额少一大截，但不比
        assertNull(CampusCardAnalysis.monthChangeInsight(listOf(june.copy(totalSpend = 60.0, spendDays = 3), may), LocalDate(2023, 6, 30), today))
    }

    @Test
    fun personaTags_coversExtremeAndRegularEaters() {
        val meals = mapOf(
            "早餐" to MealTimeStats(count = 2, totalAmount = 10.0, avgAmount = 5.0),
            "午餐" to MealTimeStats(count = 40, totalAmount = 480.0, avgAmount = 12.0),
            "夜宵" to MealTimeStats(count = 12, totalAmount = 120.0, avgAmount = 10.0),
        )
        val tags = CampusCardAnalysis.personaTags(
            food = mapOf("面食" to 400.0, "米饭" to 200.0),
            meals = meals,
            activeDays = 40,
            hourly = emptyList(),
            regular = "午餐" to "康桥苑一楼面食",
        ).map { it.second }
        assertEquals(listOf("面食派", "基本不吃早饭", "夜宵常客", "午餐常去「康桥苑一楼面」"), tags)

        // 口味分散、饭点集中的规律食客也有标签
        val hourly = List(24) { h -> mapOf(7 to 20, 8 to 10, 12 to 30, 11 to 6, 18 to 30, 17 to 4)[h] ?: 0 }
        val regular = CampusCardAnalysis.personaTags(
            food = mapOf("面食" to 28.0, "米饭" to 21.0, "汤粥" to 17.0, "自选" to 15.0, "小吃" to 13.0),
            meals = mapOf("早餐" to MealTimeStats(30, 210.0, 7.0), "午餐" to MealTimeStats(36, 480.0, 13.3)),
            activeDays = 40,
            hourly = hourly,
            regular = null,
        ).map { it.second }
        assertEquals(listOf("什么都吃", "早饭从不落", "饭点很准"), regular)

        // 样本太少：什么都不贴
        assertTrue(
            CampusCardAnalysis.personaTags(
                food = mapOf("面食" to 10.0, "米饭" to 10.0, "自选" to 10.0, "汤粥" to 10.0),
                meals = mapOf("夜宵" to MealTimeStats(2, 20.0, 10.0)),
                activeDays = 3, hourly = emptyList(), regular = null,
            ).isEmpty()
        )
    }
}
