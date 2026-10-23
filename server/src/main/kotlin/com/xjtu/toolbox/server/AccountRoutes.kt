package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.auth.AccountType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/accounts` —— 多账号页在这一端的端点族（`docs/api-contract.md` §5.10）。
 *
 * 一条读 + 四条写（`docs/parity-ledger.md` §6.1 会议 #10 = 丙：写侧全做，HTTPS 成硬前提）：
 *
 * | 端点 | 请求体 | 干什么 |
 * |---|---|---|
 * | `GET` [ACCOUNT_PATH] | — | 账号表 + serve 当前会话，合成一份只读视图 |
 * | `POST` [ACCOUNT_PATH]/switch | `{"id"}` | 切换当前账号（**只认 id，不收密码**） |
 * | `POST` [ACCOUNT_PATH]/add | `{"username","password","accountType"?,"activate"?}` | 新增账号 |
 * | `POST` [ACCOUNT_PATH]/delete | `{"id","confirm":true}` | 删除账号（要二次确认） |
 * | `POST` [ACCOUNT_PATH]/password | `{"id","newPassword"}` | 改密 |
 *
 * ## HTTPS 是硬前提
 *
 * 四条写端点只在 [httpsEnabled] 为真时服务；明文 HTTP 上一律 **403 + [ApiErrors.HTTPS_REQUIRED]**
 * （消息里带着「怎么开 HTTPS / 怎么弄一张自签证书」）。进门第一件事就是这个判断，**早于**读请求体：
 * 明文链路上连密码都不该被接收进来。**读端点不受影响**（`readOnly` 字段如实反映这件事）。
 *
 * ## id 的两种形式
 *
 * 读端点给出去的是打码 id（`2023****0001`），客户端手上就是它；写端点两种都认：先按原文认
 * （完整学号/手机号），再按打码形式认。打码形式对上不止一条时回 409，让调用方改用完整学号，
 * 而不是替它猜一条。
 *
 * ## 幂等
 *
 * 见 [ServeAccountsHost] 的 KDoc（按「目标态」判，不用幂等键）：重复提交 = `ok:true` + `changed:false`，
 * 既不产生第二份账号，也不二次删除。
 *
 * ## 红线
 *
 * 密码只出现在**请求体**里：本文件不记日志、不回显，任何 `message` / `data` 里都不会有它。
 */
internal fun Route.accountRoutes(
    session: ServeSession,
    store: AccountStore = AccountStore(),
    /** serve 这一次是不是跑在 HTTPS 上（`ServeConfig.tls != null`）。写端点的硬前提。 */
    httpsEnabled: Boolean = false,
) {
    val writes = ServeAccountsHost(session, store)
    // 相对段而不是绝对路径：这棵子树已经挂在 `route("/api")` 之下（先例见 SessionRoutes）。
    route(ACCOUNT_SEGMENT) {
        // 见文件头：两个真相合成一份只读视图。
        get {
            call.respond(ApiEnvelope.ok(accountsSnapshot(session, store, httpsEnabled)))
        }

        post(ACCOUNT_SWITCH) {
            if (!call.requireHttps(httpsEnabled)) return@post
            val body = call.receiveOrNull<AccountSwitchRequest>() ?: return@post call.respondBadRequestMessage(SWITCH_BODY_MESSAGE)
            call.respondOutcome(writes.switchTo(body.id), session, store, httpsEnabled)
        }

        post(ACCOUNT_ADD) {
            if (!call.requireHttps(httpsEnabled)) return@post
            val body = call.receiveOrNull<AccountAddRequest>() ?: return@post call.respondBadRequestMessage(ADD_BODY_MESSAGE)
            val type = body.accountType?.let { key ->
                AccountType.entries.firstOrNull { it.key == key.trim() }
                    ?: return@post call.respondBadRequestMessage("accountType 只认 undergraduate 或 postgraduate")
            } ?: AccountType.UNDERGRADUATE
            call.respondOutcome(
                writes.add(body.username, body.password, type, body.activate),
                session, store, httpsEnabled,
            )
        }

        post(ACCOUNT_DELETE) {
            if (!call.requireHttps(httpsEnabled)) return@post
            val body = call.receiveOrNull<AccountDeleteRequest>() ?: return@post call.respondBadRequestMessage(DELETE_BODY_MESSAGE)
            // 二次确认：少一个 `"confirm":true` 就是 400，且什么都没动（不是「默认删」）
            if (!body.confirm) return@post call.respondBadRequestMessage(DELETE_BODY_MESSAGE)
            call.respondOutcome(writes.remove(body.id), session, store, httpsEnabled)
        }

        post(ACCOUNT_PASSWORD) {
            if (!call.requireHttps(httpsEnabled)) return@post
            val body = call.receiveOrNull<AccountPasswordRequest>() ?: return@post call.respondBadRequestMessage(PASSWORD_BODY_MESSAGE)
            call.respondOutcome(writes.updatePassword(body.id, body.newPassword), session, store, httpsEnabled)
        }
    }
}

/** 写端点的进门闸门：不在 HTTPS 上 ⇒ 403 + 「怎么开 HTTPS」的指引（见文件头）。 */
private suspend fun ApplicationCall.requireHttps(httpsEnabled: Boolean): Boolean {
    if (httpsEnabled) return true
    respond(HttpStatusCode.Forbidden, ApiErrors.httpsRequired())
    return false
}

/**
 * 一次写动作 → 响应。
 *
 * 成功回 `code:0` + [AccountsWriteData]（`data.snapshot` 就是动作之后那一刻的账号表，
 * 客户端不必再补一发 `GET`）；失败回 [AccountsWriteOutcome.Failed.code] 那一档 + 中文短句。
 */
private suspend fun ApplicationCall.respondOutcome(
    outcome: AccountsWriteOutcome,
    session: ServeSession,
    store: AccountStore,
    httpsEnabled: Boolean,
) = when (outcome) {
    is AccountsWriteOutcome.Done -> respond(
        ApiEnvelope.ok(
            AccountsWriteData(
                ok = true,
                changed = outcome.changed,
                message = outcome.message,
                snapshot = accountsSnapshot(session, store, httpsEnabled),
            ),
        ),
    )

    is AccountsWriteOutcome.Failed -> respond(
        HttpStatusCode.fromValue(outcome.code),
        ApiEnvelope<Unit>(outcome.code, message = outcome.message),
    )
}

/** `GET /api/accounts` 的 `data`。 */
@Serializable
internal data class AccountsData(
    val authenticated: Boolean,
    /** `true` = 这一端写在**明文 HTTP** 上，四条写端点会 403（见文件头「HTTPS 是硬前提」）。 */
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

/**
 * 四条写端点的 `data`。
 *
 * [ok] 恒为 `true`（失败走 `code != 0`）；
 * [changed] 是「这次到底改了没改」——重复提交（目标态本来就成立）为 `false`；
 * [snapshot] 是动作之后那一刻的账号表（与 `GET` 的 `data` 同形）。
 */
@Serializable
internal data class AccountsWriteData(
    val ok: Boolean,
    val changed: Boolean,
    val message: String,
    val snapshot: AccountsData,
)

/** `POST /api/accounts/switch` 的请求体：只认 id（打码形式也认），**不收密码**。 */
@Serializable
internal data class AccountSwitchRequest(val id: String = "")

/** `POST /api/accounts/add` 的请求体。[activate] 为真时顺手认成当前账号（默认只入表）。 */
@Serializable
internal data class AccountAddRequest(
    val username: String = "",
    val password: String = "",
    val accountType: String? = null,
    val activate: Boolean = false,
)

/** `POST /api/accounts/delete` 的请求体：[confirm] 必须显式 `true`（二次确认）。 */
@Serializable
internal data class AccountDeleteRequest(val id: String = "", val confirm: Boolean = false)

/** `POST /api/accounts/password` 的请求体。 */
@Serializable
internal data class AccountPasswordRequest(val id: String = "", val newPassword: String = "")

private const val SWITCH_BODY_MESSAGE = "请求体需要 JSON：{\"id\":\"…\"}（打码 id 也认）"

private const val ADD_BODY_MESSAGE =
    "请求体需要 JSON：{\"username\":\"…\",\"password\":\"…\"}（可选 \"accountType\":\"undergraduate|postgraduate\"、\"activate\":true）"

private const val DELETE_BODY_MESSAGE =
    "删除会清掉本端这个账号的凭据与会话缓存，要做二次确认：请求体写成 {\"id\":\"…\",\"confirm\":true}"

private const val PASSWORD_BODY_MESSAGE = "请求体需要 JSON：{\"id\":\"…\",\"newPassword\":\"…\"}"

/** 账号表 + serve 当前会话 → 线上形状（见文件头「它答的是什么」）。 */
private fun accountsSnapshot(session: ServeSession, store: AccountStore, httpsEnabled: Boolean): AccountsData {
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
        // 写侧要 HTTPS：没开就如实说「这一端只读」
        readOnly = !httpsEnabled,
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

/** 四条写端点的相对段（挂在 [ACCOUNT_SEGMENT] 之下）。 */
internal const val ACCOUNT_SWITCH = "switch"
internal const val ACCOUNT_ADD = "add"
internal const val ACCOUNT_DELETE = "delete"
internal const val ACCOUNT_PASSWORD = "password"
