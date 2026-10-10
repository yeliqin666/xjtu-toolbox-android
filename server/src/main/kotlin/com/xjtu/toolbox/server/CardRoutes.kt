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

/**
 * `/api/card*` —— 两个端点（`docs/api-contract.md` §5 的 P0），走 `:data` 的
 * [AppCampusCardSource]（包住 `CampusCardApi` 的 ncard 抓取，解析口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/card/balance` | `CampusCardSource.card()`（`CardInfo`） |
 * | `GET /api/card/transactions` | `CampusCardSource.transactions(from, to, page, pageSize)` |
 *
 * ## ⚠️ 挂失口径（契约 §5 要「明确选一个」的那条）
 *
 * `CardInfo.lostFlag` 在 `:data` 的 `CampusCardApi.getCardInfo` 里取的是上游 **`barflag`**
 * （`ncard` 的 `queryCard` 返回 `barflag`/`freezeflag` 两个号；`:app` 的老实现写的就是
 * `barflag`，`CampusCardNetApi` 的 KDoc 记着「`:app` 的 `lostFlag` 取的是 `barflag`」）。
 * 本层沿用它 —— 不另选 `lostflag`（那是 campus-api 的映射，两边的读法在异常卡上才可能分叉，
 * 本端只对 `:data` 负责）。
 *
 * ## ⚠️ 红线：卡面的 `account` / `name` / `studentNo` **不投影**
 *
 * `CardInfo` 那三个字段是卡账号 / 姓名 / 学号（登录用户本人）—— 与 `/api/status`
 * 那条红线同口径，任何响应不许出现。`balance` / `pendingAmount` / `lostFlag` / `frozenFlag` /
 * `expireDate` / `cardType` / `department` 是卡面形状（`cardType` ← 上游 `cardname`，`amount`/`merchant`
 * 等流水字段 ← `signed`/`channel` 的解析在 `:data` 已经做掉了，这里只投影结果）。
 *
 * ## 分页与查询
 *
 * 流水按页取（`:data` 的 `transactions` 本来就带分页与总数）：`?from=&to=`（必填，`YYYY-MM-DD`）
 * + `?page=`（默认 1）`?size=`（默认 50）。响应 `{page, size, total, transactions[]}`（§4）。
 *
 * @param session 会话装配；校园卡站点从 `session.sessionManager` 取。桌面端那份传 `store = null`
 *   （不缓存）—— serve 这里同样是 `null`：没有宿主磁盘快照那一档（P2 宿主能力）。
 */
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

/** `GET /api/card/balance` 的 `data`。⚠️ 没有 `account`/`name`/`studentNo`（红线，见文件头）。 */
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

/**
 * 一笔流水的投影。`amount` 是**带符号**的（负 = 支出，正 = 收入）—— `:data` 的解析把
 * 上游 `tranamt` 无符号绝对值 + `signedAmountCents` 的方向判据合成好了，这里只投影结果。
 */
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

/** `?page=&size=` 的正整数读法；不是正整数（或没给）返回 null。 */
private fun io.ktor.http.Parameters.positiveInt(name: String, default: Int): Int? {
    val raw = get(name)?.trim() ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 }
}

// ── 常量 ─────────────────────────────────────────────────────────────

/** 校园卡站点会话。 */
internal fun ServeSession.cardSite(): SiteSession = sessionManager.getSite(ServeSession.CAMPUS_CARD_SITE_KEY)

/** 校园卡取数实例：每次请求现建；宿主存储传 null（serve 没有磁盘快照那一档，见 [AppCampusCardSource]）。 */
internal fun ServeSession.cardSource(): AppCampusCardSource = AppCampusCardSource(cardSite(), store = null)

internal const val CARD_SEGMENT = "card"
internal const val CARD_BALANCE = "balance"
internal const val CARD_TRANSACTIONS = "transactions"