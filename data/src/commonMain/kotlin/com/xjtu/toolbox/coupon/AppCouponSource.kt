package com.xjtu.toolbox.coupon

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * `:core` 的 [CouponSource] 在 `:data` 里的实现（Android 与桌面侧共用）—— 包住原来的
 * [CouponApi]（okhttp 抓 `egc.xjtu.edu.cn` 加餐券系统）。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 15 条真数据路由：桌面要自己登 `egc` 站、自己查券与领券），
 * 两件事各动一半：
 *
 * 1. **取数**（[CouponApi]）的类名与包路径一字未改，只是从 `:app/coupon/` 挪到同包的
 *    `:data/coupon/`；它本来就没有 `android.util.Log`，所以连 Log 都不用动；
 * 2. **调度器**：搬迁前是 ViewModel 每次调用自己 `withContext(Dispatchers.IO)`，现在 VM
 *    不再替实现挑调度器（见 [CouponSource] 的 KDoc），所以由本类自己包 ——
 *    **同一层、同一个调度器，行为不变**。
 *
 * 会话仍然是 `:data` 的 [SiteSession]（`coupon` 站点，见 [com.xjtu.toolbox.auth.CouponSession]
 * 与 [com.xjtu.toolbox.auth.CouponLogin]）：`Authorization` 头由 `CouponSession.decorateRequest`
 * 自动带上，与搬迁前逐字相同。
 *
 * **券封面图**原来在屏上直接用 `site.client` 发 GET（BitmapFactory 解码那一半留在 `:core` 的
 * 图片缝），现在也收进本实现 —— 屏不再摸 `SiteSession`。取不到图返回 null，屏退化成图标。
 */
class AppCouponSource(private val site: SiteSession) : CouponSource {

    private val api = CouponApi(site)

    override suspend fun queryCoupons(
        filter: CouponFilter,
        page: Int,
        pageSize: Int,
    ): CouponPage = withContext(Dispatchers.IO) { api.queryCoupons(filter, page, pageSize) }

    override suspend fun getCouponDetail(showCardId: String): CouponDetail =
        withContext(Dispatchers.IO) { api.getCouponDetail(showCardId) }

    override suspend fun activateCoupon(showCardId: String): Unit =
        withContext(Dispatchers.IO) { api.activateCoupon(showCardId) }

    override suspend fun loadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val response = runCatching<okhttp3.Response> {
            site.client.newCall(Request.Builder().url(url).get().build()).execute()
        }.getOrNull() ?: return@withContext null
        try {
            if (!response.isSuccessful) null else response.body.bytes()
        } finally {
            response.close()
        }
    }
}