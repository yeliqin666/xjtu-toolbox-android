package com.xjtu.toolbox.venue

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:core` 的 [VenueSource] 在 `:data` 里的实现（Android 与桌面侧共用） */
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
