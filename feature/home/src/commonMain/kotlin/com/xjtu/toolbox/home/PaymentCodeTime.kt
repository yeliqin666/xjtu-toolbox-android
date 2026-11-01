package com.xjtu.toolbox.home

import kotlinx.datetime.LocalTime

internal fun isMealPaymentTime(time: LocalTime): Boolean {
    val minute = time.hour * 60 + time.minute
    return minute in 390..510 || minute in 660..780 || minute in 1030..1150
}
