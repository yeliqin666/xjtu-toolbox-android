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
    fun malformedTimeOnlyCountsTowardTotal() {
        val s = todaySummaryOf(listOf(tx("2026-09-18", -8.0)), LocalDate.of(2026, 9, 18))
        assertEquals(8.0, s.total, 1e-9)
        assertEquals(0.0, s.breakfast + s.lunch + s.dinner, 1e-9)
    }
}
