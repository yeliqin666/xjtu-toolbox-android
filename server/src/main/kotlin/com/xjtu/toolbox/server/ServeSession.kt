package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.CampusCardSession
import com.xjtu.toolbox.auth.FitnessSession
import com.xjtu.toolbox.auth.GmisSession
import com.xjtu.toolbox.auth.GsteSession
import com.xjtu.toolbox.auth.JsSession
import com.xjtu.toolbox.auth.JwxtSession
import com.xjtu.toolbox.auth.LibrarySession
import com.xjtu.toolbox.auth.MfaRequest
import com.xjtu.toolbox.auth.SessionBackend
import com.xjtu.toolbox.auth.SessionManager
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

/**
 * 一次登录尝试的结果（[ServeSession.login] 的返回值 → `/api/session/login` 的响应）。
 */
sealed interface LoginOutcome {

    /** 成功 —— 也包含「本来就已经登录」（幂等：再登一次不会把已有会话搅掉）。 */
    data object Success : LoginOutcome

    /** 另一次登录正在跑（含挂起的短信二验）⇒ HTTP 409。 */
    data object Busy : LoginOutcome

    /**
     * 没登上去。[message] 是给用户看的中文短句（**不含学号/姓名**）。
     *
     * @param retryable true = 上游/网络的故障，同样的请求稍后重试可能就成功（HTTP 502）；
     *   false = 凭据或验证被拒，重发同样的请求没有意义（HTTP 401）。
     */
    data class Failed(val message: String, val retryable: Boolean) : LoginOutcome
}

/**
 * serve 进程的**会话装配**：`:data` 的 [SessionManager] + 落盘凭据 + MFA 宿主。
 *
 * ## 它与桌面端那份是什么关系
 *
 * 形态照 `:desktop` 的 `DesktopAuth`（登录 / 登出 / 冷启动恢复三件事逐条同源），**去掉 Compose 状态**：
 * serve 没有界面，登录页那两个输入框、`loggedIn`、`LoginUiState` 在这里都不存在 ——
 * 它们的位置由 HTTP（`/api/session*`）与浏览器的那个页面顶上。所以这里只剩三样东西：
 *
 * 1. **会话内核**（[sessionManager]）：注册 serve 模式**能取数**的站点，与桌面端同一个清单；
 * 2. **MFA 宿主**（[mfaHostKeepAlive]）：serve 进程一直挂着（见下）；
 * 3. **凭据**（[JvmCredentialStore]，落盘 `0600`）—— 登录成功才写，登出即清。
 *
 * ## 登录（与 `DesktopAuth.login` 逐条对齐）
 *
 * 1. `AccountContext.activeAccountId = 学号` + `reconfigureForAccount(suffix)` ⇒ backends 绑到该账号
 *    的 cookie / 快照文件（`cookies_normal_<学号>` …，与其余端同一套命名）；
 * 2. `setCredentials` + **硬要求主站（图书馆）登上去** —— 它证明这组凭据真能过统一认证；
 * 3. 其余站点 `runCatching` **尽力预热**（失败只记不抛）：某个子系统自己挂了（体测历史上真返回过
 *    502）**绝不能**变成「整个登录失败」；
 * 4. **全都过了才落盘**（学号密码 + 设备指纹 + 缓存的 RSA 公钥）；
 * 5. 失败退回 [SessionManager.reconfigureForAnonymous]（否则所有按账号分目录的存储会开始写一个
 *    不存在的账号），并把 [FriendlyError] 的中文短句交回调用方。
 *
 * ⚠️ **短信二验（MFA）在 serve 上是一条「服务端主动问用户要验证码」的流程**：内核挂起
 * `verifyMfaWithUser`（最长 150 秒）等验证码，浏览器侧轮询 `/api/session/mfa`、把用户敲的码
 * `submit` 回来。为了让那条路真的能走通，这里必须做两件事（少一件 MFA 就永远失败）：
 *
 * - [mfaHostKeepAlive]：`attachMfaHost()` **一次并保持挂载**。计数为 0 时 `verifyMfaWithUser`
 *   直接抛 IOException（「现在没有可以输入验证码的界面」）—— 浏览器就是那个「界面」；
 * - [prepareMfaDialog]：弹窗出现的那一刻去取绑定手机号（`MFAContext.getPhoneNumber()`）。
 *   它**不只是为了显示**：`MFAContext.verifyCode` 要拿 `getPhoneNumber()` 顺手设下的 `gid` 去校验
 *   验证码，不调它的话每一次提交都会被判「必须先发送验证码」。两个窗口端（`:app` 的 `MfaDialogHost`、
 *   `:desktop` 的 `DesktopMfaDialog`）在弹窗的 `LaunchedEffect` 里做的就是这一下。
 *
 * ## 并发
 *
 * 同一时刻只允许一个登录流程（[loginMutex]）：第二个并发 `POST /api/session/login` 立刻拿 409，
 * 而不是排队等一个可能 150 秒的 MFA。**不能**让两个登录并存：它们会互相
 * `reconfigureForAccount` / `setCredentials`，把 backends 与命名空间搅成半新半旧的状态。
 *
 * @param credentials 凭据存储。默认就是 `:data:jvmMain` 那份（`~/.local/share/xjtu-toolbox/xjtu_credentials`，
 *   权限 `0600`）；测试通过 `dataRootOverride` 换数据根，**绝不碰**用户真实的那一份。
 */
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
    }

    /**
     * MFA 宿主的**常驻**挂载凭据。
     *
     * 返回的 lambda 是卸载函数，本进程**永不调用**：serve 没有「窗口开着/关了」这回事，进程活着
     * 就一定能接住验证码询问。为什么要把返回值存下来：它同时是「挂载计数不会掉回 0」的凭据 ——
     * 计数回 0 之后 [SessionManager.verifyMfaWithUser] 会直接抛 IOException，浏览器那条 MFA 桥就断了。
     */
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

    /**
     * 「有身份」的真值（[authenticated] 读它）—— 与 `DesktopAuth.loggedIn` 同义。
     *
     * 置 true 的只有两处：登录**跑完**（[login] 的最后一步）、冷启动恢复成功。
     * 置 false 的也是两处：登录失败（命名空间已退回匿名）与 [logout]。
     */
    @Volatile
    private var loggedIn = false

    /**
     * 有没有一个可用的会话 —— 「有身份」= 登录**跑完了**（或冷启动从落盘凭据恢复过）。
     *
     * ⚠️ 两个容易写错的地方：
     * - **不**是读 `sessionManager.credentials`：内核在登录一开始就 `setCredentials`，于是
     *   「正在等短信验证码」那一段也会看起来像已登录 —— 而它随时可能失败，且那时扔该让第二发
     *   登录拿 409（见 [loginMutex]）；
     * - 读它会先做一次 [ensureRestored]（幂等、纯本地）—— 这就是「冷启动恢复」的入口：
     *   `GET /api/session` 与 `GET /api/status` 读的都是这一个属性。
     */
    val authenticated: Boolean
        get() {
            ensureRestored()
            return loggedIn
        }

    /** 当前挂起的 MFA 询问（没有就是 null）。浏览器侧轮询的就是它。 */
    val activeMfaRequest: MfaRequest? get() = sessionManager.activeMfaRequest.value

    /**
     * 冷启动静默恢复：落盘凭据还在就直接进「已登录」态，**不联网验证**（与 `DesktopAuth.restore` 同口径）。
     *
     * 为什么不验证：恢复本身只是把内存里的会话装配回账号命名空间（backends / 凭据 / 设备指纹 /
     * 缓存公钥），站点会话是「从快照恢复、待确认」态；第一次真取数时会自己探活，失效就凭 TGC 免密
     * 或凭据重登。在这里多打一次网络只会让第一个请求变慢（而且 serve 可能根本没人来取数）。
     *
     * 幂等且只尝试一次：失败（比如数据目录不可读）如实报「没有会话」，只记一条诊断 ——
     * 一个 HTTP 端点不该因为读盘失败而 500。
     */
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

    /**
     * 用这组凭据真登一次（走完整 CAS）。成功返回 [LoginOutcome.Success]。
     *
     * 失败时：命名空间退回匿名、**不落盘任何东西**、把 [FriendlyError] 的中文短句交回去。
     * 已在登录态时直接成功（幂等）—— 想换账号请先 `POST /api/session/logout`。
     */
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

    /**
     * 退出登录：**凭据、cookie、站点快照一起删**（与 `DesktopAuth.logout` 逐条同源）。
     *
     * 为什么不是「只删凭据」：serve 也可能跑在一台共用的机器上，磁盘上留一个 TGC 等于下一个人
     * 直接登进上一个人的账号（设计文档 §8 第 3 条把这条列为分发的代价之一）。
     *
     * ⚠️ 正在挂起的 MFA 询问会被一并取消（`reconfigureForAnonymous` 里 kernel 会 cancel 它）——
     * 那一发登录请求会以 4xx 收尾，这是对的：验证码发到了「已经没有人在等」的那一边。
     */
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

    /**
     * 弹窗出现的那一刻：把这次询问的绑定手机号取回来（顺带设下 `verifyCode` 要用的 `gid`）。
     *
     * 调用点只有一个 —— `GET /api/session/mfa` 报 `pending:true` 的时候。那一刻正是浏览器里
     * 「弹窗真的显示出来了」，与两个窗口端在弹窗 `LaunchedEffect` 里做的事逐条同型。
     *
     * 取不到只记一条诊断（`getPhoneNumber` 是网络调用）：弹窗照旧显示，用户提交的验证码会因为
     * 缺 `gid` 被判「必须先发送验证码」—— 与窗口端「取不到号码就报错、不假装有一个号码」一致。
     * 同一个 [MfaRequest] 只取一次（`phoneNumber` 在 `MFAContext` 里本来就缓存）。
     */
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

        /**
         * 登录那一步一次建起会话的站点（与 `:desktop` 的 `DesktopAuth.SESSION_SITE_KEYS` 同一份清单）。
         *
         * ⚠️ `js`（智慧教室平台）**刻意不在**表里：只有空闲教室那一屏的三档里的「实时状态」要它，
         * 为了一屏的一档让每一次登录都多走一趟 CAS 不划算 —— 进屏时由那个源自己 ensure。
         */
        private val SESSION_SITE_KEYS = listOf(
            LIBRARY_SITE_KEY,
            FITNESS_SITE_KEY,
            JWXT_SITE_KEY,
            GSTE_SITE_KEY,
            GMIS_SITE_KEY,
            CAMPUS_CARD_SITE_KEY,
            VENUE_SITE_KEY,
            YWTB_SITE_KEY,
        )

        /**
         * MFA 一共允许几次验证码（`SessionManager.MFA_MAX_ATTEMPTS` 的**对外投影**）。
         *
         * ⚠️ 那边是 `private`，所以这里是同一个数字的第二份写法：它只用来算 `/api/session/mfa`
         * 回给浏览器的 `attemptsLeft`（= 这个数 - `rejections`）。改了内核那个值就得改这里 ——
         * 两边都动才算改完（契约 §5 里记着这条）。
         */
        const val MFA_MAX_ATTEMPTS = 3
    }
}

/** `:data` 的 CAS 账号类型 → `:core` 的账号身份（两个枚举同义，各端各有一份，见两侧的 KDoc）。 */
private fun XJTULogin.AccountType.toCoreAccountType(): AccountType = when (this) {
    XJTULogin.AccountType.UNDERGRADUATE -> AccountType.UNDERGRADUATE
    XJTULogin.AccountType.POSTGRADUATE -> AccountType.POSTGRADUATE
}
