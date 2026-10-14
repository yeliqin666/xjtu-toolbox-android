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

/**
 * 账号管理（`AppRoute.Accounts`）在浏览器这一端的**宿主**（工单 S7a / D9，契约 §5.10）。
 *
 * ## 屏是 `:core` 的那一份，本文件只做两件事
 *
 * 1. **取数**：[CampusAccountsApi] 打 `GET /api/accounts`，把打码后的账号表与「serve 当前是哪个账号」
 *    读回来，再映射成 `:core` 的 [Account]（共享屏认的那个模型）；
 * 2. **动作**：一个都不做 —— 见下。
 *
 * ## 为什么四个写动作都如实失败（而不是假装能切）
 *
 * D9 的口径是「浏览器上切换账号 = 让 serve 换一套凭据」，而 serve 这一侧换凭据就是它自己的登录态
 * 迁移（命名空间 + 凭据 + 站点快照一起换）。本批按工单的「只读优先」**只端了读那一半**：
 * 服务端那一条端点的能力开关 `readOnly` 如实报 `true`（§5.10），这一端也就照它如实说 ——
 * [switchTo] / [addAccount] / [updatePassword] / [removeAccount] 一律抛 [WebAccountsNotWritable]
 * 或返回失败，文案是**给用户看的中文短句**（`FriendlyError` 对 [UserFacingFailure] 原样透出），
 * 告诉人「去 App 或桌面端做」，并说明为什么。屏上另有一行 [WebGapNote] 说同一件事。
 *
 * 这比画一个点了没反应的按钮诚实，也比把这一屏落成「还没搬到 Web」准确：**屏真在这里**
 * （同一份 `AccountManagerScreen`），能看见的东西（账号表、当前账号）是真数据。
 *
 * ## 那一行 note 为什么不写死
 *
 * `readOnly` 是**服务端如实答的**（§4 的能力开关）。服务端哪天端出写动作、这一端却还没接线，
 * note 会换成另一句（[gapNote]）而不是继续把「服务端不能」挂在自己头上。
 */
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

    /** 服务端如实答的能力开关（§5.10）；加载完成前按**只读**（保守那一侧）。 */
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

    /**
     * 页顶那一行如实说明（内容随服务端的能力开关走，见类 KDoc）。
     *
     * 给共享屏用（`:core` 那份屏不知道浏览器与 serve 的分工）。
     */
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

/**
 * 「这一端没有写动作」——[UserFacingFailure] 让 `FriendlyError` 把中文短句**原样**交给屏上的 toast
 * （不实现它的话，`FriendlyError` 会按网络/兜底分类，把 [hint] 里那句「去哪儿做」丢掉）。
 */
private class WebAccountsNotWritable(message: String) : IllegalStateException(message), UserFacingFailure

/**
 * 账号管理那一屏（serve 形态）。**屏本体是 `:core` 的 [AccountManagerScreen]** ——
 * 三端同一份屏、同一套层级；这里只做「先取数，再把它画出来，并在页顶写清这一端的缺口」。
 *
 * 加载中 / 取数失败两张态是本端自己画的（与 `WebDormPowerScreen` 那两张同一套组件），
 * 不拿一张空账号表冒充 ——「读不到」与「真的一个账号都没有」在屏上完全不同。
 *
 * @param client 这一份 `:web` 的 HTTP 客户端（带 serve 令牌，见 [ServeTokenHolder]）
 * @param mode 只该在 serve 形态下进来（campus-api 形态由 `AppPage` 落 `WebServeOnlyScreen`）
 * @param onBack 返回上一屏（壳给的导航；共享屏的返回箭头就是它）
 */
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
