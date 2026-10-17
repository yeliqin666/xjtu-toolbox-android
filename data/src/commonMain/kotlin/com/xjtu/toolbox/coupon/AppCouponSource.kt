package com.xjtu.toolbox.coupon

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** `:core` 的 [CouponSource] 在 `:data` 里的实现（Android 与桌面侧共用） */
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