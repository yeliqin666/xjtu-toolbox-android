package com.xjtu.toolbox.coupon

/** 加餐券的取数端口：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。 */
interface CouponSource {

    /** 本端这一份取数能不能真领取。 */
    val canActivate: Boolean get() = true

    /** 按分类分页查券。分类的线上取值全在 [CouponFilter] 里（`status` / `count` / `expired`）。 */
    suspend fun queryCoupons(
        filter: CouponFilter,
        page: Int = 1,
        pageSize: Int = 20,
    ): CouponPage

    /** 券详情（`CouponApi` 只在领取时调它 —— 「领完弹一句这券的真名」）。 */
    suspend fun getCouponDetail(showCardId: String): CouponDetail

    /** 领取。成功后再说「已领取」，服务端会把 `status` 翻过去。 */
    suspend fun activateCoupon(showCardId: String)

    /** 券封面图的字节。 */
    suspend fun loadImage(url: String): ByteArray?
}