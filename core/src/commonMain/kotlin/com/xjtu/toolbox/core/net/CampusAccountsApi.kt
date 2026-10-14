package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

/**
 * 多账号页的 **serve 版取数**：给 `:web` 用。
 *
 * | 端点 | 本类的方法 |
 * |---|---|
 * | `GET /api/accounts` | [accounts]（一次拿全：账号表 + serve 当前会话那一条） |
 *
 * 契约 §5.10（工单 S7a / D9）；`:server` 那一侧是 `AccountRoutes.kt`。这一族**只有一个只读端点**：
 * 「切换 / 新增 / 删除 / 改密」在 serve 这一侧换的就是它自己的登录态，本批按工单的「只读优先」
 * 不端出去 —— [ServeAccounts.readOnly] 就是这件事的能力开关（屏不许靠猜，契约 §4）。
 *
 * ## 这一格里**没有**什么（红线 §3.4）
 *
 * `id` 是**打码后**的学号/手机号（`2023****0001`），它同时是显示名与句柄；完整学号、姓名、
 * 密码、设备指纹、RSA 公钥**都不在响应里**，所以这一族的模型 [ServeAccount] 也没有那些字段 ——
 * 它不是 `:core` 那个 [com.xjtu.toolbox.account.Account]（那个模型带 `password` / `nickname`，
 * 在浏览器里没有任何用处，端它就是把红线拆了）。要喂共享屏就由 `:web` 的宿主映射一次。
 *
 * ## 默认形态（campus-api）
 *
 * 多账号在 campus-api 里没有对应端点（那一头的账号与凭据都在它自己的进程里，
 * 两级之间没有这条缝）⇒ 非 serve 分支一律 [serveOnly]（抛错，不返回一份空表 —— 那会把
 * 「形态不对」说成「这台机器一个账号都没有」）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusAccountsApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.10）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /**
     * 账号表 ∪ serve 当前会话那一条（见 [ServeAccounts]）。
     *
     * 不要求已登录：未登录时 [ServeAccounts.authenticated] 是 `false`，而账号表照旧列出来
     * （那张表在磁盘上，与任何会话无关）。
     */
    suspend fun accounts(): ServeAccounts {
        if (mode != ApiMode.SERVE) serveOnly("账号表", ACCOUNTS_PATH)
        val data = client.serveData("加载账号表", baseUrl, ACCOUNTS_PATH)
        return ServeAccounts(
            authenticated = data["authenticated"].safeBoolean(),
            // 缺这一格按「不可写」处理：能力开关的默认值必须是**保守**的那一侧（§4），
            // 猜成 true 会让屏上长出一堆点了必失败的按钮。
            readOnly = data["readOnly"].safeBoolean(default = true),
            accounts = data.arr(ACCOUNTS_KEY).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                // 打码后的 id 是这一行的**唯一标识**（也是屏上的显示名）：空的那一行没法用，丢掉。
                val id = row["id"].safeString().trim()
                if (id.isEmpty()) return@mapNotNull null
                ServeAccount(
                    id = id,
                    accountType = AccountType.fromKey(row["accountType"].safeString().trim()),
                    // §4：`null` = 服务端明确说没有（这一条不在账号表里 / 从来没被切过），
                    // 不是「字段缺失 = 本端不投影」——两种都读成 null，但都**不编一个时刻**。
                    lastUsedAt = row["lastUsedAt"].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
                    active = row["active"].safeBoolean(),
                )
            },
        )
    }
}

/**
 * `GET /api/accounts` 的 `data`（§5.10）。
 *
 * @param authenticated serve 这一侧有没有会话（与 `/api/session` 的同一格同义）。
 * @param readOnly 能力开关：本端**没有**切换 / 新增 / 删除 / 改密的端点（见类 KDoc）。
 * @param accounts 账号表里那些 + serve 当前会话那一条；顺序由服务端给
 *   （屏自己会按「当前账号优先、最近使用优先」再排一次，与三端同一套）。
 */
data class ServeAccounts(
    val authenticated: Boolean,
    val readOnly: Boolean,
    val accounts: List<ServeAccount>,
)

/**
 * 账号表里的一行（**只读投影**，见类 KDoc 的红线那一段）。
 *
 * @param id 打码后的学号/手机号（`2023****0001`）：显示名与句柄是同一个值。
 * @param accountType 本科 / 研究生（认不出的写法落到 [AccountType.UNDERGRADUATE]，与其余端同一条兜底）。
 * @param lastUsedAt 上次切换时间（epoch 毫秒）；`null` = 服务端明确说没有。
 * @param active serve 这一侧的当前会话就是这一条。
 */
data class ServeAccount(
    val id: String,
    val accountType: AccountType,
    val lastUsedAt: Long?,
    val active: Boolean,
)

/** 对外路径（契约 §5.10）。 */
private const val ACCOUNTS_PATH = "/api/accounts"

/** `data` 里账号数组那个键。 */
private const val ACCOUNTS_KEY = "accounts"
