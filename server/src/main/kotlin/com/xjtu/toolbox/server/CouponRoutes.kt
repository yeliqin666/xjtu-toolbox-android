package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.coupon.AppCouponSource
import com.xjtu.toolbox.coupon.CouponDetail
import com.xjtu.toolbox.coupon.CouponFilter
import com.xjtu.toolbox.coupon.CouponRecord
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/coupon*` —— 加餐券的三个端点（`docs/api-contract.md` §5.5）：列表 + 详情两个只读，加上领券（写）。 */
internal fun Route.couponRoutes(session: ServeSession) {
    route(COUPON_SEGMENT) {

        // ── 券列表（一页，按档筛）────────────────────────────────────
        get(COUPON_LIST) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val filter = CouponFilter.entries.firstOrNull {
                it.name.equals(params[COUPON_PARAM_FILTER]?.trim(), ignoreCase = true)
            } ?: if (params[COUPON_PARAM_FILTER] == null) {
                // 省略 = `:core` 屏那一档（可使用），不是「全部」
                CouponFilter.USABLE
            } else {
                return@get call.respondBadRequestMessage(COUPON_FILTER_SHAPE_MESSAGE)
            }
            val page = params.positiveInt(COUPON_PARAM_PAGE, 1)
                ?: return@get call.respondBadRequestMessage("page 需要是正整数")
            val size = params.positiveInt(COUPON_PARAM_SIZE, COUPON_PAGE_SIZE)
                ?: return@get call.respondBadRequestMessage("size 需要是正整数")
            try {
                val result = AppCouponSource(session.couponSite())
                    .queryCoupons(filter = filter, page = page, pageSize = size)
                call.respond(
                    ApiEnvelope.ok(
                        CouponListData(
                            filter = filter.code,
                            page = page,
                            size = size,
                            total = result.total,
                            records = result.records.map { it.toDto() },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载加餐券")
            }
        }

        // ── 单张券的详情 ──────────────────────────────────────────────
        get(COUPON_DETAIL) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val showCardId = call.request.queryParameters[COUPON_PARAM_SHOW_CARD_ID]?.trim().orEmpty()
            if (showCardId.isEmpty()) {
                return@get call.respondBadRequestMessage("showCardId 必填（取自 /api/coupon/list 的 showCardId）")
            }
            try {
                val detail = AppCouponSource(session.couponSite()).getCouponDetail(showCardId)
                call.respond(ApiEnvelope.ok(detail.toDto()))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载加餐券详情")
            }
        }

        // ── 领券（写：真会改上游状态）────────────────────────────────────
        post(COUPON_ACTIVATE) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<CouponActivateRequest>()
                ?: return@post call.respondBadRequestMessage(COUPON_ACTIVATE_BODY_MESSAGE)
            val showCardId = body.showCardId.trim()
            if (showCardId.isEmpty()) {
                return@post call.respondBadRequestMessage(
                    "showCardId 必填（取自 /api/coupon/list 的 showCardId）",
                )
            }
            try {
                AppCouponSource(session.couponSite()).activateCoupon(showCardId)
            } catch (e: Exception) {
                return@post call.respondUpstreamFailure(e, "领取加餐券")
            }
            call.respond(ApiEnvelope.ok(CouponActionResult(ok = true, message = COUPON_ACTIVATED_MESSAGE)))
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/coupon/list` 的 `data`：分页形状（§4）+ 所用的档。 */
@Serializable
internal data class CouponListData(
    /** 所用的档（见 [CouponFilter.code]），请求没给就是 `usable`。 */
    val filter: String,
    val page: Int,
    val size: Int,
    /** 上游给的这一档的总数（`:data` 的 `CouponPage.total`）。 */
    val total: Int,
    val records: List<CouponRecordDto>,
)

/** 一张券（`CouponRecord` 的线上形状）。 */
@Serializable
internal data class CouponRecordDto(
    val sendId: String,
    val showCardId: String,
    val voucherName: String,
    val typeName: String,
    /** 面额，单位：分。 */
    val amountFen: Long,
    /** 剩余金额，单位：分。 */
    val leftAmountFen: Long,
    /** 剩余张数。 */
    val leftCount: Int,
    val startDate: String,
    val endDate: String,
    val imageUrl: String,
)

/** `GET /api/coupon/detail` 的 `data`（`CouponDetail` 的线上形状，十二格）。 */
@Serializable
internal data class CouponDetailDto(
    val showCardId: String,
    val voucherName: String,
    val title: String,
    val description: String,
    /** 面额，单位：分。 */
    val amountFen: Long,
    /** 剩余金额，单位：分。 */
    val leftAmountFen: Long,
    val startDate: String,
    val endDate: String,
    val batchId: String,
    val imageUrl: String,
    val closedPacketImageUrl: String,
    val openPacketImageUrl: String,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

/** 档 → 线上码。 */
private val CouponFilter.code: String get() = name.lowercase()

private fun CouponRecord.toDto() = CouponRecordDto(
    sendId = sendId,
    showCardId = showCardId,
    voucherName = voucherName,
    typeName = typeName,
    amountFen = amountFen,
    leftAmountFen = leftAmountFen,
    leftCount = leftCount,
    startDate = startDate,
    endDate = endDate,
    imageUrl = imageUrl,
)

private fun CouponDetail.toDto() = CouponDetailDto(
    showCardId = showCardId,
    voucherName = voucherName,
    title = title,
    description = description,
    amountFen = amountFen,
    leftAmountFen = leftAmountFen,
    startDate = startDate,
    endDate = endDate,
    batchId = batchId,
    imageUrl = imageUrl,
    closedPacketImageUrl = closedPacketImageUrl,
    openPacketImageUrl = openPacketImageUrl,
)


// ── 常量 ─────────────────────────────────────────────────────────────

/** 加餐券站点会话（key = `coupon`，`:data` 的 `CouponSession`）。 */
internal fun ServeSession.couponSite(): SiteSession = sessionManager.getSite(ServeSession.COUPON_SITE_KEY)

internal const val COUPON_SEGMENT = "coupon"
internal const val COUPON_LIST = "list"
internal const val COUPON_DETAIL = "detail"
internal const val COUPON_ACTIVATE = "activate"
internal const val COUPON_PARAM_FILTER = "filter"
internal const val COUPON_PARAM_PAGE = "page"
internal const val COUPON_PARAM_SIZE = "size"
internal const val COUPON_PARAM_SHOW_CARD_ID = "showCardId"

/** `:core` 的 `CouponViewModel` 拉列表用的页大小（20）—— serve 侧的默认值照它。 */
private const val COUPON_PAGE_SIZE = 20

/** `?filter=` 不认识时的中文短句（把合法取值列出来 —— 与 `/api/library/action` 的 400 同一个写法）。 */
private const val COUPON_FILTER_SHAPE_MESSAGE =
    "filter 只能是 available / usable / used_up / expired（省略 = usable）"

/** `POST /api/coupon/activate` 的请求体（一格 = `activateCoupon(showCardId)` 的参数）。 */
@Serializable
internal data class CouponActivateRequest(val showCardId: String = "")

/** `POST /api/coupon/activate` 的 `data`（与 §5.3.1 / §5.3.2 两个写端点同一个 `{ok, message}` 形状）。 */
@Serializable
internal data class CouponActionResult(val ok: Boolean, val message: String)

/** 领券成功的提示（给用户看的中文短句）。 */
internal const val COUPON_ACTIVATED_MESSAGE = "✓ 已领取"

internal const val COUPON_ACTIVATE_BODY_MESSAGE =
    "请求体需要 JSON：{\"showCardId\":\"…\"}（取自 /api/coupon/list 的 showCardId）"

