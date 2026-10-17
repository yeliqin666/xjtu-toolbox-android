package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

/** 多账号页的 serve 版取数：给 `:web` 用。 */
class CampusAccountsApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.10）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /** 账号表 ∪ serve 当前会话那一条（见 [ServeAccounts]）。 */
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

/** `GET /api/accounts` 的 `data`（§5.10）。 */
data class ServeAccounts(
    val authenticated: Boolean,
    val readOnly: Boolean,
    val accounts: List<ServeAccount>,
)

/** 账号表里的一行（只读投影，见类 KDoc 的那一段）。 */
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
