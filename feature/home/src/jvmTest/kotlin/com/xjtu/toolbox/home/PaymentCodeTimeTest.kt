package com.xjtu.toolbox.home

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentCodeTimeTest {
    @Test fun eachMealHasOneHourOnEitherSide() {
        listOf("06:30", "07:30", "08:30", "11:00", "12:00", "13:00", "17:10", "18:10", "19:10")
            .forEach { assertTrue(isMealPaymentTime(LocalTime.parse(it)), it) }
        listOf("06:29", "08:31", "10:59", "13:01", "17:09", "19:11", "00:00", "23:59")
            .forEach { assertFalse(isMealPaymentTime(LocalTime.parse(it)), it) }
    }
}
