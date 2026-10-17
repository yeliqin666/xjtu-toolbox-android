package com.xjtu.toolbox.server

import com.xjtu.toolbox.pay.PaymentCodeApi
import com.xjtu.toolbox.pay.PaymentVoucher
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/pay*` —— 付款码（`docs/api-contract.md` §5.7）的两个只读端点：拿码 + 可用加餐券列表。 */
internal fun Route.payRoutes(session: ServeSession) {
    route(PAY_SEGMENT) {

        // ── 拿码（只读；付款码借校园卡的会话）─────────────────────────
        get(PAY_CODE) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val source = session.paySource()
                // `:core` 那条端口的「进门第一枪」：本地判据（会话里有没有那张 JWT），不发请求
                source.authenticate()
                call.respond(ApiEnvelope.ok(PayCodeData(barCode = source.getBarCode())))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "获取付款码")
            }
        }

        // ── 可用加餐券（只读；勾选同步是写路径，不端）─────────────────
        get(PAY_VOUCHERS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val vouchers = session.paySource().getVouchers()
                call.respond(
                    ApiEnvelope.ok(
                        PayVouchersData(count = vouchers.size, vouchers = vouchers.map { it.toDto() }),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "获取加餐券")
            }
        }

        // ── 同步「这一轮用哪几张加餐券」（写：真会改上游状态）────────
        post(PAY_VOUCHERS_SELECT) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<PayVoucherSelectRequest>()
                ?: return@post call.respondBadRequestMessage(PAY_VOUCHER_SELECT_BODY_MESSAGE)
            // 空表照发（取消全部勾选就是空表）；⚠️ 夹具把空表当 400，这条没有夹具用例。
            val ids = body.showCardIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            try {
                session.paySource().updateVoucherStatus(ids)
            } catch (e: Exception) {
                return@post call.respondUpstreamFailure(e, "同步加餐券勾选")
            }
            call.respond(ApiEnvelope.ok(PayActionResult(ok = true, message = PAY_VOUCHERS_SELECTED_MESSAGE)))
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/pay/code` 的 `data`。 */
@Serializable
internal data class PayCodeData(
    /** 上游 `data.barCodeVo.barcode[0]`：20 位十进制的付款码号（`2026-10-14` 实测形状）。 */
    val barCode: String,
)

/** `GET /api/pay/vouchers` 的 `data`。 */
@Serializable
internal data class PayVouchersData(
    val count: Int,
    val vouchers: List<PayVoucherDto>,
)

/** 一张可用加餐券（屏真读的那五格；`serverFlag` 刻意不投影 —— 见文件头 KDoc）。 */
@Serializable
internal data class PayVoucherDto(
    val showCardId: String,
    val voucherName: String,
    val typeName: String,
    /** **分**（与 §5.5 的 `/api/coupon*` 同一个单位；除 100 是消费方的事）。 */
    val amountFen: Long,
    val endDate: String,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun PaymentVoucher.toDto() = PayVoucherDto(
    showCardId = showCardId,
    voucherName = voucherName,
    typeName = typeName,
    amountFen = amountFen,
    endDate = endDate,
)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 付款码取数实例：每次请求现建。 */
internal fun ServeSession.paySource(): PaymentCodeApi = PaymentCodeApi(cardSite())

/** `POST /api/pay/vouchers/select` 的请求体（一格 = `updateVoucherStatus(selectedIds)` 的参数）。 */
@Serializable
internal data class PayVoucherSelectRequest(
    /** 选中的券（`showCardId` 那一族）。 */
    val showCardIds: List<String>,
)

/** `POST /api/pay/vouchers/select` 的 `data`（与 §5.3.1 / §5.3.2 两个写端点同一个 `{ok, message}` 形状）。 */
@Serializable
internal data class PayActionResult(val ok: Boolean, val message: String)

/** 勾选同步成功的提示（给用户看的中文短句）。 */
internal const val PAY_VOUCHERS_SELECTED_MESSAGE = "✓ 已同步加餐券勾选"

internal const val PAY_VOUCHER_SELECT_BODY_MESSAGE =
    "请求体需要 JSON：{\"showCardIds\":[\"…\"]}（取自 /api/pay/vouchers 的 showCardId）"

internal const val PAY_SEGMENT = "pay"
internal const val PAY_CODE = "code"
internal const val PAY_VOUCHERS = "vouchers"
internal const val PAY_VOUCHERS_SELECT = "vouchers/select"
