package com.xjtu.toolbox.venue

import android.content.Context
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [VenueSource] 在 Android 侧的实现 —— 包住原来的 `VenueApi`（okhttp 抓
 * `202.117.17.144:8080`）与 `VenueFavorites`（SharedPreferences）。
 *
 * **那两份实现一行未改**（`VenueApi` 只搬走了模型，`VenueFavorites` 连模型都没有，原样不动）。
 * 本类只做搬迁前写在 ViewModel 里的那件事：把每个取数调用包进 `Dispatchers.IO`
 * —— 原来的 VM 是 `withContext(Dispatchers.IO) { api.xxx() }`，现在 VM 不再替实现挑调度器
 *（见 [VenueSource] 的 KDoc），所以由本类自己包：**同一层、同一个调度器，行为不变**。
 *
 * 两个例外，都不是 IO：
 *  - [prepareOrder] 和 [paymentUrl] 是纯计算/纯拼串（API 里本来就没有网络），原来虽然被
 *    `withContext(Dispatchers.IO)` 包着，但换个调度器跑同一段纯 CPU 代码没有任何可观测差别；
 *    它们在端口上就是非挂起方法，VM 在自己的协程里直接调。
 *
 * 收藏走原来的 `VenueFavorites`：**文件 `venue_favorites`、键 `favorite_venue_ids`、
 * 值形态（字符串集合）全都逐字未动** ⇒ 老收藏还在（`:core` 的 `KeyValueStore` 没有集合这一档，
 * 所以这一件留在 :app，见 [VenueSource.favorites] 的 KDoc）。
 *
 * 验证码那两半（滑块控件与自动识别器）不在这里：它们是屏上的两个槽位，由 `AppNavHost` 注入
 *（见 `VenueScreen` 的 KDoc）——它们不碰网络，不属于本端口的职责。
 */
class AppVenueSource(
    site: SiteSession,
    context: Context,
) : VenueSource {

    private val api = VenueApi(site)
    private val favorites = VenueFavorites(context.applicationContext)

    /** Android 直连场馆站点，能下单、能取消（写路径一行未改）。 */
    override val canBook: Boolean get() = true

    /** 同上。 */
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

    override suspend fun favorites(): Set<Int> = favorites.favoriteIds.value

    override suspend fun toggleFavorite(venueId: Int): Boolean = favorites.toggleFavorite(venueId)
}
