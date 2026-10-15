package com.xjtu.toolbox.library

/** 图书馆座位那一屏的数据形状。 */

// ══════ 数据类 ══════

data class SeatInfo(
    val seatId: String,
    val available: Boolean
)

/** 区域统计：空座/总数 */
data class AreaStats(val available: Int, val total: Int) {
    val isOpen get() = total > 0
    val label get() = "${available}/${total}"
}

/** 预约结果（含失败原因） */
data class BookResult(
    val success: Boolean,
    val message: String,
    val finalUrl: String = ""
)

/** "我的预约"信息 */
data class MyBookingInfo(
    val seatId: String?,
    val area: String?,
    val statusText: String?,
    val actionUrls: Map<String, String>
)

sealed class SeatResult {
    data class Success(
        val seats: List<SeatInfo>,
        val areaStatsMap: Map<String, AreaStats> = emptyMap()
    ) : SeatResult()
    data class AuthError(val message: String, val htmlPreview: String = "") : SeatResult()
    data class Error(val message: String) : SeatResult()
}

/** 平面图上的一个座位：矩形是平面图像素坐标。 */
@kotlinx.serialization.Serializable
data class PlanSeat(
    val seatId: String = "",
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val status: Int = 0,
) {
    val available: Boolean get() = status == FREE
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    companion object {
        const val FREE = 2
        const val BOOKED = 0
        const val INSIDE = 1
        const val LEAVE = 3
        const val CANCELLED = -1
    }
}

data class SeatLayout(val seats: List<PlanSeat>)

// ══════ 判据（屏与解析共用，所以必须只有一份）══════

/** 「这条预约已经没用了」的状态文本。 */
val INACTIVE_BOOKING_STATUSES = setOf(
    "已取消", "已完成", "已过期", "已失效", "已违约",
    "超时取消", "超时未入馆", "超时", "已离馆",
)

/** 需要用户立刻动手、不做就会丢座位的操作。 */
val URGENT_BOOKING_ACTIONS = setOf("入馆签到", "中途返回")

/** 座位号 → 区域码的兜底推断（兴庆的字母前缀表）。 */
fun guessAreaCode(seatId: String): String? {
    val prefix = seatId.firstOrNull()?.uppercaseChar() ?: return null
    return when (prefix) {
        'A', 'B' -> "north2elian"
        'D', 'E' -> "north2east"
        'C' -> "south2"
        'N' -> "north2west"
        'Y' -> "west3B"
        'P' -> "eastnorthda"
        'X' -> "east3A"
        'K', 'L', 'M' -> "north4west"
        'J' -> "north4middle"
        'H', 'F', 'G' -> "north4east"
        'Q' -> "north4southwest"
        'T' -> "north4southeast"
        else -> null
    }
}
