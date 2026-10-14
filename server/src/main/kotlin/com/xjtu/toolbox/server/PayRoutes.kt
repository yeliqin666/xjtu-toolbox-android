package com.xjtu.toolbox.server

import com.xjtu.toolbox.pay.PaymentCodeApi
import com.xjtu.toolbox.pay.PaymentVoucher
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
 * ⚠️ **刻意不端的写路径**：`updateVoucherStatus(ids)`（`POST .../vouchers/updateVoucherStatus`）
 * —— 屏上勾选加餐券时会把「这一轮用哪几张」**真的推给上游**，那是写路径 ⇒ serve 模式下
 * 那个勾选动作**没有端点**（如实缺口，见契约 §5.7）。加餐券勾选的**落盘**
 * （`:core` 的 `PaymentSelectionStore`，宿主那份键值存储）同样不端：serve 这一层没有宿主磁盘。
 *
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

internal const val PAY_SEGMENT = "pay"
internal const val PAY_CODE = "code"
internal const val PAY_VOUCHERS = "vouchers"
