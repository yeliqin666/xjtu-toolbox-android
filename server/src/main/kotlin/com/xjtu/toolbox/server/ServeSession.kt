package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.AttendanceSession
import com.xjtu.toolbox.auth.CampusCardSession
import com.xjtu.toolbox.auth.CouponSession
import com.xjtu.toolbox.auth.DzpzSession
import com.xjtu.toolbox.auth.FitnessSession
import com.xjtu.toolbox.auth.GmisSession
import com.xjtu.toolbox.auth.GsteSession
import com.xjtu.toolbox.auth.IclassfaceSession
import com.xjtu.toolbox.auth.JiaocaiSession
import com.xjtu.toolbox.auth.JsSession
import com.xjtu.toolbox.auth.JwappSession
import com.xjtu.toolbox.auth.JwxtSession
import com.xjtu.toolbox.auth.LibrarySession
import com.xjtu.toolbox.auth.LmsSession
import com.xjtu.toolbox.auth.MfaRequest
import com.xjtu.toolbox.auth.SessionBackend
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SsnSession
import com.xjtu.toolbox.auth.VenueSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.auth.YwtbSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.UserFacingFailure
import com.xjtu.toolbox.platform.JvmCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一次登录尝试的结果（[ServeSession.login] 的返回值 → `/api/session/login` 的响应）。 */
sealed interface LoginOutcome {

    /** 成功 —— 也包含「本来就已经登录」（幂等：再登一次不会把已有会话搅掉）。 */
    data object Success : LoginOutcome

    /** 另一次登录正在跑（含挂起的短信二验）⇒ HTTP 409。 */
    data object Busy : LoginOutcome

    /** 没登上去。 */
    data class Failed(val message: String, val retryable: Boolean) : LoginOutcome
}

/** serve 进程的会话装配：`:data` 的 [SessionManager] + 落盘凭据 + MFA 宿主。 */
class ServeSession(private val credentials: JvmCredentialStore = JvmCredentialStore()) {

    /** 会话管家：站点注册中心、站点级 60 秒冷却、MFA 状态机宿主。 */
    val sessionManager: SessionManager = SessionManager().apply {
        // 只注册 serve 模式**能取数**的站点（与 `:desktop` 的 `DesktopAuth` 同一个清单）：
        // 其余站点的类与它们的 `*Login` 还在 `:app`，搬一个注册一个。
        register(LibrarySession())
        register(FitnessSession())
        // 教务：全校课表 / 成绩报表 / 本科评教三条路由共用的站点。
        register(JwxtSession())
        // 研究生评教要的两个站点：gste（问卷）与 gmis（课程详情）。它们**只**服务研究生，
        // 本科账号登它们会失败 —— 所以登录那一步只是「尽力预热」（失败只记不抛）。
        register(GsteSession())
        register(GmisSession())
        register(CampusCardSession())
        // 智慧教室平台（`js`）：空闲教室的「实时状态」那一档进门时自己 ensure。
        // 它**不在** [SESSION_SITE_KEYS] 里（只有那一屏的一档要它，登录时没必要为它多走一趟 CAS）。
        register(JsSession())
        register(VenueSession())
        register(YwtbSession())
        // 成绩单（dzpz）有自己的独立登录（CAS OAuth2 `client_id=new9940`），登录时只尽力预热。
        register(DzpzSession())
        // 加餐券（`coupon`）：`/api/coupon/{list,detail}` 要它。同样是独立登录页
        // （`CouponLogin`：CAS OAuth2 `client_id=1596` → 开放平台 → 回 egc 换 `auth_token`）。
        register(CouponSession())
        // 宿舍电费（`ssn`）：`/api/dorm/{rooms,reading}` 要它 —— 而且 `cid` 必须由**登录**写进会话
        // （`SsnLogin.parseCid` 从缴费页 HTML 里取），否则 `DormPowerApi.cid()` 直接抛「未登录」。
        register(SsnSession())
        // 考勤（`new_attendance`）：`/api/attendance/{terms,records}` 要它。它直连本科生业务站登录
        // （`AttendanceLogin`：入口 → CAS → 换业务令牌），不借别的站点会话。
        register(AttendanceSession())
        // 思源学堂（`lms`）：`/api/lms/{courses,activities,activity}` 要它。它直连本域根路径 → 302 到
        // 统一认证（与教务同型，不经过开放平台/OAuth 授权页，见 `LmsLogin`），不借别的站点会话。
        register(LmsSession())
        register(JiaocaiSession())
        // 移动教务（`jwapp`）：`/api/jwapp/scores` 要它；同一族的 `/api/jwapp/precise-scores` 要的是
        // **教务**（`jwxt`，上面的站点已经注册过）。它的登录链走开放平台 `appId=1370` 那一跳。
        register(JwappSession())
        // 人脸识别签到（`iclassface`）：`/api/iclassface/records` 要它。走 CAS OAuth2 `client_id=1543`
        // → 开放平台 `authorizesw` → 回本站种下 `PHPSESSID`（`mustUseWebVpn = true`，同上）。
        register(IclassfaceSession())
    }

    /** MFA 宿主的常驻挂载凭据。 */
    private val mfaHostKeepAlive: () -> Unit = sessionManager.attachMfaHost()

    /** 登录互斥（见类 KDoc 的「并发」）。 */
    private val loginMutex = Mutex()

    /** 「弹窗已经就绪」的去重锁 + 记号（同一个 [MfaRequest] 只取一次手机号）。 */
    private val mfaReadyMutex = Mutex()
    private var mfaReadyFor: MfaRequest? = null

    /** 静默恢复只尝试一次（成功或失败都不再重试，免得每个请求都去读盘）。 */
    private val restoreLock = Any()

    @Volatile
    private var restored = false

    /** 「有身份」的真值（[authenticated] 读它）—— 与 `DesktopAuth.loggedIn` 同义。 */
    @Volatile
    private var loggedIn = false

    /** 有没有一个可用的会话 —— 「有身份」= 登录跑完了（或冷启动从落盘凭据恢复过）。 */
    val authenticated: Boolean
        get() {
            ensureRestored()
            return loggedIn
        }

    /** 当前挂起的 MFA 询问（没有就是 null）。浏览器侧轮询的就是它。 */
    val activeMfaRequest: MfaRequest? get() = sessionManager.activeMfaRequest.value

    /** 冷启动静默恢复：落盘凭据还在就直接进「已登录」态，不联网验证。 */
    fun ensureRestored() {
        if (restored) return
        synchronized(restoreLock) {
            if (restored) return
            restored = true
            val saved = credentials.load() ?: return
            runCatching { restore(saved) }
                .onSuccess { loggedIn = true }
                .onFailure {
                    sessionManager.recordDiagnostic("WARN", "session", "冷启动恢复失败，按未登录处理：${it.message}")
                }
        }
    }

    /** 用这组凭据真登一次（走完整 CAS）。 */
    suspend fun login(username: String, password: String): LoginOutcome {
        if (authenticated) return LoginOutcome.Success

        // tryLock 而不是 lock：第二发立刻 409（见类 KDoc）。挂起的那一发可能正等验证码（150 秒）。
        if (!loginMutex.tryLock()) return LoginOutcome.Busy
        try {
            val user = username.trim()
            if (user.isEmpty() || password.isEmpty()) {
                return LoginOutcome.Failed("请输入学号与密码", retryable = false)
            }
            val suffix = AccountContext.suffixFor(user)
            val accountType = credentials.accountType
            AccountContext.activeAccountId = user
            AccountContext.activeAccountType = accountType.toCoreAccountType()
            sessionManager.reconfigureForAccount(suffix)
            sessionManager.setCredentials(user, password)
            sessionManager.accountType = accountType
            // 缓存过的设备指纹与 RSA 公钥要带进来：前者避免学校把它当成新设备（多一发短信），
            // 后者省掉 `GET https://login.xjtu.edu.cn/cas/jwt/publicKey` 那一枪。
            sessionManager.fpVisitorId = credentials.fpVisitorId
            sessionManager.cachedRsaKey = credentials.rsaPublicKey

            return try {
                // 硬要求主站：它证明这组凭据真能过统一认证（userInitiated = true ⇒ 豁免站点级 60 秒冷却）
                sessionManager.ensureSite(LIBRARY_SITE_KEY, userInitiated = true)
                // 其余站点**尽力而为**（失败只记不抛）：不能因为某个子系统自己挂了就把人挡在门外。
                SESSION_SITE_KEYS.filter { it != LIBRARY_SITE_KEY }.forEach { key ->
                    runCatching { sessionManager.ensureSite(key, userInitiated = true) }
                        .onFailure {
                            sessionManager.recordDiagnostic("WARN", key, "登录时预热失败（不阻塞登录）：${it.message}")
                        }
                }

                // 成功才记住（学号密码 + 设备指纹 + 公钥）
                credentials.save(user, password)
                sessionManager.fpVisitorId?.let { credentials.fpVisitorId = it }
                sessionManager.cachedRsaKey?.let { credentials.rsaPublicKey = it }
                loggedIn = true
                LoginOutcome.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 没登上去就别占着命名空间：否则所有按账号分目录的存储会开始写一个不存在的账号
                AccountContext.activeAccountId = null
                sessionManager.reconfigureForAnonymous()
                loggedIn = false
                LoginOutcome.Failed(
                    // 认证异常的消息本身就是给用户看的中文（「账号或密码无效」）；其余落到 FriendlyError 的
                    // 网络/兜底分类。两种都不含学号。
                    message = FriendlyError.of(e, "登录"),
                    retryable = e !is UserFacingFailure,
                )
            }
        } finally {
            loginMutex.unlock()
        }
    }

    /** 退出登录：凭据、cookie、站点快照一起删（与 `DesktopAuth.logout` 逐条同源）。 */
    fun logout() {
        val suffix = AccountContext.suffixFor(AccountContext.activeAccountId)
        sessionManager.clearCredentials()
        sessionManager.forgetAllSites()
        sessionManager.reconfigureForAnonymous()
        AccountContext.activeAccountId = null
        runCatching { SessionBackend.wipe(suffix) }
        credentials.clear()
        loggedIn = false
    }

    /** 弹窗出现时取回绑定手机号（顺带设下 `verifyCode` 要用的 `gid`）。 */
    suspend fun prepareMfaDialog(request: MfaRequest) {
        if (mfaReadyFor === request) return
        mfaReadyMutex.withLock {
            if (mfaReadyFor === request) return
            mfaReadyFor = request
            runCatching { withContext(Dispatchers.IO) { request.mfaContext.getPhoneNumber() } }
                .onFailure {
                    sessionManager.recordDiagnostic("WARN", request.siteKey, "取验证手机号失败，弹窗里的验证码可能提不上去：${it.message}")
                }
        }
    }

    private fun restore(saved: Pair<String, String>) {
        val (user, password) = saved
        val accountType = credentials.accountType
        AccountContext.activeAccountId = user
        AccountContext.activeAccountType = accountType.toCoreAccountType()
        sessionManager.reconfigureForAccount(AccountContext.suffixFor(user))
        sessionManager.setCredentials(user, password)
        sessionManager.accountType = accountType
        sessionManager.fpVisitorId = credentials.fpVisitorId
        sessionManager.cachedRsaKey = credentials.rsaPublicKey
    }

    companion object {

        /** 站点 key，与 `Sites.kt` / `LoginType.siteKey()` 里的那个字符串一致。 */
        const val LIBRARY_SITE_KEY = "library"
        const val FITNESS_SITE_KEY = "fitness"
        const val JWXT_SITE_KEY = "jwxt"
        const val GSTE_SITE_KEY = "gste"
        const val GMIS_SITE_KEY = "gmis"
        const val CAMPUS_CARD_SITE_KEY = "campus_card"
        const val VENUE_SITE_KEY = "venue"
        const val YWTB_SITE_KEY = "ywtb"
        const val DZPZ_SITE_KEY = "dzpz"
        const val COUPON_SITE_KEY = "coupon"
        /** 宿舍电费。key 是 `SsnSession.SITE_KEY`（**不是** `dorm_power`；`LoginType.DORM_POWER.siteKey()` 映射到它）。 */
        const val SSN_SITE_KEY = "ssn"
        /** 考勤。key 是 `AttendanceSession` 那个字面量（`LoginType.ATTENDANCE.siteKey()` 映射到它）。 */
        const val ATTENDANCE_SITE_KEY = "new_attendance"

        /** 思源学堂（`lms`）。`/api/lms*` 进门时用它（与 `:desktop` 的 `DesktopAuth.LMS_SITE_KEY` 同一个值）。 */
        const val LMS_SITE_KEY = LmsSession.SITE_KEY

        /** 教材中心（`jiaocai`）。 */
        const val JIAOCAI_SITE_KEY = JiaocaiSession.SITE_KEY

        /** 移动教务（`jwapp`）。`/api/jwapp/scores` 进门时用它（`jwxt` 那个站点是同一族的第二条上游）。 */
        const val JWAPP_SITE_KEY = JwappSession.SITE_KEY

        /** 人脸识别签到（`iclassface`）。`/api/iclassface/records` 进门时用它。 */
        const val ICLASSFACE_SITE_KEY = IclassfaceSession.SITE_KEY

        /** 登录那一步一次建起会话的站点（与 `:desktop` 的 `DesktopAuth.SESSION_SITE_KEYS` 同一份清单）。 */
        private val SESSION_SITE_KEYS = listOf(
            LIBRARY_SITE_KEY,
            FITNESS_SITE_KEY,
            JWXT_SITE_KEY,
            GSTE_SITE_KEY,
            GMIS_SITE_KEY,
            CAMPUS_CARD_SITE_KEY,
            VENUE_SITE_KEY,
            YWTB_SITE_KEY,
            DZPZ_SITE_KEY,
            COUPON_SITE_KEY,
            SSN_SITE_KEY,
            ATTENDANCE_SITE_KEY,
            LMS_SITE_KEY,
            JWAPP_SITE_KEY,
            ICLASSFACE_SITE_KEY,
            JIAOCAI_SITE_KEY,
        )

        /** MFA 一共允许几次验证码（`SessionManager.MFA_MAX_ATTEMPTS` 的对外投影）。 */
        const val MFA_MAX_ATTEMPTS = 3
    }
}

/** `:data` 的 CAS 账号类型 → `:core` 的账号身份（两个枚举同义，各端各有一份，见两侧的 KDoc）。 */
private fun XJTULogin.AccountType.toCoreAccountType(): AccountType = when (this) {
    XJTULogin.AccountType.UNDERGRADUATE -> AccountType.UNDERGRADUATE
    XJTULogin.AccountType.POSTGRADUATE -> AccountType.POSTGRADUATE
}
