package com.xjtu.toolbox.card

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TodaySummaryTest {

    private fun tx(time: String, amount: Double) =
        Transaction(time = time, merchant = "", amount = amount, balance = 0.0, type = "", description = "")

    @Test
    fun splitsTodaySpendingIntoMeals() {
        val today = LocalDate.of(2026, 9, 18)
        val s = todaySummaryOf(
            listOf(
                tx("2026-09-18 07:30:00", -5.0),   // 早餐
                tx("2026-09-18 12:10:00", -12.5),  // 午餐
                tx("2026-09-18 18:05:00", -15.0),  // 晚餐
                tx("2026-09-18 15:00:00", -3.0),   // 下午：只计总额
                tx("2026-09-18 12:30:00", 100.0),  // 充值：不算支出
                tx("2026-09-17 12:00:00", -20.0),  // 昨天
            ),
            today,
        )
        assertEquals(35.5, s.total, 1e-9)
        assertEquals(5.0, s.breakfast, 1e-9)
        assertEquals(12.5, s.lunch, 1e-9)
        assertEquals(15.0, s.dinner, 1e-9)
    }

    @Test
    fun dailyRateDividesByDaysWithSpending() {
        val today = LocalDate.of(2026, 9, 24)
        // 近 30 天里隔天刷一次，每次 ¥20：日均应是 20，而不是被空着的那些天摊成 10
        val spends = (0 until 14).map { tx(today.minusDays(it * 2L).toString() + " 12:00:00", -20.0) }
        val noise = listOf(
            Transaction("2026-09-20 09:00:00", "能源管理中心", -100.0, 0.0, "", "电费"),  // 水电不算
            tx("2026-09-21 10:00:00", 200.0),                                           // 充值不算
            tx("2026-07-01 12:00:00", -999.0),                                          // 30 天以外
        )
        assertEquals(20.0, dailySpendRate(spends + noise, today)!!, 1e-9)
        assertEquals(6, runwayDays(125.0, 20.0))

        // 不足 7 天有消费：样本太少，不给数
        assertEquals(null, dailySpendRate(spends.take(6), today))
        assertEquals(null, runwayDays(125.0, null))
    }

    @Test
    fun malformedTimeOnlyCountsTowardTotal() {
        val s = todaySummaryOf(listOf(tx("2026-09-18", -8.0)), LocalDate.of(2026, 9, 18))
        assertEquals(8.0, s.total, 1e-9)
        assertEquals(0.0, s.breakfast + s.lunch + s.dinner, 1e-9)
    }
}
