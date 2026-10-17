package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/accounts` —— 多账号页在这一端的那一条只读端点（`docs/api-contract.md` §5.10）。 */
internal fun Route.accountRoutes(session: ServeSession, store: AccountStore = AccountStore()) {
    // 相对段而不是绝对路径：这棵子树已经挂在 `route("/api")` 之下（先例见 SessionRoutes）。
    route(ACCOUNT_SEGMENT) {
        // 见文件头：两个真相合成一份只读视图。
        get {
            call.respond(ApiEnvelope.ok(accountsSnapshot(session, store)))
        }
    }
}

/** `GET /api/accounts` 的 `data`。 */
@Serializable
internal data class AccountsData(
    val authenticated: Boolean,
    val readOnly: Boolean,
    val accounts: List<AccountDto>,
)

/** 一行账号。 */
@Serializable
internal data class AccountDto(
    val id: String,
    val accountType: String,
    val lastUsedAt: String?,
    val active: Boolean,
)

/** 账号表 + serve 当前会话 → 线上形状（见文件头「它答的是什么」）。 */
private fun accountsSnapshot(session: ServeSession, store: AccountStore): AccountsData {
    // ⚠️ 顺序有意义：`authenticated` 会触发一次幂等的冷启动恢复（那一步会把
    //    `AccountContext.activeAccountId` 写成落盘凭据里的那个学号），所以它必须在读 activeId 之前。
    val authenticated = session.authenticated
    val activeId = AccountContext.activeAccountId

    val rows = ArrayList<Row>()
    val seen = HashSet<String>()
    for (account in store.list()) {
        // 同一个学号在表里只该有一条（`AccountStore` 自己就按 accountId 去重），这里再挡一次：
        // 坏档案里重复的条目不该在屏上变成两行。
        if (seen.add(account.accountId)) rows += Row(account.accountId, account.accountType.key, account.lastUsedAt)
    }
    if (activeId != null && seen.add(activeId)) {
        rows += Row(activeId, AccountContext.activeAccountType.key, 0L)
    }

    return AccountsData(
        authenticated = authenticated,
        readOnly = true,
        accounts = rows.map {
            AccountDto(
                id = maskedAccountId(it.accountId),
                accountType = it.accountTypeKey,
                lastUsedAt = isoTime(it.lastUsedAt),
                active = it.accountId == activeId,
            )
        },
    )
}

/** 内部一行：真实 accountId 只在进程内用，**永不**进响应（响应里走 [maskedAccountId]）。 */
private data class Row(val accountId: String, val accountTypeKey: String, val lastUsedAt: Long)

/** 学号/手机号 → 打码形式（`20230001`）。 */
internal fun maskedAccountId(raw: String): String {
    val id = raw.trim()
    return when {
        id.length >= MASKED_EDGES * 2 -> id.take(MASKED_EDGES) + MASK + id.takeLast(MASKED_EDGES)
        id.length >= 4 -> id.take(1) + MASK + id.takeLast(1)
        id.isEmpty() -> ""
        else -> MASK
    }
}

/** 打码时两边各留几位（≥ 8 位才用得上）。 */
private const val MASKED_EDGES = 4

/** 打码用的那串星号。 */
private const val MASK = "****"

/** `/api/accounts` 的路由段。 */
internal const val ACCOUNT_SEGMENT = "accounts"
internal const val ACCOUNT_PATH = "$API_PREFIX/$ACCOUNT_SEGMENT"
