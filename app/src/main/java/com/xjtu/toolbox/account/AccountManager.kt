package com.xjtu.toolbox.account

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.auth.AccessMode
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.data.SecurePrefs
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.network.PersistentCookieJar
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

    /** 清空 WebView 的 localStorage / sessionStorage / IndexedDB。WebStorage 只能在主线程调用；失败不影响切换。 */
    private fun clearWebViewStorage() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
                .onFailure { Log.w(TAG, "clear WebStorage failed: ${it.message}") }
        }
    }

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
        // 1.5) 清 WebView 本地存储：移动交大等页面把令牌存在 localStorage（cookie 清不掉），
        // WebVPN 下各站点还共用同一个 origin，不清会让新账号捡到上一个账号仍有效的令牌。
        clearWebViewStorage()
        // 2) 重建 SessionManager backends（旧 cookieJar 磁盘保留以便切回）
        sessionManager.reconfigureForAccount(AccountContext.suffixFor(accountId))
        // 3) 载入新账号身份（内部会设置 AccountContext.activeAccountId）
        holder.loadIdentityFromAccount(account)
        runCatching { holder.ensureCampusDetected() }
            .onFailure { Log.w(TAG, "switchTo campus detect failed: ${it.message}") }
        // 4) 持久化激活指针 + lastUsedAt
        accountStore.setActive(accountId)
        accountStore.update(accountId) { it.copy(lastUsedAt = System.currentTimeMillis()) }
        // 5) 通知 UI：避免在「我的」页面改信息时误以为是当前账号。
        // 显式切到 Main 线程：mutableStateOf 的写入对其他线程不可见，必须在主线程上 set。
        withContext(Dispatchers.Main) {
            holder.switchNotice = "已切换到「${account.nickname ?: account.accountId}」"
        }
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
        sessionManager.reconfigureForAccount(AccountContext.suffixFor(username))
        holder.loadIdentityFromAccount(tempAccount)
        runCatching { holder.ensureCampusDetected() }
            .onFailure { Log.w(TAG, "addAccount campus detect failed: ${it.message}") }

        // 探活：尝试 JWXT 登录。MFA 由 SessionManager 状态机驱动 UI 弹窗。
        val loginResult = runCatching {
            sessionManager.ensureSite(com.xjtu.toolbox.auth.LoginType.JWXT, userInitiated = true)
            sessionManager.ensureSite(com.xjtu.toolbox.auth.LoginType.YWTB, userInitiated = true)
        }

        if (loginResult.isFailure) {
            // 回滚到原账号
            val cause = loginResult.exceptionOrNull() ?: IllegalStateException("登录失败")
            Log.w(TAG, "addAccount login failed: ${cause.message}")
            rollbackTo(previousActiveId)
            wipeAccountFiles(username, cookiesOnly = false) // 这个账号没落库，登录过程留下的文件一并清掉
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
        withContext(Dispatchers.Main) {
            holder.switchNotice = "已添加并切换到「${nickname ?: username}」"
        }
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
     * 「我的」页首次登录成功后落库当前账号。与 [addAccount] 不同：假定 JWXT 已登录，不再探活。
     * 登录发生在匿名命名空间，这里把会话 cookie 搬进账号命名空间，匿名罐清空。
     */
    suspend fun persistCurrentLogin(username: String, password: String, accountType: AccountType) =
        withContext(Dispatchers.IO) {
            switchLock.withLock {
                // 登录链可能还没把 fp 写回 SessionManager，用旧记录兜底，免得下次切回又触发 MFA
                val existing = accountStore.get(username)
                val account = Account(
                    accountId = username,
                    password = password,
                    accountType = accountType,
                    nickname = holder.ywtbUserInfo?.userName,
                    fpVisitorId = sessionManager.fpVisitorId ?: existing?.fpVisitorId,
                    rsaPublicKey = sessionManager.cachedRsaKey ?: existing?.rsaPublicKey,
                    rsaKeyTime = if (sessionManager.cachedRsaKey != null) System.currentTimeMillis() else 0L,
                    lastUsedAt = System.currentTimeMillis(),
                )
                accountStore.upsert(account, setActive = true)
                val previous = AccountContext.activeAccountId
                if (previous != username) {
                    val suffix = AccountContext.suffixFor(username)
                    if (previous == null) sessionManager.adoptAnonymousSession(suffix)
                    else sessionManager.reconfigureForAccount(suffix)
                }
                holder.loadIdentityFromAccount(account)
            }
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

        // 当前账号要清正在用的 jar，否则 debounce 写盘会把 TGC 写回
        if (AccountContext.activeAccountId == accountId) {
            runCatching { AccessMode.entries.forEach { sessionManager.backend(it).clearAuth() } }
        }
        wipeAccountFiles(accountId, cookiesOnly = !deleteCache)

        accountStore.remove(accountId)

        // 若删的是当前账号：切到剩余账号或登出
        if (AccountContext.activeAccountId == accountId) {
            val remaining = accountStore.list()
            if (remaining.isNotEmpty()) {
                switchToLocked(remaining.first().accountId)
            } else {
                holder.clearInMemorySessionState()
                AccountContext.activeAccountId = null
                sessionManager.reconfigureForAnonymous()
                accountStore.clearActive()
            }
        }
        Log.i(TAG, "removeAccount($accountId, deleteCache=$deleteCache) done")
        withContext(Dispatchers.Main) {
            holder.switchNotice = if (deleteCache) "已彻底删除账号「${account.nickname ?: account.accountId}」及本地缓存"
                else "已删除账号「${account.nickname ?: account.accountId}」（保留缓存）"
        }
        return true
    }

    /**
     * 登出当前账号：清当前 session cookies + 内存态，保留账号记录与缓存。
     * 下次切换该账号时需重新走 CAS（可能 MFA）。
     */
    suspend fun logoutCurrent() = switchLock.withLock {
        val id = AccountContext.activeAccountId ?: return@withLock
        // 清正在用的 jar（取消 debounce 写盘），不要另 new 一份同名 jar：活着的那份仍会把 TGC 写回
        runCatching {
            AccessMode.entries.forEach { sessionManager.backend(it).clearAuth() }
        }
        AccountContext.activeAccountId = null
        holder.clearInMemorySessionState()
        sessionManager.reconfigureForAnonymous()
        accountStore.clearActive()
        Log.i(TAG, "logoutCurrent($id) done")
    }

    /** 新增账号失败后退回原账号（没有原账号就回到未登录）。 */
    private suspend fun rollbackTo(previousActiveId: String?) {
        holder.clearInMemorySessionState()
        val prev = previousActiveId?.let { accountStore.get(it) }
        if (prev != null) {
            sessionManager.reconfigureForAccount(AccountContext.suffixFor(prev.accountId))
            holder.loadIdentityFromAccount(prev)
            accountStore.setActive(prev.accountId)
            // clearInMemorySessionState 把网络探测结果也清了，回滚后补测一次
            runCatching { holder.ensureCampusDetected() }
        } else {
            sessionManager.reconfigureForAnonymous()
            AccountContext.activeAccountId = null
        }
    }

    /**
     * 清掉某账号命名空间下的落盘数据。[cookiesOnly] 时只清会话 cookie，否则连本地缓存、
     * 头像、Agent 配置（含 API Key）与对话、Room 自定义课程一并清。
     * 直接按该账号的后缀清，不去临时改全局 [AccountContext]。
     */
    private suspend fun wipeAccountFiles(accountId: String, cookiesOnly: Boolean) {
        val app = context.applicationContext
        val suffix = AccountContext.suffixFor(accountId)
        for (name in listOf("cookies_normal", "cookies_webvpn")) {
            runCatching { PersistentCookieJar(app, name + suffix).clear() }
        }
        if (cookiesOnly) return

        runCatching { File(app.cacheDir, "data_cache$suffix").deleteRecursively() }
        runCatching { File(app.cacheDir, "avatar$suffix.jpg").delete() }
        runCatching { File(app.cacheDir, "avatar$suffix.url").delete() }
        for (name in listOf("agent_sessions$suffix", "agent_images$suffix", "avatar$suffix.jpg",
            "avatar$suffix.url", "avatar_custom$suffix.jpg")) {
            runCatching { File(app.filesDir, name).deleteRecursively() }
        }
        for (name in ACCOUNT_PREFS) {
            runCatching { app.getSharedPreferences(name + suffix, Context.MODE_PRIVATE).edit().clear().commit() }
        }
        // 加密的 Agent 配置（API Key）：文件不存在就别为了清它去创建
        val secureName = "agent_config_secure$suffix"
        if (File(app.applicationInfo.dataDir, "shared_prefs/$secureName.xml").exists()) {
            runCatching { SecurePrefs.open(app, secureName).edit().clear().commit() }
        }
        runCatching { AppDatabase.getInstance(app).customCourseDao().deleteByAccount(accountId) }
    }

    companion object {
        private const val TAG = "AccountManager"

        /** 按账号后缀存放的普通 SharedPreferences 名。各 Store 自己拼后缀，改名要同步这里。 */
        private val ACCOUNT_PREFS = listOf(
            "campus_card", "score_cursor", "attendance_watch", "course_colors", "schedule_diff",
            "empty_room_cache", "attendance_records_ug", "attendance_records_pg", "agent_config",
            "schedule_source", "schedule_changes",
        )
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
    /**
     * 账号切换/新增/删除的即时事件：UI 层用 `LaunchedEffect` 监听后弹 Snackbar / Toast。
     *
     * 设计为"一次性事件"模式：消费后由消费者调用 [consumeSwitchNotice] 重置为 null，
     * 避免配置变更/重组时重复弹出。
     */
    var switchNotice: String?
    fun clearInMemorySessionState()
    fun loadIdentityFromAccount(account: Account)
    suspend fun ensureCampusDetected()
    /** 取出并清空切换通知。 */
    fun consumeSwitchNotice(): String?
}
