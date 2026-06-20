package com.xjtu.toolbox.account

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginState
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.util.AppDatabase
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.util.PersistentCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 多账号编排器。统一处理切换、新增、删除、登出。
 *
 * 持有 [SessionManager] 与 [AppLoginStateHolder] 的引用，切换账号时：
 *  1. 清空旧账号内存会话状态（[AppLoginStateHolder.clearInMemorySessionState]）
 *  2. 用目标账号命名空间重建 SessionManager backends（[SessionManager.reconfigureForAccount]）
 *  3. 从 [AccountStore] 载入目标账号身份到内存（[AppLoginStateHolder.loadIdentityFromAccount]）
 *  4. 更新 [AccountContext.activeAccountId]、AccountStore.lastUsedAt
 *  5. 触发懒加载热加载（由 UI 层观察 [AppLoginStateHolder.accountId] 变化驱动）
 *
 * 「切换模式」：同一时刻仅一个账号在线，其余账号的 cookies/缓存静默躺在磁盘命名空间里，
 * 切回时由步骤 2 的 cookieJar 实例化直接复用，无需重新登录（除非服务端 session 已过期）。
 */
class AccountManager(
    private val context: Context,
    private val accountStore: AccountStore,
    private val credentialStore: CredentialStore,
) {

    lateinit var sessionManager: SessionManager
        internal set
    lateinit var holder: AppLoginStateHolder
        internal set

    /** 序列化所有账号切换/新增/删除/登出操作，避免并发导致命名空间与 AccountContext 错位。 */
    private val switchLock = Mutex()

    fun accountList(): List<Account> = accountStore.list()
    fun activeAccount(): Account? = accountStore.activeAccount()
    fun accountCount(): Int = accountStore.list().size

    /**
     * 切换到目标账号。幂等：若已是当前账号则仅更新 lastUsedAt。
     * @return 切换后的 [Account]；目标不存在则返回 null。
     */
    suspend fun switchTo(accountId: String): Account? = switchLock.withLock {
        switchToLocked(accountId)
    }

    private suspend fun switchToLocked(accountId: String): Account? {
        val account = accountStore.get(accountId) ?: run {
            Log.w(TAG, "switchTo: account $accountId not found")
            return null
        }
        if (AccountContext.activeAccountId == accountId && holder.accountId == accountId) {
            // 已是当前账号：仅刷新 lastUsedAt
            accountStore.update(accountId) { it.copy(lastUsedAt = System.currentTimeMillis()) }
            return account
        }

        Log.i(TAG, "Switching account: ${AccountContext.activeAccountId} -> $accountId")
        // 1) 清旧账号内存态
        holder.clearInMemorySessionState()
        // 2) 重建 SessionManager backends（旧 cookieJar 磁盘保留以便切回）
        val suffix = "_" + accountId.replace(Regex("[^a-zA-Z0-9]"), "_")
        sessionManager.reconfigureForAccount(suffix)
        // 3) 载入新账号身份（内部会设置 AccountContext.activeAccountId）
        holder.loadIdentityFromAccount(account)
        // 4) 持久化激活指针 + lastUsedAt
        accountStore.setActive(accountId)
        accountStore.update(accountId) { it.copy(lastUsedAt = System.currentTimeMillis()) }
        return account
    }

    /**
     * 新增账号：先切换到新账号命名空间做一次 JWXT 探活登录，成功后落库。
     *
     * @return Result：成功返回新增的 [Account]；失败返回错误信息。
     * 失败时自动切回原激活账号，不留中间态。
     */
    suspend fun addAccount(
        username: String,
        password: String,
        accountType: AccountType,
    ): Result<Account> = withContext(Dispatchers.IO) {
        switchLock.withLock {
            addAccountLocked(username, password, accountType)
        }
    }

    private suspend fun addAccountLocked(
        username: String,
        password: String,
        accountType: AccountType,
    ): Result<Account> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("学号和密码不能为空"))
        }
        if (accountStore.get(username) != null) {
            return Result.failure(IllegalArgumentException("该账号已存在"))
        }

        val previousActiveId = AccountContext.activeAccountId
        // 构造临时 Account 用于载入身份（尚未落库）
        val tempAccount = Account(
            accountId = username,
            password = password,
            accountType = accountType,
            lastUsedAt = System.currentTimeMillis(),
        )

        // 切到新账号命名空间
        holder.clearInMemorySessionState()
        val suffix = "_" + username.replace(Regex("[^a-zA-Z0-9]"), "_")
        sessionManager.reconfigureForAccount(suffix)
        holder.loadIdentityFromAccount(tempAccount)

        // 探活：尝试 JWXT 登录。MFA 由 SessionManager 状态机驱动 UI 弹窗。
        val loginResult = runCatching {
            sessionManager.ensureSite(com.xjtu.toolbox.auth.LoginType.JWXT)
            sessionManager.ensureSite(com.xjtu.toolbox.auth.LoginType.YWTB)
        }

        if (loginResult.isFailure) {
            // 回滚到原账号
            val cause = loginResult.exceptionOrNull() ?: IllegalStateException("登录失败")
            Log.w(TAG, "addAccount login failed: ${cause.message}")
            rollbackTo(previousActiveId)
            return Result.failure(cause)
        }

        // 登录成功：捕获 nickname / fpVisitorId / rsaKey 后落库
        val nickname = holder.ywtbUserInfo?.userName
        val persisted = tempAccount.copy(
            nickname = nickname,
            fpVisitorId = sessionManager.fpVisitorId ?: tempAccount.fpVisitorId,
            rsaPublicKey = sessionManager.cachedRsaKey ?: tempAccount.rsaPublicKey,
            rsaKeyTime = if (sessionManager.cachedRsaKey != null) System.currentTimeMillis() else 0L,
        )
        accountStore.upsert(persisted, setActive = true)
        accountStore.update(username) { it.copy(lastUsedAt = System.currentTimeMillis()) }
        Log.i(TAG, "addAccount ok: $username")
        return Result.success(persisted)
    }

    /**
     * 更新账号密码（用户在设置页改密后调用）。同步清除密码失效熔断。
     */
    fun updatePassword(accountId: String, newPassword: String) {
        accountStore.update(accountId) { it.copy(password = newPassword) }
        if (AccountContext.activeAccountId == accountId) {
            holder.savedPassword = newPassword
            sessionManager.setCredentials(accountId, newPassword)
        }
    }

    /**
     * 更新当前账号昵称（YWTB 拉取到全名后缓存）。
     */
    fun updateNickname(accountId: String, nickname: String?) {
        if (nickname.isNullOrBlank()) return
        accountStore.update(accountId) { it.copy(nickname = nickname) }
    }

    /**
     * 持久化当前登录得到的 fpVisitorId / rsaKey 到账号记录。
     * 登录链路完成后调用，保证下次切换切回时复用同一设备指纹。
     */
    fun persistSessionArtifacts(accountId: String) {
        accountStore.update(accountId) {
            it.copy(
                fpVisitorId = sessionManager.fpVisitorId ?: it.fpVisitorId,
                rsaPublicKey = sessionManager.cachedRsaKey ?: it.rsaPublicKey,
                rsaKeyTime = if (sessionManager.cachedRsaKey != null) System.currentTimeMillis() else it.rsaKeyTime,
            )
        }
    }

    /**
     * 「我的」页首次登录成功后落库当前账号。
     * 与 [addAccount] 不同：本方法假定 SessionManager 已完成 JWXT 登录，不再重复探活，
     * 仅把当前内存态身份 + 会话产物写入 AccountStore。
     */
    fun persistCurrentLogin(username: String, password: String, accountType: AccountType) {
        val nickname = holder.ywtbUserInfo?.userName
        // fpVisitorId fallback：JWXT 登录链可能尚未把 fp 写回 SessionManager，
        // 用已有账号记录里的 fp（切换切回时）兜底，避免落库 null 导致下次切回重新触发 MFA。
        val existingFp = accountStore.get(username)?.fpVisitorId
        val existingRsa = accountStore.get(username)?.rsaPublicKey
        val account = Account(
            accountId = username,
            password = password,
            accountType = accountType,
            nickname = nickname,
            fpVisitorId = sessionManager.fpVisitorId ?: existingFp,
            rsaPublicKey = sessionManager.cachedRsaKey ?: existingRsa,
            rsaKeyTime = if (sessionManager.cachedRsaKey != null) System.currentTimeMillis() else 0L,
            lastUsedAt = System.currentTimeMillis(),
        )
        accountStore.upsert(account, setActive = true)
        holder.accountId = username
        holder.accountType = accountType
        com.xjtu.toolbox.account.AccountContext.activeAccountId = username
    }

    /**
     * 删除账号：清除其全部命名空间存储（cookies / DataCache / Agent 会话 / 校园卡缓存 / Room 行）。
     * @param deleteCache true=同时删除本地缓存（课表/成绩/对话）；false=仅删凭据与 cookies。
     */
    suspend fun removeAccount(accountId: String, deleteCache: Boolean): Boolean = withContext(Dispatchers.IO) {
        switchLock.withLock { removeAccountLocked(accountId, deleteCache) }
    }

    private suspend fun removeAccountLocked(accountId: String, deleteCache: Boolean): Boolean {
        val account = accountStore.get(accountId) ?: return false
        val suffix = "_" + accountId.replace(Regex("[^a-zA-Z0-9]"), "_")
        val appContext = context.applicationContext

        // cookies
        runCatching { PersistentCookieJar(appContext, "cookies_normal$suffix").clear() }
        runCatching { PersistentCookieJar(appContext, "cookies_webvpn$suffix").clear() }

        if (deleteCache) {
            // DataCache 目录
            runCatching { File(appContext.cacheDir, "data_cache$suffix").deleteRecursively() }
            // Agent / 交晓智 会话目录
            runCatching { File(appContext.filesDir, "agent_sessions$suffix").deleteRecursively() }
            runCatching { File(appContext.filesDir, "jiaoxiaozhi_sessions$suffix").deleteRecursively() }
            // 校园卡缓存 prefs：临时把 AccountContext 指向待删账号以让 CampusCardCache.clear 命中正确命名空间，
            // try-finally 保证无论是否异常都恢复原值，避免 AccountContext 卡在已删除账号上。
            val prevActive = AccountContext.activeAccountId
            runCatching {
                AccountContext.activeAccountId = accountId
                CampusCardCache.clear(appContext)
                appContext.getSharedPreferences("campus_card$suffix", Context.MODE_PRIVATE).edit().clear().apply()
            }
            AccountContext.activeAccountId = prevActive
            // Room 自定义课程
            runCatching {
                AppDatabase.getInstance(appContext).customCourseDao().deleteByAccount(accountId)
            }
        }

        accountStore.remove(accountId)

        // 若删的是当前账号：切到剩余账号或登出
        if (AccountContext.activeAccountId == accountId) {
            val remaining = accountStore.list()
            if (remaining.isNotEmpty()) {
                switchToLocked(remaining.first().accountId)
            } else {
                holder.clearInMemorySessionState()
                AccountContext.activeAccountId = null
                sessionManager.reconfigureForAccount("_default")
                accountStore.clearActive()
            }
        }
        Log.i(TAG, "removeAccount($accountId, deleteCache=$deleteCache) done")
        return true
    }

    /**
     * 登出当前账号：仅清当前 session cookies + 内存态，保留账号记录与缓存。
     * 下次切换该账号时 cookieJar 重建，需重新走 CAS（可能 MFA）。
     */
    suspend fun logoutCurrent() = switchLock.withLock {
        val id = AccountContext.activeAccountId ?: return@withLock
        val suffix = "_" + id.replace(Regex("[^a-zA-Z0-9]"), "_")
        // 1) 先清磁盘 cookie（当前账号命名空间）
        runCatching { PersistentCookieJar(context.applicationContext, "cookies_normal$suffix").clear() }
        runCatching { PersistentCookieJar(context.applicationContext, "cookies_webvpn$suffix").clear() }
        // 2) 先置空 AccountContext，让后续 IO 回退到 default 命名空间，避免用旧 id 写新 backend
        AccountContext.activeAccountId = null
        // 3) 清内存态 + 重建 backends 到 default 命名空间
        holder.clearInMemorySessionState()
        sessionManager.reconfigureForAccount("_default")
        accountStore.clearActive()
    }

    private suspend fun rollbackTo(previousActiveId: String?) {
        holder.clearInMemorySessionState()
        if (previousActiveId != null && accountStore.get(previousActiveId) != null) {
            val prev = accountStore.get(previousActiveId)!!
            val suffix = "_" + previousActiveId.replace(Regex("[^a-zA-Z0-9]"), "_")
            sessionManager.reconfigureForAccount(suffix)
            holder.loadIdentityFromAccount(prev)
            accountStore.setActive(previousActiveId)
        } else {
            sessionManager.reconfigureForAccount("_default")
            AccountContext.activeAccountId = null
        }
    }

    companion object {
        private const val TAG = "AccountManager"
    }
}

/**
 * AppLoginState 的最小契约。MainActivity.AppLoginState 实现此接口，
 * 让 AccountManager 不直接依赖 AppLoginState 具体类型，便于测试与隔离。
 */
interface AppLoginStateHolder {
    var accountId: String
    var savedUsername: String
    var savedPassword: String
    var accountType: AccountType
    var activeUsername: String
    var cachedNickname: String?
    var ywtbUserInfo: com.xjtu.toolbox.ywtb.UserInfo?
    fun clearInMemorySessionState()
    fun loadIdentityFromAccount(account: Account)
}
