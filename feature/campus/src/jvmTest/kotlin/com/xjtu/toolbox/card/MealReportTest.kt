package com.xjtu.toolbox.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class MealReportTest {

    private fun tx(time: String, merchant: String, amount: Double) =
        Transaction(time = time, merchant = merchant, amount = amount)

    private val sample = listOf(
        tx("2026-03-01 07:12:00", "康桥苑一楼包子", -4.0),
        tx("2026-03-01 12:05:00", "康桥苑二楼盖饭", -14.0),
        tx("2026-03-02 07:40:00", "康桥苑一楼包子", -5.0),
        tx("2026-03-02 22:30:00", "梧桐苑烧烤", -26.0),
        tx("2026-03-03 01:10:00", "瑞幸咖啡", -9.9),
        tx("2026-03-03 15:00:00", "超市", -30.0),
        tx("2026-03-04 09:00:00", "充值", 100.0),
    )
    private val isMeal: (Transaction) -> Boolean = { it.displayMerchant != "超市" }

    @Test
    fun `算出顿数、最常去的食堂、最早最晚`() {
        val r = MealReport.build(sample, isMeal)!!
        assertEquals(5, r.meals)
        assertEquals(58.9, r.mealSpend, 0.001)
        assertEquals(88.9, r.totalSpend, 0.001)
        assertEquals(LocalDate(2026, 3, 1), r.from)
        assertEquals(LocalDate(2026, 3, 3), r.to)
        assertEquals("康桥苑" to 3, r.canteen)
        assertEquals("康桥苑一楼包子" to 2, r.stall)
        assertEquals("2026-03-01 07:12:00", r.earliest?.time)
        // 凌晨一点那杯咖啡算前一天深夜，比 22:30 还晚
        assertEquals("2026-03-03 01:10:00", r.latest?.time)
        assertEquals(2, r.breakfastDays)
        assertEquals(2, r.lateNights)
        assertEquals(1, r.drinks)
        assertEquals(-26.0, r.priciest!!.amount, 0.001)
        assertEquals("早八战神", r.persona)
    }

    @Test
    fun `没吃过饭返回 null`() {
        assertNull(MealReport.build(listOf(tx("2026-03-03 15:00:00", "超市", -30.0)), isMeal))
    }
}
