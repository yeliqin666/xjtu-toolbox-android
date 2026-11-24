package com.xjtu.toolbox.library

import android.content.Context
import com.xjtu.toolbox.account.AccountContext

/**
 * 最近一次查到的「我的预约」里，**桌面卡片要用的那几项**。
 *
 * 为什么必须落盘：智能卡片跑在小组件进程里，而且随时可能被系统在冷进程里拉起 ——
 * 那时既没有内存里的 `MyBookingInfo`，也**绝不能**为了画一行字去登录图书馆站点
 * （见 `SmartWidgetProvider` 类注释）。所以「我约了哪个座、还能点哪几个按钮」
 * 只能取自上一次联网查到、已经写在本机的那一份。
 *
 * 只存**动作名**（`actionUrls.keys`），不存 URL：URL 是学校里一次性的句柄，存了就会过期，
 * 动作键执行时由 `LibraryActionWorker` 现查拿最新的那个（§5.3 的硬要求）。
 *
 * 与 [LibraryStatus] 里那份 `desk_booked_seat` 分开：那个只回答「是不是在座位前扫码约的」，
 * 而卡片要的是座位号、区域、状态、可用动作这一整组。
 */
data class LibrarySeatFact(
    val seat: String,
    val area: String,
    val status: String,
    /** 最近一次查到的可用动作名（`入馆签到` / `中途离开` / `中途返回` / `取消预约`）。 */
    val actions: List<String>,
    /** 这份事实是什么时候查到的（epoch millis）：卡片据此写「上次查到 HH:mm」。 */
    val at: Long,
) {
    /** 座位事实里有没有这个动作；动作键只能执行这里真有的（§5.3 ⚠️）。 */
    fun has(action: String): Boolean = action in actions

    /** 还占着一个座位（已取消/已完成/已过期这些都不算）。 */
    val active: Boolean get() = seat.isNotBlank() && status !in INACTIVE_BOOKING_STATUSES
}

/**
 * [LibrarySeatFact] 在 Android 上的落盘。写方是 [LibraryStatus.publish]（首页刷新与图书馆页
 * 哪边先查到就哪边更新），读方是智能卡片。
 *
 * 按账号分：别的账号扫码约的座位不能顶到当前账号头上（与 [LibraryStatus] 同一套后缀）。
 */
object LibrarySeatStore {
    private const val PREFS = "library_seat_fact"

    private fun prefs(context: Context) =
        context.getSharedPreferences("$PREFS${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)

    fun save(context: Context, booking: MyBookingInfo?, now: Long = System.currentTimeMillis()) {
        val seat = booking?.seatId?.takeIf { it.isNotBlank() }
        val p = prefs(context)
        if (seat == null) {
            // 没有预约（或刚签到失败被学校撤了）：整份作废，别让卡片显示一个不存在的座
            p.edit().clear().apply()
            return
        }
        p.edit()
            .putString("seat", seat)
            .putString("area", booking.area.orEmpty())
            .putString("status", booking.statusText.orEmpty())
            .putString("actions", booking.actionUrls.keys.joinToString("\u0001"))
            .putLong("at", now)
            .apply()
    }

    /** 拿不到（没约过座 / 换过账号 / 清过数据）返回 null——卡片那一档就不画，不显示占位符。 */
    fun load(context: Context): LibrarySeatFact? {
        val p = prefs(context)
        val seat = p.getString("seat", null)?.takeIf { it.isNotBlank() } ?: return null
        val at = p.getLong("at", 0L)
        if (at <= 0L) return null
        return LibrarySeatFact(
            seat = seat,
            area = p.getString("area", null).orEmpty(),
            status = p.getString("status", null).orEmpty(),
            actions = p.getString("actions", null).orEmpty()
                .split('\u0001')
                .filter { it.isNotBlank() },
            at = at,
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
