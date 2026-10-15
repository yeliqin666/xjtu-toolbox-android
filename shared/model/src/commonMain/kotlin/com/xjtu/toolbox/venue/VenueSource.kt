package com.xjtu.toolbox.venue

/** 体育场馆的取数 + 落盘端口：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。 */
interface VenueSource {

    /** 本端能不能下单。campus-api 的场馆模块只读 ⇒ Web 是 false（见接口 KDoc）。 */
    val canBook: Boolean

    /** 本端能不能取消订单。理由同上（同一个只读模块）。 */
    val canCancel: Boolean

    /** 支付前要先去这个站点的登录页过一遍（`:app` 的内置浏览器带着 App 的统一认证 cookie，一般免输密码）。 */
    val browserLoginUrl: String

    // ─── 读（两端都有）─────────────────────────────────────────

    /** 场馆列表。`:app` 是分页拉到不足一页为止（每页 8 条）；实现方自己决定拉几页。 */
    suspend fun venues(): List<Venue>

    /** [serviceId] 这个场馆 [date]（`YYYY-MM-DD`）这一天的时段。 */
    suspend fun slots(serviceId: Int, date: String): List<AreaSlot>

    /** 我的订单，[page] 从 1 开始。 */
    suspend fun orders(page: Int, pageSize: Int): OrderPage

    /** 支付页 URL。 */
    fun paymentUrl(orderId: String): String

    // ─── 写（[canBook] / [canCancel] 为 false 时抛）──────────────

    /** 滑块验证码。 */
    suspend fun captcha(serviceId: Int): CaptchaData

    /** 把选中的时段拼成待提交订单参数。 */
    fun prepareOrder(serviceId: Int, selections: List<AreaSlot>): PendingOrder

    /** 提交预订。 */
    suspend fun submitBooking(
        serviceId: Int,
        pendingOrder: PendingOrder,
        captchaId: String,
        sliderTrackJson: String,
    ): BookingResult

    /** 取消订单。 */
    suspend fun cancelOrder(orderId: String): OrderActionResult

    // ─── 收藏（本端的落盘）─────────────────────────────────────

    /** 已收藏的场馆 id。 */
    suspend fun favorites(): Set<Int>

    /** 切换收藏，返回切换后的状态（与 `VenueFavorites.toggle` 同一个返回值语义）。 */
    suspend fun toggleFavorite(venueId: Int): Boolean
}
