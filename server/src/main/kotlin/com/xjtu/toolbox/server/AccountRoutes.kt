package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/accounts` —— 多账号页在这一端的那一条**只读**端点（`docs/api-contract.md` §5.10，D9）。
 *
 * ## 它答的是什么
 *
 * 一条响应里合成**两个真相**（都只读，谁也不改）：
 *  1. **账号表**：`xjtu_accounts`（`:data` 的 [AccountStore]）—— 它与桌面端**共用同一份文件**
 *     （同一个数据根、同一套账号后缀规则，见 `docs/agent-web-spec.md` 那一条）；
 *  2. **serve 这一侧的会话**：`AccountContext.activeAccountId`（登录成功或冷启动恢复时写入，
 *     见 [ServeSession]）。表里没有它的时候**补一条**，否则屏上会出现「已登录但一个账号也没有」。
 *
 * ## 红线：`id` 是**打码后**的学号
 *
 * 契约 §3.4 说 `/api/…` 的响应不许出现学号/姓名/手机号（§5.2 那三处例外与本端点无关）。
 * 账号页又非要有东西可认，所以这一格端的是 [maskedAccountId] 的**打码形式**（`2023****0001`），
 * 它同时是显示名与句柄。**完整学号、姓名（`nickname`）、密码、设备指纹、RSA 公钥一律不投影** ——
 * 这条由 `ServeAccountsTest` 的红线断言钉住（夹具里那些值明明都在）。
 *
 * ## 为什么没有写端点（切换 / 新增 / 删除 / 改密）
 *
 * D9 的原话是「浏览器上切换账号 = 让 serve 换一套凭据」，而 serve 这一侧换凭据就是
 * [ServeSession] 的登录态迁移（命名空间 + 凭据 + 站点快照一起换）。这一轮按工单的「只读优先」
 * 只端出**读**那一半：账号页在浏览器上能看见账号表与当前账号，动作类操作如实指路到 App / 桌面端
 * （屏上那一行说明见 `:web` 的 `WebGapNote`，宿主是 `WebAccountsScreen`）。`readOnly` 那一格就是这件事的**能力开关**
 * （契约 §4：客户端不得靠猜）—— 它是常量 `true`，与 `/api/jwxt/evaluations` 的 `canSubmit:false` 同型。
 *
 * ## 错误码
 *
 * 只有 401（令牌闸门那一档：没带令牌 / 令牌不对）。本端点**不要求已登录** —— 未登录时
 * `authenticated:false` + 账号表照旧列出来（那张表在磁盘上，不依赖任何会话）。
 *
 * @param session 会话装配：`authenticated` 与「当前是哪个账号」都从它那里读。
 * @param store 账号表（默认就是 `:data` 那份实现；测试换成临时数据根下的同一个类）。
 */
internal fun Route.accountRoutes(session: ServeSession, store: AccountStore = AccountStore()) {
    // 相对段而不是绝对路径：这棵子树已经挂在 `route("/api")` 之下（先例见 SessionRoutes）。
    route(ACCOUNT_SEGMENT) {
        // 见文件头：两个真相合成一份只读视图。
        get {
            call.respond(ApiEnvelope.ok(accountsSnapshot(session, store)))
        }
    }
}

/**
 * `GET /api/accounts` 的 `data`。
 *
 * @param authenticated serve 这一侧**有没有会话**（与 `/api/session` 的同一格同义：「登录跑完了」
 *   或冷启动从落盘凭据静默恢复过 —— 不是「内核里有凭据」）。
 * @param readOnly 能力开关：本端**不提供**切换 / 新增 / 删除 / 改密（见文件头）。常量 `true`。
 * @param accounts 账号表 ∪ serve 当前会话那一条；顺序 = 表里原本的顺序，补的那一条在末尾
 *   （屏自己会按「当前账号优先、最近使用优先」再排一次）。
 */
@Serializable
internal data class AccountsData(
    val authenticated: Boolean,
    val readOnly: Boolean,
    val accounts: List<AccountDto>,
)

/**
 * 一行账号。
 *
 * @param id **打码后**的学号/手机号（`2023****0001`）：既当显示名也当句柄，见文件头。
 * @param accountType `undergraduate` / `postgraduate`（`:core` 的 `AccountType.key` 那一套写法）。
 * @param lastUsedAt ISO-8601 带时区（契约 §4）。`null` = 本端**明确说没有**：账号表里没有这一条
 *   （serve 当前会话那一条就是这种情况）或者它从来没被切过 —— 不编一个时刻（先例是
 *   `/api/dorm/reading` 不编时间戳那一条）。
 * @param active serve 这一侧的当前会话就是这一条。**不是**账号表里那个激活指针
 *   （那是桌面端的，serve 不写它、也不读它）。
 */
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
        // serve 当前会话那一条不在表里（这一端登录只写 `xjtu_credentials`，账号表是桌面端在维护）
        // ⇒ 补一条，`lastUsedAt` 如实留 null（serve 不知道它上次什么时候用的）。
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

/**
 * 学号/手机号 → 打码形式（`2023****0001`）。**这是这一族唯一允许出现在响应里的身份形状。**
 *
 * 规则：够长（≥8）就留前 4 后 4，短一点（4..7）留前 1 后 1，更短的整串打掉。
 * 打码之后仍然可能撞（两个学号前 4 后 4 一样），但这一格**只是显示名与句柄**：本端没有写端点，
 * 撞了也不会让哪个动作打错人（见文件头）。
 */
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

/**
 * `/api/accounts` 的路由段。
 *
 * [ACCOUNT_SEGMENT] 是路由树里用的那一段（相对 [API_PREFIX]），[ACCOUNT_PATH] 是它对外的完整路径
 * —— 由前者拼出来，所以两者永远对得上（测试里的请求路径用的就是后者）。
 */
internal const val ACCOUNT_SEGMENT = "accounts"
internal const val ACCOUNT_PATH = "$API_PREFIX/$ACCOUNT_SEGMENT"
