package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.coupon.CouponDetail
import com.xjtu.toolbox.coupon.CouponFilter
import com.xjtu.toolbox.coupon.CouponPage
import com.xjtu.toolbox.coupon.CouponRecord
import com.xjtu.toolbox.coupon.CouponSource
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 加餐券（`egc.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [CouponSource]。 */
class CampusCouponApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : CouponSource {

    /** 见类 KDoc：领券端了（`POST /api/coupon/activate`，契约 §5.5）⇒ 屏上那个按钮照旧画。 */
    override val canActivate: Boolean get() = true

    override suspend fun queryCoupons(filter: CouponFilter, page: Int, pageSize: Int): CouponPage {
        requireServe()
        val data = client.serveData(
            "加载加餐券",
            baseUrl,
            LIST_PATH,
            // 参数只认一套 ASCII 写法（枚举名小写）：`available` / `usable` / `used_up` / `expired`。
            // 显式带上默认档（usable）也不含糊 —— 省略时的默认档由服务端定，与 `:core` 的初始档同一个。
            listOf(
                "filter" to filter.name.lowercase(),
                "page" to page.toString(),
                "size" to pageSize.toString(),
            ),
        )
        return CouponPage(
            records = data.arr("records").orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val showCardId = row["showCardId"].safeString().trim()
                if (showCardId.isEmpty()) return@mapNotNull null
                CouponRecord(
                    sendId = row["sendId"].safeString(),
                    showCardId = showCardId,
                    voucherName = row["voucherName"].safeString(),
                    typeName = row["typeName"].safeString(),
                    amountFen = row["amountFen"].safeLong(),
                    leftAmountFen = row["leftAmountFen"].safeLong(),
                    leftCount = row["leftCount"].safeInt(),
                    startDate = row["startDate"].safeString(),
                    endDate = row["endDate"].safeString(),
                    imageUrl = row["imageUrl"].safeString(),
                )
            },
            total = data["total"].safeInt(),
        )
    }

    override suspend fun getCouponDetail(showCardId: String): CouponDetail {
        requireServe()
        val data = client.serveData("加载加餐券详情", baseUrl, DETAIL_PATH, listOf("showCardId" to showCardId))
        // `showCardId` 用**请求给的那个**（§5.5：响应里那一格就是请求原样回）
        return CouponDetail(
            showCardId = showCardId,
            voucherName = data["voucherName"].safeString(),
            title = data["title"].safeString(),
            description = data["description"].safeString(),
            amountFen = data["amountFen"].safeLong(),
            leftAmountFen = data["leftAmountFen"].safeLong(),
            startDate = data["startDate"].safeString(),
            endDate = data["endDate"].safeString(),
            batchId = data["batchId"].safeString(),
            imageUrl = data["imageUrl"].safeString(),
            closedPacketImageUrl = data["closedPacketImageUrl"].safeString(),
            openPacketImageUrl = data["openPacketImageUrl"].safeString(),
        )
    }

    /** 见类 KDoc：领券的端点是 `POST /api/coupon/activate`，body 只有一格 `{showCardId}`。 */
    override suspend fun activateCoupon(showCardId: String) {
        requireServe()
        val outcome = client.serveAction(
            baseUrl,
            ACTIVATE_PATH,
            buildJsonObject { put("showCardId", showCardId) }.toString(),
        )
        if (!outcome.ok) error("领取：${outcome.message}")
    }

    /** 封面图要带 `egc` 的 `Authorization`，浏览器直连只会拿到登录页 ⇒ 给 `null`。 */
    override suspend fun loadImage(url: String): ByteArray? = null

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("加餐券取数", "$LIST_PATH + $DETAIL_PATH + $ACTIVATE_PATH")
    }
}

/** 券列表（一页，按档筛）。 */
private const val LIST_PATH = "/api/coupon/list"

/** 单张券的详情。 */
private const val DETAIL_PATH = "/api/coupon/detail"

/** 领券（**写**：`POST /api/coupon/activate`，body 只有一格 `showCardId`）。 */
private const val ACTIVATE_PATH = "/api/coupon/activate"
