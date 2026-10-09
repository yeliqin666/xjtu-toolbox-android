package com.xjtu.toolbox.library

/**
 * 图书馆座位那一屏的**数据形状**：从 `:app` 的 `LibraryApi.kt` / `LibraryPages.kt` 原样搬进
 * `:core`，两端共用同一份（形状、字段名、`@Serializable` 注解都逐字未改 —— 缓存里有旧数据，
 * 改形状就等于把老的座位收进去读不回来）。
 *
 * 这里只放「形状」与「判据」，不放任何取数：取数在 [LibrarySource] 的两个实现里
 *（Android = `:app` 的 `AppLibrarySource` 包住原来的 `LibraryApi`；Web = [com.xjtu.toolbox.core.net.CampusLibraryApi]）。
 */

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

/**
 * 「这条预约已经没用了」的状态文本。
 *
 * 状态是从预约页面 `预约状态：X` 里正则抓的**原文**，不是枚举，所以不可能列全
 * "有效"的那一侧；能穷举的只有失效这一侧。判定一律用"不在这个集合里就是活的"。
 *
 * 原先这份集合在 `LibraryPages.parseActiveBooking` 和 `LibraryScreen` 里各硬编码了一模一样的一份，
 * 改一处漏一处；后来收进 `LibraryApi` 这个伴生对象做唯一来源。随着屏与解析分处两个模块
 * （屏在 `:core`、解析还在 `:app`），它留在 `:app` 就够不着共享屏了 ⇒ 跟着模型一起搬进 `:core`。
 */
val INACTIVE_BOOKING_STATUSES = setOf(
    "已取消", "已完成", "已过期", "已失效", "已违约",
    "超时取消", "超时未入馆", "超时", "已离馆",
)

/**
 * 需要用户立刻动手、不做就会丢座位的操作。
 *
 * 判据取 `LibraryPages.classifyActionLabel` 归一化后的 label 而不是状态原文：label 只有五个固定值，
 * 稳定；状态文本随学校页面措辞变化。「中途离开」「取消预约」「我想换座」是常驻按钮，不构成催办。
 *
 * 搬进 `:core` 的理由同 [INACTIVE_BOOKING_STATUSES]（`:app` 的 `LibraryStatus` 也在用，同一个包直接解析得到）。
 */
val URGENT_BOOKING_ACTIONS = setOf("入馆签到", "中途返回")

/**
 * 座位号 → 区域码的兜底推断（兴庆的字母前缀表）。
 *
 * 只在「不知道座位在哪个区域」时兜底：屏幕选中了区域就用屏幕那个；扫码进来的用二维码上的区域码。
 * 雁塔、创新港的编号不在表里（所以那边推不出来，这也是为什么它只能当兜底）。
 * 原来是 `LibraryApi.Companion.guessAreaCode`，随 `:core` 的 ViewModel 一起搬过来。
 */
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
