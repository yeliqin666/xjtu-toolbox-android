package com.xjtu.toolbox.notification

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.MfaRequiredException
import com.xjtu.toolbox.auth.PasswordInvalidatedException
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "HeadlessSessions"

/**
 * 后台提醒任务用的会话入口。
 *
 * App 进程还活着时直接复用前台那一份 [SessionManager.active]；进程已经被系统回收时，
 * 按当前激活账号现建一个——这正是"关掉 App 也能收到提醒"要的东西。
 *
 * 建出来的这一份只在本进程内缓存，不去动前台的任何状态：后台任务不负责切账号，
 * 也不弹 MFA（一律 `silent = true`，撞上短信验证直接放弃这一轮）。
 */
internal object HeadlessSessions {

    private val lock = Mutex()

    @Volatile
    private var headless: SessionManager? = null

    @Volatile
    private var headlessAccountId: String? = null

    /**
     * 拿一个已登录的站点。没有账号、需要短信验证、密码已失效时返回 null：这些情况重试也没用，
     * 还会再提交一次密码，调用方应当直接结束这一轮。网络等临时故障照常抛出，由调用方决定重试。
     */
    suspend fun site(context: Context, type: LoginType): SiteSession? {
        val manager = manager(context) ?: return null
        return try {
            manager.ensureSite(type, silent = true)
        } catch (e: MfaRequiredException) {
            Log.d(TAG, "ensureSite(${type.name}) 需要短信验证，本轮放弃")
            null
        } catch (e: PasswordInvalidatedException) {
            Log.d(TAG, "ensureSite(${type.name}) 密码已失效，本轮放弃")
            null
        }
    }

    suspend fun manager(context: Context): SessionManager? {
        SessionManager.active?.let { return it }

        val app = context.applicationContext
        val account = AccountStore(app).activeAccount() ?: return null
        if (account.password.isBlank()) return null

        lock.withLock {
            // 锁内再看一次：前台可能刚刚起来了，那就用它的。
            SessionManager.active?.let { return it }
            headless?.takeIf { headlessAccountId == account.accountId }?.let { return it }

            val manager = SessionManager(app)
            with(manager) {
                register(com.xjtu.toolbox.auth.JwxtSession())
                register(com.xjtu.toolbox.auth.JwappSession())
                register(com.xjtu.toolbox.auth.LibrarySession())
                register(com.xjtu.toolbox.auth.LmsSession())
                register(com.xjtu.toolbox.auth.AttendanceSession())
            }
            manager.reconfigureForAccount(AccountContext.suffixFor(account.accountId))
            manager.setCredentials(account.accountId, account.password)
            manager.accountType = if (account.accountType == AccountType.POSTGRADUATE) {
                XJTULogin.AccountType.POSTGRADUATE
            } else {
                XJTULogin.AccountType.UNDERGRADUATE
            }
            manager.fpVisitorId = account.fpVisitorId
            manager.cachedRsaKey = account.rsaPublicKey
            AccountContext.activeAccountId = account.accountId

            headless = manager
            headlessAccountId = account.accountId
            return manager
        }
    }

    /** 有没有可用于后台自动登录的账号。没有就别排后台任务，省得空转。 */
    fun hasAccount(context: Context): Boolean =
        AccountStore(context.applicationContext).activeAccount()?.password?.isNotBlank() == true
}
