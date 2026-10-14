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

/**
 * 加餐券（`egc.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [CouponSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/coupon/list` | [queryCoupons]（`?filter=` 四档 / `?page=` / `?size=`） |
 * | `GET /api/coupon/detail` | [getCouponDetail]（`?showCardId=`） |
 *
 * 契约 §5.5；`:server` 那一侧是 `CouponRoutes.kt`（薄包装 `:data` 的 `AppCouponSource`）。
 *
 * ## 两处**如实不画**（都在端口上留了开关）
 *
 * 1. **领券没有端点**：`CouponSource.activateCoupon`（`POST /app/voucher/activate`）真会改上游状态，
 *    是写路径 ⇒ serve 契约只端只读两格。所以 [canActivate] 报 `false`，屏上「可领取」那一档的券卡里
 *    **不画**「领取」按钮（那个方法也照旧抛错，见 [activateCoupon]）；
 * 2. **封面图取不到**：`imageUrl` 是 `:data` 拼好的绝对地址，但那张图要带 `egc` 的 `Authorization`
 *    才取得到 ⇒ 浏览器直接打开这个 URL 会拿到**登录页**，而契约里没有图片代理端点。所以
 *    [loadImage] 如实给 `null`（屏上退化成图标 —— 与「画不出图就不画图」同一条口径），
 *    而不是让页面去拉一张登录页当封面。
 *
 * ## 金额单位是**分**
 *
 * `amountFen` / `leftAmountFen` 就是 `:core` 模型的原字段名（除 100 是消费方的事，本层不做
 * 第二次换算，也不编两个 `amountYuan` 出来）。
 *
 * ## 默认形态（campus-api）
 *
 * 加餐券在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusCouponApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : CouponSource {

    /** 见类 KDoc：领券是写路径，契约 §5.5 不端 ⇒ 屏上不画那个按钮。 */
    override val canActivate: Boolean get() = false

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

    /**
     * 见类 KDoc：领券是**写路径**（真会改上游状态），serve 契约 §5.5 不端 ⇒ 这里照旧抛错。
     * 正常路径上它不会被调用：屏按 [canActivate] 把那个按钮整个不画。
     */
    override suspend fun activateCoupon(showCardId: String): Unit =
        throw UnsupportedOperationException(
            "serve 契约没有「领券」的端点：`CouponSource.activateCoupon`（POST /app/voucher/activate）真会" +
                "改上游状态，属写路径（docs/api-contract.md §5.5）—— 屏上那个按钮不画" +
                "（CouponSource.canActivate = false）",
        )

    /**
     * 见类 KDoc：封面图要带 `egc` 的 `Authorization` 才取得到，浏览器直接打这个 URL 会拿到登录页，
     * 而契约里没有图片代理端点 ⇒ 如实给 `null`（屏退化成图标），不去拉一张登录页。
     *
     * ⚠️ 这一发**不发请求**：拿不到就是拿不到，看不见的东西不假装。
     */
    override suspend fun loadImage(url: String): ByteArray? = null

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("加餐券取数", "$LIST_PATH + $DETAIL_PATH")
    }
}

/** 券列表（一页，按档筛）。 */
private const val LIST_PATH = "/api/coupon/list"

/** 单张券的详情。 */
private const val DETAIL_PATH = "/api/coupon/detail"
