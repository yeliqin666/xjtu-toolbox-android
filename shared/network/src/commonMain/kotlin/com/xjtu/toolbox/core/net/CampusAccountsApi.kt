package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 多账号页的 serve 版取数与写操作：给 `:web` 用（契约 §5.10 / §5.19）。 */
class CampusAccountsApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份只认 serve 契约（§5.10 / §5.19）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /** 账号表 ∪ serve 当前会话那一条（见 [ServeAccounts]）。 */
    suspend fun accounts(): ServeAccounts {
        if (mode != ApiMode.SERVE) serveOnly("账号表", ACCOUNTS_PATH)
        val data = client.serveData("加载账号表", baseUrl, ACCOUNTS_PATH)
        return data.toServeAccounts()
    }

    /** `POST /api/accounts/switch`：只认 id，不收密码。 */
    suspend fun switchAccount(id: String): ServeAccountsWrite {
        requireServeWrites()
        return write("切换账号", SWITCH_PATH, buildJsonObject { put("id", id) })
    }

    /**
     * `POST /api/accounts/add`。
     *
     * 默认 [activate] 为真，与安卓 / 桌面「添加后切过去」一致。
     * serve 这一侧不探活：密码打错要到下一次取数才暴露。
     */
    suspend fun addAccount(
        username: String,
        password: String,
        accountType: AccountType = AccountType.UNDERGRADUATE,
        activate: Boolean = true,
    ): ServeAccountsWrite {
        requireServeWrites()
        return write(
            "新增账号",
            ADD_PATH,
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("accountType", accountType.key)
                put("activate", activate)
            },
        )
    }

    /** `POST /api/accounts/delete`：必须带 `confirm:true`（二次确认）。 */
    suspend fun deleteAccount(id: String, confirm: Boolean = true): ServeAccountsWrite {
        requireServeWrites()
        return write(
            "删除账号",
            DELETE_PATH,
            buildJsonObject {
                put("id", id)
                put("confirm", confirm)
            },
        )
    }

    /** `POST /api/accounts/password`：改密入库，不重新登录。 */
    suspend fun updatePassword(id: String, newPassword: String): ServeAccountsWrite {
        requireServeWrites()
        return write(
            "更新密码",
            PASSWORD_PATH,
            buildJsonObject {
                put("id", id)
                put("newPassword", newPassword)
            },
        )
    }

    private fun requireServeWrites() {
        if (mode != ApiMode.SERVE) {
            serveOnly("账号表写操作", "$SWITCH_PATH + $ADD_PATH + $DELETE_PATH + $PASSWORD_PATH")
        }
    }

    /**
     * 一次写。失败（`code != 0`）也是结果，不是异常。
     * 密码只出现在请求体里，不会写进 [ServeAccountsWrite.message] 或任何异常文案。
     */
    private suspend fun write(what: String, path: String, body: JsonObject): ServeAccountsWrite {
        val outcome = client.serveAction(baseUrl, path, body.toString())
        if (!outcome.ok) {
            return ServeAccountsWrite(
                ok = false,
                changed = false,
                message = outcome.message,
                snapshot = null,
                code = outcome.code,
            )
        }
        val data = outcome.data
            ?: error("$what：服务端返回缺少 data")
        val snapshotObj = data["snapshot"] as? JsonObject
        return ServeAccountsWrite(
            ok = true,
            changed = data["changed"].safeBoolean(),
            message = data["message"].safeString(),
            snapshot = snapshotObj?.toServeAccounts(),
            code = 0,
        )
    }
}

/** 把 `GET /api/accounts` 的 `data`（或写端点里的 `snapshot`）解成 [ServeAccounts]。 */
internal fun JsonObject.toServeAccounts(): ServeAccounts = ServeAccounts(
    authenticated = this["authenticated"].safeBoolean(),
    // 缺这一格按「不可写」处理：能力开关的默认值必须是保守的那一侧（§4）。
    readOnly = this["readOnly"].safeBoolean(default = true),
    accounts = this.arr(ACCOUNTS_KEY).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        // 打码后的 id 是这一行的唯一标识（也是屏上的显示名）：空的那一行没法用，丢掉。
        val id = row["id"].safeString().trim()
        if (id.isEmpty()) return@mapNotNull null
        ServeAccount(
            id = id,
            accountType = AccountType.fromKey(row["accountType"].safeString().trim()),
            // §4：`null` = 服务端明确说没有，不编一个时刻。
            lastUsedAt = row["lastUsedAt"].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
            active = row["active"].safeBoolean(),
        )
    },
)

/** `GET /api/accounts` 的 `data`（§5.10）。 */
data class ServeAccounts(
    val authenticated: Boolean,
    val readOnly: Boolean,
    val accounts: List<ServeAccount>,
)

/** 账号表里的一行（只读投影，不含密码）。 */
data class ServeAccount(
    val id: String,
    val accountType: AccountType,
    val lastUsedAt: Long?,
    val active: Boolean,
)

/**
 * 四条写端点的结果（§5.10）。
 *
 * 失败时 [snapshot] 为 null；此对象不含密码，[toString] 也不会带上请求里的密码。
 */
data class ServeAccountsWrite(
    val ok: Boolean,
    val changed: Boolean,
    val message: String,
    val snapshot: ServeAccounts?,
    /** 信封里的 `code`：成功 `0`，失败是那一次的 HTTP 状态码。 */
    val code: Int = 0,
)

/** 对外路径（契约 §5.10）。 */
private const val ACCOUNTS_PATH = "/api/accounts"
private const val SWITCH_PATH = "$ACCOUNTS_PATH/switch"
private const val ADD_PATH = "$ACCOUNTS_PATH/add"
private const val DELETE_PATH = "$ACCOUNTS_PATH/delete"
private const val PASSWORD_PATH = "$ACCOUNTS_PATH/password"

/** `data` 里账号数组那个键。 */
private const val ACCOUNTS_KEY = "accounts"
