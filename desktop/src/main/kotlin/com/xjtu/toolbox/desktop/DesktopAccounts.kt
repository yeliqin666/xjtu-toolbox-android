package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.account.AccountsController
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.SessionBackend
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 多账号在桌面端的**编排**那一半 —— `:core` 的 `AccountsController` 缝的实现。
 *
 * ## 与 `:app` 的 `AccountManager` 是什么关系
 *
 * 同一层东西、同一套顺序（先清旧命名空间、再换绑、再落库；失败要回滚，不留中间态），
 * 差别只有「宿主能力」那几件：
 *
 * | | `:app`（`AccountManager`） | 桌面（这里） |
 * |---|---|---|
 * | 切账号时清场 | WebView 的 localStorage/IndexedDB、Room 自定义课程、按账号的 SharedPreferences、头像 | 只换会话命名空间（桌面没有那几样） |
 * | 删账号时的「本地缓存」 | `data_cache_<后缀>` 目录、头像、agent 会话、Room 行 | 桌面**唯一**按账号分的缓存：成绩报表的 SWR（键 `score_report_<学号>`） |
 * | 昵称 | 一网通办回填（`holder.ywtbUserInfo`） | **没有** ⇒ `nickname` 一直是 null（如实降级，见下） |
 * | 新增账号的探活 | 硬要求 JWXT + YWTB | 同上（两个站点在桌面都注册了、都是真取数的站点） |
 *
 * 命名空间与界面状态的切换本身在 [DesktopAuth.adoptAccount] / [DesktopAuth.dropToAnonymous]
 * —— 那几样是它的私有状态，这里只管「谁、什么顺序、失败了怎么办」。
 *
 * ## 如实降级（TODO）
 *
 * - **昵称**：`:app` 在登录后从一网通办拉一次全名缓存进 `Account.nickname`（欢迎卡片秒显）。
 *   桌面端的 `:data` 里没有那份「取用户名」的调用（`YwtbSession` 只留了 `id_token` 与
 *   `onYwtbToken` 钩子）⇒ 账号列表里显示的是学号本身。要补的话是 `:data` 侧加一个
 *   「YWTB 取用户信息」的出口，不在这一轮。
 * - **`deleteCache` 的清场范围**：桌面按账号分的落盘只有成绩报表那一条缓存，见 [wipeDesktopCaches]。
 *   将来哪一屏加了按账号的缓存，就往那个列表里补一行。
 */
internal class DesktopAccounts(
    private val auth: DesktopAuth,
    private val store: AccountStore = auth.accountStore,
) : AccountsController {

    /**
     * 序列化切换 / 新增 / 删除：三者都会改会话命名空间与 `AccountContext`，并发跑会错位
     * （与 `:app` 的 `AccountManager.switchLock` 同一条理由、同一个粒度）。
     */
    private val switchLock = Mutex()

    override fun accountList(): List<Account> = store.list()

    override suspend fun switchTo(accountId: String): Account? = switchLock.withLock {
        val account = store.get(accountId) ?: return@withLock null
        if (AccountContext.activeAccountId == accountId) {
            // 幂等：已是当前账号，只刷新 lastUsedAt（与 :app 的 switchToLocked 同一条）
            return@withLock store.update(accountId) { it.copy(lastUsedAt = nowMs()) } ?: account
        }
        auth.adoptAccount(account)
        store.setActive(accountId)
        store.update(accountId) { it.copy(lastUsedAt = nowMs()) } ?: account
    }

    override suspend fun addAccount(
        username: String,
        password: String,
        accountType: AccountType,
    ): Result<Account> = switchLock.withLock {
        val user = username.trim()
        // 与 `:app` 的 `AccountManager.addAccountLocked` 同一判据：两个都按 `isBlank()`
        // （而不是 `isEmpty()`）—— 全是空格不算填了。
        if (user.isBlank() || password.isBlank()) {
            return@withLock Result.failure(IllegalArgumentException("学号和密码不能为空"))
        }
        if (store.get(user) != null) {
            return@withLock Result.failure(IllegalArgumentException("该账号已存在"))
        }

        val previousActiveId = AccountContext.activeAccountId
        // 先切到新账号的命名空间做一次探活登录（此时还**没有**落库）
        val draft = Account(
            accountId = user,
            password = password,
            accountType = accountType,
            lastUsedAt = nowMs(),
        )
        auth.adoptAccount(draft)

        val probe = runCatching {
            // 与 `:app` 的 addAccount 同一个判据：教务 + 一网通办都要能进 —— 它们都是桌面真取数的
            // 站点（全校课表/成绩/评教 与 消息收纳），一条只能登统一认证、进不了教务的凭据在这里
            // 加进来没有意义。MFA 由会话内核的状态机驱动窗口里的弹窗（与登录页那一步同一套）。
            auth.sessionManager.ensureSite(DesktopAuth.JWXT_SITE_KEY, userInitiated = true)
            auth.sessionManager.ensureSite(DesktopAuth.YWTB_SITE_KEY, userInitiated = true)
        }

        if (probe.isFailure) {
            val cause = probe.exceptionOrNull() ?: IllegalStateException("登录失败")
            // 回滚到原账号（没有原账号就回匿名），并把这次探活留下的文件一并清掉 —— 这个账号
            // 没落库，留下的 cookie / 快照没人认领（与 :app 的 wipeAccountFiles 同一件事）。
            rollback(previousActiveId)
            runCatching { SessionBackend.wipe(AccountContext.suffixFor(user)) }
            return@withLock Result.failure(cause)
        }

        val persisted = draft.copy(
            fpVisitorId = auth.sessionManager.fpVisitorId ?: draft.fpVisitorId,
            rsaPublicKey = auth.sessionManager.cachedRsaKey ?: draft.rsaPublicKey,
            rsaKeyTime = if (auth.sessionManager.cachedRsaKey != null) nowMs() else 0L,
            lastUsedAt = nowMs(),
        )
        store.upsert(persisted, setActive = true)
        Result.success(persisted)
    }

    override fun updatePassword(accountId: String, newPassword: String) {
        store.update(accountId) { it.copy(password = newPassword) }
        if (AccountContext.activeAccountId == accountId) {
            auth.sessionManager.setCredentials(accountId, newPassword)
        }
    }

    override suspend fun removeAccount(accountId: String, deleteCache: Boolean): Boolean = switchLock.withLock {
        if (store.get(accountId) == null) return@withLock false

        // 当前账号要先把正在用的会话放掉，否则 debounce 写盘会把 TGC 写回刚清掉的文件
        if (AccountContext.activeAccountId == accountId) {
            auth.sessionManager.clearCredentials()
            auth.sessionManager.forgetAllSites()
        }
        SessionBackend.wipe(AccountContext.suffixFor(accountId))
        if (deleteCache) wipeDesktopCaches(accountId)

        store.remove(accountId)

        if (AccountContext.activeAccountId == accountId) {
            val remaining = store.list()
            val next = remaining.firstOrNull()
            if (next != null) {
                auth.adoptAccount(next)
                store.setActive(next.accountId)
            } else {
                auth.dropToAnonymous()
                store.clearActive()
            }
        }
        true
    }

    /** 新增失败后回到原账号；没有原账号（或它已经不在了）就回匿名。 */
    private fun rollback(previousActiveId: String?) {
        val previous = previousActiveId?.let { store.get(it) }
        if (previous != null) {
            auth.adoptAccount(previous)
            store.setActive(previous.accountId)
        } else {
            auth.dropToAnonymous()
        }
    }

    /**
     * 删掉这个账号在本端的**本地缓存**（`deleteCache = true` 那一档）。
     *
     * 桌面按账号分的落盘目前只有一条：成绩报表的 SWR 缓存 —— 键与 `:app` 同一个
     * （`score_report_<学号>`，见 `DesktopScoreReportCache.keyFor`）。其余屏的缓存要么是按
     * 站点分的（在 `SessionBackend.wipe` 那一步清掉了），要么是本端全局的（设置 / 提示开关）。
     * 新屏加了按账号的缓存就往这里补一行。
     */
    private fun wipeDesktopCaches(accountId: String) {
        runCatching {
            keyValueStore(DesktopScoreReportCache.STORE_NAME).remove(DesktopScoreReportCache.keyFor(accountId))
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * 外壳每次组合建一个（与 `DesktopAuth` 同生命周期）：它只持有 `auth` 与那份账号表，
 * 自身没有状态 —— `remember` 在这里只是避免每帧新建一个 Mutex。
 */
@Composable
internal fun rememberDesktopAccounts(auth: DesktopAuth): DesktopAccounts =
    remember(auth) { DesktopAccounts(auth) }
