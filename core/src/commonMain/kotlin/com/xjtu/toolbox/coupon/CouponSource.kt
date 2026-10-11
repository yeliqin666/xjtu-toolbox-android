package com.xjtu.toolbox.coupon

/**
 * 加餐券的**取数端口**：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。
 *
 * 四个方法就是 [CouponApi]（`:data`，Android 与桌面共用同一份实现）那四个动作 —— 名称、
 * 参数、返回形状与搬迁前逐字相同，所以「按分类分页 → 查详情 → 领取」的编排（[CouponViewModel]）
 * 在两端是同一条代码路径。
 *
 * ## 为什么 IO 调度不在端口上
 *
 * `:app` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`；Web 走挂起接口，
 * 不需要。VM 只在自己的可取消作用域里调用（与 `VenueSource` / `CampusCardSource` 同一条约定）。
 *
 * ## 两端各自的实现
 *
 * | 端 | 实现 | 备注 |
 * |---|---|---|
 * | Android / 桌面 | `:data` 的 `AppCouponSource`（包住 `CouponApi`） | 走自己的站点会话（`coupon`） |
 * | Web | 还没有 | 屏搬好了，取数要等 campus-api 或 `:server` 的 `/api` 端点给出（如实记在 `:web` 的未搬清单里） |
 */
interface CouponSource {

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

    /**
     * 券封面图的字节。`CouponRecord.imageUrl` 是 URL，取图走哪条网络由**实现方**决定
     *（这里的实现 = 站点的会话客户端）；**null = 取不到图**，屏上退化成图标（与「点了会
     * 失败的按钮，一个都不画」同一条口径，只是这边是「画不出图就不画图」）。
     */
    suspend fun loadImage(url: String): ByteArray?
}