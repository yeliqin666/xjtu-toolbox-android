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

/**
 * `/api/coupon*` —— 加餐券的**三个端点**（`docs/api-contract.md` §5.5）：列表 + 详情两个只读，
 * 加上领券（写）。走 `:data` 的 [AppCouponSource]（包住 `CouponApi`，解析口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/coupon/list` | `CouponSource.queryCoupons(filter, page, pageSize)` |
 * | `GET /api/coupon/detail` | `CouponSource.getCouponDetail(showCardId)` |
 * | `POST /api/coupon/activate` | `CouponSource.activateCoupon(showCardId)`（**写**） |
 *
 * ## 领券（写路径）✅ 已端（2026-10-15，批③-b / D7 ③）
 *
 * `CouponSource.activateCoupon`（`POST /app/voucher/activate`）**真会改上游状态**，所以它是一个
 * 写端点：`POST /api/coupon/activate`，body 只有一格 `{showCardId}`，成功回 `{ok:true, message}`。
 * 屏上的口径一字未改（`:core` 的 `CouponViewModel.receive` 仍是先查详情再领、领完重拉那一档）。
 *
 * ⚠️ **它区分不了「上游说不能领」与「上游坏了」**：`:data` 的 `CouponApi.activateCoupon` 返回
 * `Unit`，失败时只抛一句上游原文（`RuntimeException(msg)`），认不出「这张券领完了」这种业务结果
 * ⇒ 与只读那两条同一条口径，一律 `502`。要分出 `409` 得先改 `:data` 那一层（把失败细分，与
 * `/api/library/action` 的 `OrderActionResult` 同一个形状）—— 那是 `:data` 的改动，本轮不编一个
 * 判据出来（如实记在契约 §5.5）。
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `CouponModels`）
 *
 * - 四档筛选：`?filter=` 取 `CouponFilter` 的**小写枚举名** ——
 *   `available`（可领取，status=0）· `usable`（可使用，status=1）· `used_up`（已用完）·
 *   `expired`（已过期）。**省略 = `usable`** —— 与 `:core` 的 `CouponViewModel` 初始档一致
 *   （`var filter by mutableStateOf(CouponFilter.USABLE)`），本层不另定一个默认档。
 *   中文名（`CouponFilter.label` =「可领取」那些）**不当参数收**：参数只认一套 ASCII 写法；
 * - 金额一律**单位是分**（`amountFen` / `leftAmountFen`，`:core` 模型的原字段名）。`CouponRecord`
 *   上那两个 `amountYuan` 属性是**换算便利属性**，不是模型字段 ⇒ 不进线上形状（除 100 是消费方
 *   的事，本层不做第二次舍入）；
 * - `imageUrl` 是 `:data` 的 `normalizeImageUrl` 拼好的**绝对地址**（`egc.xjtu.edu.cn` + 相对路径）。
 *   ⚠️ **已知缺口**：那张图要带 `egc` 的 `Authorization` 才取得到（`AppCouponSource.loadImage`
 *   就是走站点会话发的）⇒ 浏览器**直接打开这个 URL 会拿到登录页**。本层没有图片代理端点；
 *   下一批要做封面图就得开一个（如实记在契约 §5.5 的「保不住的字段」那一条）；
 * - `CouponDetail` 的十二格原样投影（`title ← destitle`、`description ← describes`、
 *   `leftAmountFen ← ltranamt` 那些映射在 `:data` 的 `CouponJsonParser` 已经做掉了，这里只投影结果）。
 *
 * ## 错误码
 *
 * 400 `filter` 不认识 / `showCardId` 缺失 / `page`、`size` 不是正整数 / `activate` 的 body 不是那一格 ·
 * 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句，含会话失效那档，也含「领券被
 * 上游拒」那一档 —— 见上）。
 *
 * @param session 会话装配；加餐券站点（`coupon`）从它那里取。
 */
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

/** `GET /api/coupon/list` 的 `data`：分页形状（§4）+ 本次用的档。 */
@Serializable
internal data class CouponListData(
    /** 本次用的档（ASCII 码，见 [CouponFilter.code]）—— 请求没给就是 `usable`。 */
    val filter: String,
    val page: Int,
    val size: Int,
    /** 上游给的这一档的总数（`:data` 的 `CouponPage.total`）。 */
    val total: Int,
    val records: List<CouponRecordDto>,
)

/**
 * 一张券（`CouponRecord` 的线上形状）。
 *
 * ⚠️ 金额单位是**分**（不是元）；`imageUrl` 在浏览器里直接打不开（要 egc 会话）—— 见文件头 KDoc。
 */
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

/**
 * 档 → 线上码。用枚举名的小写（`AVAILABLE` → `available`）：一套 ASCII 写法，参数与响应同一个值。
 */
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

/**
 * `POST /api/coupon/activate` 的 `data`（与 §5.3.1 / §5.3.2 两个写端点同一个 `{ok, message}` 形状）。
 *
 * `:core` 的 `CouponSource.activateCoupon` 返回 `Unit`（它一个格都不读），所以 `message` 是给
 * curl / 排障看的 —— 屏上那句「已领取 …」是 `CouponViewModel` 自己按详情拼的，本层不替它拼。
 */
@Serializable
internal data class CouponActionResult(val ok: Boolean, val message: String)

/** 领券成功那一句（写端点的 `message` 口径：能直接给用户看的中文短句）。 */
internal const val COUPON_ACTIVATED_MESSAGE = "✓ 已领取"

internal const val COUPON_ACTIVATE_BODY_MESSAGE =
    "请求体需要 JSON：{\"showCardId\":\"…\"}（取自 /api/coupon/list 的 showCardId）"

