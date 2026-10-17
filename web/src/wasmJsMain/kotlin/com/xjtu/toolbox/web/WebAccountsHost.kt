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
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.UserFacingFailure
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.LoadingState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

/** 账号管理（`AppRoute.Accounts`）在浏览器这一端的宿主（契约 §5.10）。 */
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

    private var rows: List<Account> = emptyList()

    /** 打一次 `GET /api/accounts`。幂等，屏可在重试时反复调。 */
    suspend fun load() {
        loading = true
        errorMessage = null
        try {
            val snapshot = api.accounts()
            readOnly = snapshot.readOnly
            // 打码后的 id 就是句柄（也是屏上的显示名）：共享屏只认 Account.accountId 那一格。
            // `lastUsedAt` 为 null（服务端明确说没有）⇒ 屏上落「未使用」那一档，不编一个时刻。
            rows = snapshot.accounts.map {
                Account(
                    accountId = it.id,
                    accountType = it.accountType,
                    lastUsedAt = it.lastUsedAt ?: 0L,
                )
            }
            activeAccountId = snapshot.accounts.firstOrNull { it.active }?.id
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

    override suspend fun switchTo(accountId: String): Account? = notWritable()

    override suspend fun addAccount(
        username: String,
        password: String,
        accountType: AccountType,
    ): Result<Account> = Result.failure(WebAccountsNotWritable(hint()))

    override fun updatePassword(accountId: String, newPassword: String) = notWritable()

    override suspend fun removeAccount(accountId: String, deleteCache: Boolean): Boolean = notWritable()

    /** 页顶那一行说明（随服务端的能力开关走）。 */
    fun gapNote(): String = if (readOnly) {
        "账号表由 serve 进程读（与桌面端共用同一份 xjtu_accounts，见 docs/agent-web-spec.md 那一条）。" +
            "这一端只读：切换 / 新增 / 删除 / 改密要在 App 或桌面端做 —— 服务端那条端点把能力开关 " +
            "readOnly 如实报成 true（docs/api-contract.md §5.10）。"
    } else {
        "服务端如实报了 readOnly:false，但这一端还没接线写动作（本批只做了只读那一半，" +
            "docs/api-contract.md §5.10）：切换 / 新增 / 删除 / 改密仍要去 App 或桌面端做。"
    }

    private fun hint(): String = if (readOnly) {
        "这一端只读：切换 / 新增 / 删除 / 改密要在 App 或桌面端做（serve 只端了只读的 /api/accounts）"
    } else {
        "服务端已经能改，但这一端还没接线写动作（本批只做了只读那一半，见 docs/api-contract.md §5.10）"
    }

    private fun notWritable(): Nothing = throw WebAccountsNotWritable(hint())
}

/** 「这一端没有写动作」：实现 [UserFacingFailure] 让 `FriendlyError` 把 [hint] 原样交给 toast。 */
private class WebAccountsNotWritable(message: String) : IllegalStateException(message), UserFacingFailure

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
        WebGapNote(accounts.gapNote())
        Box(Modifier.weight(1f)) {
            val message = accounts.errorMessage
            when {
                accounts.loading -> FullPageState(Modifier.fillMaxSize()) {
                    LoadingState("正在读取账号表...", Modifier.fillMaxSize())
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
