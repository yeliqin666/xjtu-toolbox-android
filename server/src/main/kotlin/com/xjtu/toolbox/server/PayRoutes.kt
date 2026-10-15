package com.xjtu.toolbox.server

import com.xjtu.toolbox.pay.PaymentCodeApi
import com.xjtu.toolbox.pay.PaymentVoucher
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/pay*` —— 付款码（`docs/api-contract.md` §5.7）的**两个只读端点**：拿码 + 可用加餐券列表。
 * 走 `:data` 的 [PaymentCodeApi]（它本身就是 `:core` 的 `PaymentCodeSource` 实现，
 * ncard 的取数口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/pay/code` | `PaymentCodeSource.authenticate()` + `getBarCode()` |
 * | `GET /api/pay/vouchers` | `PaymentCodeSource.getVouchers()` |
 *
 * ## 「拿码」不是「付钱」（这一族最要紧的一条）
 *
 * 上游那个 App 从头到尾**只显示**付款码，钱是商户扫了那串号之后在学校那边扣的 ——
 * 客户端里根本没有「支付 / 扣款」这一档可端（`:data` 的 [PaymentCodeApi] 上也没有那种方法）。
 * 所以这一族端的是**一次性的取码**：
 * - `getBarCode()` 是 `GET .../authCode/batchGetBarCodeGet`（读一发，不改任何状态）；
 * - `authenticate()` 只是**本地**看一眼会话里有没有那张校园卡 JWT（不发请求）。
 *
 * ✅ **勾选同步已经端了（2026-10-15，批③-b / D7 ④）**：`POST /api/pay/vouchers/select` ——
 * body 只有一格 `{showCardIds:[…]}`，走的就是 `updateVoucherStatus(ids)`（`POST .../vouchers/
 * updateVoucherStatus`）。成功回 `{ok, message}`，屏上那几行券**照旧可勾选**。
 *
 * ## ⚠️ 「支付」这一档不是「本轮没端」，是**上游本来就没有**（D7 ④ 的结论）
 *
 * 上游那个 App 从头到尾**只显示**付款码，钱是商户扫了那串号之后**在学校那边扣的** ——
 * `:data` 的 [PaymentCodeApi] 上**一个扣款/支付方法都没有**（`grep` 可证：它只有
 * `authenticate` / `getBarCode` / `getVouchers` / `updateVoucherStatus` 四个）。所以这一族
 * **没有**「付款码支付」可端 —— 要端就只能自己造一个：先自己实现扣款协议，再把它接上，
 * 而那是**新功能**（且要真实的支付通道与合规），不是这次重构（§16）的事。
 * 这一族**唯一**的写就是上面那条勾选同步。
 *
 * ## 加餐券勾选的**落盘**不在这一层
 *
 * `:core` 的 `PaymentSelectionStore`（宿主那份键值存储）仍然不端：serve 这一层没有宿主磁盘。
 * 浏览器自己那一份（localStorage）由 `:web` 接 —— 两边各管各的：**上游那份勾选**走这个端点，
 * **本机记住的勾选**走本地存储。
 * ## ⚠️ 那一串码本身就是钱
 *
 * `/api/pay/code` 的 `barCode` 是**能付钱的一次性凭据**（登录者本人的）。它不在 §5.2 红线的
 * 四个字段里（学号 / 姓名 / 教师名 / 手机号 —— 这一族一个都不投影），但它的敏感度不比它们低：
 * serve 的默认姿势（只监听回环 + 令牌闸门）是这条端点的**前提**，不是可选项。
 * 对外暴露（`--host 0.0.0.0`）时必须自己加反代 + 鉴权 —— 与 §3.3 那条同源。
 *
 * ## 会话
 *
 * 付款码借的是**校园卡那个会话**（`:app` 的 `AppRoute.PaymentCode.loginType = CAMPUS_CARD`）：
 * 业务请求带的是校园卡登录时存的 JWT（`site.localToken["access_token"]`，见
 * [PaymentCodeApi.authRequest]）⇒ 本文件复用 [ServeSession.cardSite]（`CardRoutes.kt` 里
 * 那一个），不新注册站点。
 *
 * ## 逐字段映射
 *
 * - `code`：`barCode` ← `getBarCode()` 的返回值 —— 上游 `data.barCodeVo.barcode[0]`（**字符串**，
 *   20 位十进制），`:data` 已经把「`success != true`」「少 `data` / `barCodeVo`」「数组为空」
 *   三档都判过了，并对 `barcode` 是数组这件事取了第 0 个（屏画条码 / 二维码用的就是这一串）；
 * - `vouchers`：`count` + 五个字段的投影 —— `showCardId`（勾选与后续动作的句柄）、
 *   `voucherName`、`typeName`、`amountFen`（**分**，与 §5.5 加餐券那条同一条单位口径）、
 *   `endDate`（上游原文的 `2026-12-31`，不是 ISO-8601 带时区的时间 —— 它本来就是「到哪天为止」）。
 *   ⚠️ `PaymentVoucher.serverFlag`（上游 `flag`）**不投影**：`:core` 的 `PaymentCodeScreen`
 *   一个地方都没读它，端出去只会多一处要跟着上游漂的口径。
 *
 * ## 错误码
 *
 * - `401` 令牌对但没会话（「请先登录：POST /api/session/login」）；
 * - `502` 上游 / 网络故障，**含「校园卡未登录」那一档**：`:data` 抛的是
 *   `RuntimeException("校园卡未登录，无法获取付款码")`，`FriendlyError` 按「应用自己写的中文短句」
 *   原样交回（与 §5.5 宿舍电费那条同口径：会话那一档归 502、message 说明白）；
 * - **没有 `400`**：两个端点都不收参数。
 *
 * @param session 会话装配；校园卡站点从它那里取（付款码与校园卡是同一个站点会话）。
 */
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
            // 空表**照发**：`:data` 的 `updateVoucherStatus` 不替上游判空（取消全部勾选就是空表）——
            // ⚠️ 夹具把空表当 400（它按自己读到的上游形状判），所以这条口径**没有夹具用例**，
            // 如实记在契约 §5.7 里而不是编一个。
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

/**
 * `GET /api/pay/code` 的 `data`。
 *
 * ⚠️ 这一格是**能付钱的一次性凭据**（登录者本人）⇒ 只该走回环 + 令牌闸门（见文件头 KDoc）。
 */
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

/**
 * 付款码取数实例：每次请求现建（与 `cardSource()` 同一条口径）。
 *
 * ⚠️ 站点是**校园卡**那一个（[ServeSession.cardSite]）：付款码不新注册站点，它借的就是
 * 校园卡登录时存下的那张 JWT。
 */
internal fun ServeSession.paySource(): PaymentCodeApi = PaymentCodeApi(cardSite())

/** `POST /api/pay/vouchers/select` 的请求体（一格 = `updateVoucherStatus(selectedIds)` 的参数）。 */
@Serializable
internal data class PayVoucherSelectRequest(
    /**
     * 选中的券（`showCardId` 那一族）。
     *
     * ⚠️ **刻意没有默认值**：这一格缺了与「传了个空表」在写端点上**不是一回事** —— 前者是
     * 调用方发错了（比如写成了 `ids`），后者是「取消全部勾选」。有默认值的话两种会被
     * 当成同一件事，而后果是「一次笔误把用户的勾选全清掉」。所以缺这一格一律 `400`。
     */
    val showCardIds: List<String>,
)

/**
 * `POST /api/pay/vouchers/select` 的 `data`（与 §5.3.1 / §5.3.2 两个写端点同一个 `{ok, message}` 形状）。
 *
 * `:core` 的 `PaymentCodeSource.updateVoucherStatus` 返回 `Unit`（一个格都不读），所以 `message` 是给
 * curl / 排障看的 —— 屏上那句「已选 N 张」是 `PaymentCodeScreen` 自己按本地勾选算的。
 */
@Serializable
internal data class PayActionResult(val ok: Boolean, val message: String)

/** 勾选同步成功那一句（写端点的 `message` 口径：能直接给用户看的中文短句）。 */
internal const val PAY_VOUCHERS_SELECTED_MESSAGE = "✓ 已同步加餐券勾选"

internal const val PAY_VOUCHER_SELECT_BODY_MESSAGE =
    "请求体需要 JSON：{\"showCardIds\":[\"…\"]}（取自 /api/pay/vouchers 的 showCardId）"

internal const val PAY_SEGMENT = "pay"
internal const val PAY_CODE = "code"
internal const val PAY_VOUCHERS = "vouchers"
internal const val PAY_VOUCHERS_SELECT = "vouchers/select"
