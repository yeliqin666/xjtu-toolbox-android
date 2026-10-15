package com.xjtu.toolbox.library

import kotlin.concurrent.Volatile

/**
 * 别处（屁岱的座位卡片、首页扫桌面二维码）想让图书馆页一打开就定位到某个区域。放一次、取一次。
 * 带 [Target.seatId] 时，区域加载完会弹出这个座位的预约确认（扫码的场景）。
 *
 * 这是**进程内的一个信箱**，不是取数：`:app` 的几处宿主（首页、屁岱、扫码）往里放，
 * 共享的 ViewModel 取。Web 端没有任何人放 ⇒ 永远取不到 null 以外的东西。
 */
object LibraryFocus {
    data class Target(val campusId: String, val areaCode: String, val seatId: String? = null)

    @Volatile private var pending: Target? = null

    fun request(target: Target) { pending = target }

    fun take(): Target? = pending.also { pending = null }
}
