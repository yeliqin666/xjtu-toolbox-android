package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountManagerScreen
import com.xjtu.toolbox.account.AccountsController
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusAccountsApi
import com.xjtu.toolbox.core.net.ServeAccounts
import com.xjtu.toolbox.core.net.ServeAccountsWrite
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.UserFacingFailure
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.LoadingState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

/** 账号管理（`AppRoute.Accounts`）在浏览器这一端的宿主（契约 §5.10 / §5.19）。 */
internal class WebAccountsController(private val api: CampusAccountsApi) : AccountsController {

    /** 首次加载中（屏上那张加载态）。 */
    var loading by mutableStateOf(true)
        private set

    /** 取数失败时的中文短句（`FriendlyError` 给的），`null` = 没失败。 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** serve 这一侧的当前账号（打码 id）；`null` = 没登录。屏按它高亮那一行。 */
    var activeAccountId by mutableStateOf<String?>(null)
        private set

    /** 服务端答的能力开关（§5.10）；加载完成前按只读。 */
    var readOnly by mutableStateOf(true)
        private set

    /** 刚删掉当前账号、serve 已退出登录：页顶提示去切换。 */
    private var loggedOutAfterDelete by mutableStateOf(false)

    private var rows: List<Account> = emptyList()

    /** 打一次 `GET /api/accounts`。幂等，屏可在重试时反复调。 */
    suspend fun load() {
        loading = true
        errorMessage = null
        try {
            applySnapshot(api.accounts())
            loggedOutAfterDelete = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorMessage = FriendlyError.of(e, "加载账号表")
            rows = emptyList()
            activeAccountId = null
        } finally {
            loading = false
        }
    }

    /** 已加载的那一份（共享屏是**同步**读它：先 `load()`，再组合这一屏）。 */
    override fun accountList(): List<Account> = rows

    override suspend fun switchTo(accountId: String): Account? {
        ensureWritable()
        loggedOutAfterDelete = false
        val result = requireOk(api.switchAccount(accountId), "切换账号")
        result.snapshot?.let(::applySnapshot)
        return rows.firstOrNull { it.accountId == accountId } ?: rows.firstOrNull { it.active }
    }

    override suspend fun addAccount(
        username: String,
        password: String,
        accountType: AccountType,
    ): Result<Account> = try {
        ensureWritable()
        loggedOutAfterDelete = false
        // activate=true：与安卓 / 桌面「添加后切过去」一致。serve 不探活。
        val result = requireOk(api.addAccount(username, password, accountType, activate = true), "添加账号")
        result.snapshot?.let(::applySnapshot)
        // 投影里没有密码：返回的 Account.password 保持空串，避免进入 UI 状态或 toString。
        val added = rows.firstOrNull { it.active } ?: rows.lastOrNull()
            ?: Account(accountId = "", accountType = accountType)
        Result.success(added)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e.toUserFacing("添加账号"))
    }

    override suspend fun updatePassword(accountId: String, newPassword: String) {
        ensureWritable()
        val result = requireOk(api.updatePassword(accountId, newPassword), "更新密码")
        result.snapshot?.let(::applySnapshot)
    }

    override suspend fun removeAccount(accountId: String, deleteCache: Boolean): Boolean {
        ensureWritable()
        val wasActive = accountId == activeAccountId
        // serve 一律清会话缓存；[deleteCache] 这一端没有本机课表缓存可清。
        val result = requireOk(api.deleteAccount(accountId, confirm = true), "删除账号")
        result.snapshot?.let(::applySnapshot)
        // 与 serve 一致：删的是当前账号 ⇒ 退出登录，不自动切到下一条，页顶请用户再切。
        loggedOutAfterDelete = wasActive && activeAccountId == null
        return true
    }

    /** 页顶那一行说明（随服务端的能力开关走；可写且未刚退出时不显示）。 */
    fun gapNote(): String = when {
        readOnly -> "当前是明文 HTTP，账号改动需要先开启 HTTPS（给 serve 加上 --https）。"
        loggedOutAfterDelete -> "当前账号已删除，serve 现已退出登录。请切换到其他账号。"
        else -> ""
    }

    private fun applySnapshot(snapshot: ServeAccounts) {
        readOnly = snapshot.readOnly
        // 打码后的 id 就是句柄（也是屏上的显示名）。密码不投影，保持空串。
        rows = snapshot.accounts.map {
            Account(
                accountId = it.id,
                accountType = it.accountType,
                lastUsedAt = it.lastUsedAt ?: 0L,
            )
        }
        activeAccountId = snapshot.accounts.firstOrNull { it.active }?.id
    }

    /** 明文形态下不发写请求，避免密码走在 HTTP 上。 */
    private fun ensureWritable() {
        if (readOnly) throw WebAccountsNotWritable(HTTPS_REQUIRED_HINT)
    }

    private fun requireOk(result: ServeAccountsWrite, action: String): ServeAccountsWrite {
        if (!result.ok) {
            val message = result.message.trim().ifBlank { "${action}失败" }
            throw WebAccountsNotWritable(message)
        }
        return result
    }

    private fun Throwable.toUserFacing(action: String): Throwable =
        if (this is WebAccountsNotWritable) this
        else WebAccountsNotWritable(FriendlyError.of(this, action))
}

/** 「这一端这次写动作做不到」：实现 [UserFacingFailure] 让 `FriendlyError` 把文案原样交给 toast。 */
private class WebAccountsNotWritable(message: String) : IllegalStateException(message), UserFacingFailure

private const val HTTPS_REQUIRED_HINT =
    "当前是明文 HTTP，账号改动需要先开启 HTTPS（给 serve 加上 --https）。"

/** 账号管理那一屏（serve 形态）。 */
@Composable
internal fun WebAccountsScreen(
    client: HttpClient,
    mode: ApiMode,
    onBack: () -> Unit,
) {
    val accounts = remember(client, mode) {
        WebAccountsController(CampusAccountsApi(client, API_BASE, mode))
    }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(accounts, attempt) { accounts.load() }

    Column(Modifier.fillMaxSize()) {
        val note = accounts.gapNote()
        if (note.isNotBlank()) WebGapNote(note)
        Box(Modifier.weight(1f)) {
            val message = accounts.errorMessage
            when {
                accounts.loading -> FullPageState(Modifier.fillMaxSize()) {
                    LoadingState("正在读取账号表…", Modifier.fillMaxSize())
                }

                message != null -> FullPageState(Modifier.fillMaxSize()) {
                    ErrorState(message = message, onRetry = { attempt++ }, modifier = Modifier.fillMaxSize())
                }

                else -> AccountManagerScreen(
                    accountManager = accounts,
                    activeAccountId = accounts.activeAccountId,
                    // 头像那一格是宿主缝：学工系统的证件照缓存在 `:app`（要 Context 与 Bitmap），
                    // 这一端不传 ⇒ 走首字母圆那一档（与桌面端同一条，见 AccountPhotoSource 的 KDoc）。
                    accountPhoto = null,
                    onBack = onBack,
                )
            }
        }
    }
}
