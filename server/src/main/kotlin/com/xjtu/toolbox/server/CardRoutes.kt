package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.Transaction
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** `/api/card*`。 */
internal fun Route.cardRoutes(session: ServeSession) {
    route(CARD_SEGMENT) {

        // ── 卡面 ────────────────────────────────────────────────────────
        get(CARD_BALANCE) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val card = session.cardSource().card()
                call.respond(
                    ApiEnvelope.ok(
                        CardBalanceData(
                            balance = card.balance,
                            pendingAmount = card.pendingAmount,
                            lostFlag = card.lostFlag,
                            frozenFlag = card.frozenFlag,
                            expireDate = card.expireDate,
                            cardType = card.cardType,
                            department = card.department,
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载校园卡")
            }
        }

        // ── 流水一页 ────────────────────────────────────────────────────
        get(CARD_TRANSACTIONS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val from = params["from"]?.trim()?.parseLocalDate()
            val to = params["to"]?.trim()?.parseLocalDate()
            if (from == null || to == null) {
                return@get call.respondBadRequestMessage("from/to 必填，形如 2026-10-01")
            }
            if (from > to) {
                return@get call.respondBadRequestMessage("from 不能晚于 to")
            }
            val page = params.positiveInt("page", 1) ?: return@get call.respondBadRequestMessage("page 需要是正整数")
            val size = params.positiveInt("size", 50) ?: return@get call.respondBadRequestMessage("size 需要是正整数")
            try {
                val (total, rows) = session.cardSource().transactions(from = from, to = to, page = page, pageSize = size)
                call.respond(
                    ApiEnvelope.ok(
                        TransactionsData(
                            page = page,
                            size = size,
                            total = total,
                            transactions = rows.map { it.toDto() },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载流水")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/card/balance` 的 `data`。⚠️ 不含 `account`/`name`/`studentNo`。 */
@Serializable
internal data class CardBalanceData(
    val balance: Double,
    val pendingAmount: Double,
    /** 挂失：沿 `:data` 的 `barflag` 读法（见文件头 KDoc）。 */
    val lostFlag: Boolean,
    val frozenFlag: Boolean,
    val expireDate: String,
    /** `cardType` ← 上游 `cardname`（与 `:core` 模型同一个字段名）。 */
    val cardType: String,
    val department: String,
)

/** `GET /api/card/transactions` 的 `data`：分页形状（§4）。 */
@Serializable
internal data class TransactionsData(
    val page: Int,
    val size: Int,
    val total: Int,
    val transactions: List<TransactionDto>,
)

/** 一笔流水的投影。 */
@Serializable
internal data class TransactionDto(
    val time: String,
    val merchant: String,
    val amount: Double,
    val balance: Double,
    val type: String,
    val description: String,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun Transaction.toDto() = TransactionDto(time, merchant, amount, balance, type, description)

private fun String.parseLocalDate(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

// ── 常量 ─────────────────────────────────────────────────────────────

/** 校园卡站点会话。 */
internal fun ServeSession.cardSite(): SiteSession = sessionManager.getSite(ServeSession.CAMPUS_CARD_SITE_KEY)

/** 校园卡取数实例：每次请求现建；宿主存储传 null（serve 没有磁盘快照那一档，见 [AppCampusCardSource]）。 */
internal fun ServeSession.cardSource(): AppCampusCardSource = AppCampusCardSource(cardSite(), store = null)

internal const val CARD_SEGMENT = "card"
internal const val CARD_BALANCE = "balance"
internal const val CARD_TRANSACTIONS = "transactions"