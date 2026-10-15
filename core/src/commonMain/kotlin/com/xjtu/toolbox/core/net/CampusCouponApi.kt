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

/**
 * 加餐券（`egc.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [CouponSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/coupon/list` | [queryCoupons]（`?filter=` 四档 / `?page=` / `?size=`） |
 * | `GET /api/coupon/detail` | [getCouponDetail]（`?showCardId=`） |
 * | `POST /api/coupon/activate` | [activateCoupon]（**写**，body `{showCardId}`） |
 *
 * 契约 §5.5；`:server` 那一侧是 `CouponRoutes.kt`（薄包装 `:data` 的 `AppCouponSource`）。
 *
 * ## 领券：✅ 已经端了（2026-10-15，批③-b / D7 ③）
 *
 * `POST /api/coupon/activate`（body 只有一格 `{showCardId}`）—— [canActivate] 不再覆写，
 * 就是基类那个 `true`（Android / 桌面 / Web 三端同一个值），屏上「可领取」那一档的券卡里
 * **照旧画**「领取」按钮。
 *
 * ## 一处**如实不画**（在端口上留了开关）
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

    /**
     * 见类 KDoc：领券端了（`POST /api/coupon/activate`，契约 §5.5）⇒ 屏上那个按钮照旧画。
     *
     * 与 `:data` / 桌面那一份**逐字相同**的默认值（`CouponSource.canActivate` 默认 `true`）：
     * 本类不再覆写它，否则这一端就会自己把自己画成只读 —— 而端点是有的。
     */
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

    /**
     * 见类 KDoc：领券的端点是 `POST /api/coupon/activate`，body 只有一格 `{showCardId}`。
     *
     * 失败（`400` 参数 / `401` 没会话 / `502` 上游拒或会话失效）一律**抛错** ——
     * `CouponViewModel.receive` 接的是 `FriendlyError.of(e, "领取")` 那句，不能当成功静默过去。
     */
    override suspend fun activateCoupon(showCardId: String) {
        requireServe()
        val outcome = client.serveAction(
            baseUrl,
            ACTIVATE_PATH,
            buildJsonObject { put("showCardId", showCardId) }.toString(),
        )
        if (!outcome.ok) error("领取：${outcome.message}")
    }

    /**
     * 见类 KDoc：封面图要带 `egc` 的 `Authorization` 才取得到，浏览器直接打这个 URL 会拿到登录页，
     * 而契约里没有图片代理端点 ⇒ 如实给 `null`（屏退化成图标），不去拉一张登录页。
     * ⚠️ 这一发**不发请求**：拿不到就是拿不到，看不见的东西不假装。
     */
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
