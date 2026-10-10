package com.xjtu.toolbox.venue

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [VenueSource] 在 `:data` 里的实现（Android 与桌面侧共用）—— 包住原来的 [VenueApi]
 * （okhttp 抓 `202.117.17.144:8080`）与共享的 [VenueFavorites]。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 11 条真数据路由：桌面要自己登场馆站、自己取数），两大类各动一半：
 *
 * 1. **取数**（[VenueApi]）的类名与包路径一字未改，只是从 `:app/venue/` 挪到同包的 `:data/venue/`，
 *    替掉的只有 `android.util.Log`；
 * 2. **收藏**原来直接构造 `VenueFavorites(context)`（`SharedPreferences.getStringSet`，所以要一个
 *    `Context`），现在走 `:core` 的 [VenueFavorites]（**同一份文件、同一个键、同一个值类型**，
 *    Android 落到同一个 `venue_favorites` 上 ⇒ 老收藏不丢）。`Context` 因此不再跟着取数走，
 *    `:data` 里一行 `android.content` 都没有，桌面端与连 Web 端都不用再各写一份同语义代码。
 *
 * 本类还做搬迁前写在 ViewModel 里的那件事：把每个取数调用包进 `Dispatchers.IO` —— 原来的 VM 是
 * `withContext(Dispatchers.IO) { api.xxx() }`，现在 VM 不再替实现挑调度器（见 [VenueSource] 的 KDoc），
 * 所以由本类自己包：**同一层、同一个调度器，行为不变**。
 *
 * 两个例外，都不是 IO：
 *  - [prepareOrder] 和 [paymentUrl] 是纯计算/纯拼串（API 里本来就没有网络），原来虽然被
 *    `withContext(Dispatchers.IO)` 包着，但换个调度器跑同一段纯 CPU 代码没有任何可观测差别；
 *    它们在端口上就是非挂起方法，VM 在自己的协程里直接调。
 *
 * 验证码那两半（画滑块与自动识别）不在这里：它们是屏上的宿主槽位（[SlideCaptchaHost]），
 * 由宿主端注入（Android = `VenueSlideCaptchaHost`，桌面 = `DesktopSlideCaptchaHost`）——
 * 它们不碰网络，不属于本端口的职责。
 *
 * @param canBook 本端能不能下单。Android 直连场馆站、写路径一行未改 ⇒ true（默认值）；
 *   Stage B 之后桌面也有滑块宿主 ⇒ 桌面端同样传 **true**。按 [VenueSource] 的 KDoc 那条口径
 *   （**点了会失败的按钮，一个都不画**），将来若有端没有宿主，传 false 屏上就不画
 *   「确认预订」/「去支付」、时段格子不可勾选。取消订单不需要滑块（一次普通 POST），
 *   所以 [canCancel] 仍然是 true。
 */
class AppVenueSource(
    site: SiteSession,
    /** 见类 KDoc 的 `@param canBook`。 */
    override val canBook: Boolean = true,
) : VenueSource {

    private val api = VenueApi(site)

    /** 取消不需要滑块 ⇒ 本实现两条链都直连场馆站，这一件两端都做得到。 */
    override val canCancel: Boolean get() = true

    override val browserLoginUrl: String get() = VenueApi.BROWSER_LOGIN_URL

    override suspend fun venues(): List<Venue> = withContext(Dispatchers.IO) { api.fetchVenueList() }

    override suspend fun slots(serviceId: Int, date: String): List<AreaSlot> =
        withContext(Dispatchers.IO) { api.fetchAvailableSlots(serviceId, date) }

    override suspend fun orders(page: Int, pageSize: Int): OrderPage =
        withContext(Dispatchers.IO) { api.fetchOrders(page = page, pageSize = pageSize) }

    override fun paymentUrl(orderId: String): String = api.paymentUrl(orderId)

    override suspend fun captcha(serviceId: Int): CaptchaData =
        withContext(Dispatchers.IO) { api.generateCaptcha(serviceId) }

    override fun prepareOrder(serviceId: Int, selections: List<AreaSlot>): PendingOrder =
        api.prepareOrder(serviceId, selections)

    override suspend fun submitBooking(
        serviceId: Int,
        pendingOrder: PendingOrder,
        captchaId: String,
        sliderTrackJson: String,
    ): BookingResult = withContext(Dispatchers.IO) {
        api.submitBooking(
            serviceid = serviceId,
            pendingOrder = pendingOrder,
            captchaId = captchaId,
            sliderTrackJson = sliderTrackJson,
        )
    }

    override suspend fun cancelOrder(orderId: String): OrderActionResult =
        withContext(Dispatchers.IO) { api.cancelOrder(orderId) }

    override suspend fun favorites(): Set<Int> = VenueFavorites.all()

    override suspend fun toggleFavorite(venueId: Int): Boolean = VenueFavorites.toggle(venueId)
}
