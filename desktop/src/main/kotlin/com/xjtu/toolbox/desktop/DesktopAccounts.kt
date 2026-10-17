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
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 多账号在桌面端的编排那一半 —— `:core` 的 `AccountsController` 缝的实现。 */
internal class DesktopAccounts(
    private val auth: DesktopAuth,
    private val store: AccountStore = auth.accountStore,
) : AccountsController {

    /** 序列化切换 / 新增 / 删除。 */
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
            auth.sessionManager.ensureSite(DesktopAuth.JWXT_SITE_KEY, userInitiated = true)
            auth.sessionManager.ensureSite(DesktopAuth.YWTB_SITE_KEY, userInitiated = true)
        }

        if (probe.isFailure) {
            val cause = probe.exceptionOrNull() ?: IllegalStateException("登录失败")
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

    /** 删掉这个账号在本端的本地缓存（`deleteCache = true` 那一档）。 */
    private fun wipeDesktopCaches(accountId: String) {
        runCatching {
            keyValueStore(accountScopedStoreName(DesktopScoreReportCache.STORE_NAME, accountId))
                .remove(DesktopScoreReportCache.keyFor(accountId))
            secureKeyValueStore(accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, accountId)).clear()
            secureKeyValueStore(accountScopedStoreName(DesktopJiaocai1ShelfStore.STORE_NAME, accountId)).clear()
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}

/** 外壳每次组合建一个（与 `DesktopAuth` 同生命周期）：它只持有 `auth` 与那份账号表，自身没有状态。 */
@Composable
internal fun rememberDesktopAccounts(auth: DesktopAuth): DesktopAccounts =
    remember(auth) { DesktopAccounts(auth) }
