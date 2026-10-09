package com.xjtu.toolbox.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginUiState
import com.xjtu.toolbox.auth.LibrarySession
import com.xjtu.toolbox.auth.SessionBackend
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.library.LibrarySource
import com.xjtu.toolbox.platform.JvmCredentialStore
import kotlinx.coroutines.CancellationException

/**
 * 桌面端的**「自己登录」**：会话管家 + 凭据存储 + MFA 宿主状态，装在一个对象里。
 *
 * ## 它为什么必须存在（而不是复用 `:app` 的 `AppLoginState`）
 *
 * `:app` 的 `AppLoginState` 是 Android 侧最大的一个装配件（`Context` + `AccountStore` +
 * `CampusProbe` + `CredentialStore` + 多账号编排 + 校园网判定 + 通知），它绑着 `Context` 与
 * `ConnectivityManager`。设计文档（`docs/desktop-port-plan.md` §6 Stage A）对这一端的验收是
 * 一句话：**用户在窗口里输学号密码 → 真登进去 → 只读屏可用**。所以这里只实现那一条路：
 *
 * | 拆掉的东西 | 桌面端怎么办 |
 * |---|---|
 * | 多账号（`AccountStore` + `AccountManager`） | 单账号。凭据存一份，换账号 = 退出再登（多账号 UI 属 Stage C） |
 * | 校园网判定（`CampusProbe` + `ConnectivityManager`） | **不做**：只按直连（`AccessMode.NORMAL`）走。校外要用 WebVPN 网关属于宿主机能族（§5.4）——见下面「已知缺口」 |
 * | 账号类型自动识别（一网通办回填） | 用上次登录记住的那个（[JvmCredentialStore.accountType]），默认本科生 |
 * | 设置项（网络模式、主题、更新通道…） | 桌面端还没有设置页；这些**不属于**凭据，也不该在这一轮搬（§3.1 第 2 条） |
 *
 * ## 登录过程（与 Android 逐条同源，只是少了「匿名罐」那一段）
 *
 * `:app` 先匿名登录、拿到学号之后再 `adoptAnonymousSession(suffix)` 把 cookie 搬进账号命名空间
 * —— 那是因为它**登录前不知道 accountId**。桌面端是用户手输学号，所以先定命名空间再登：
 *
 * 1. `AccountContext.activeAccountId = 学号`，`reconfigureForAccount(suffix)` ⇒ backends 绑到
 *    该账号的 cookie / 快照文件（`cookies_normal_<学号>` …，与其余端同一套命名）；
 * 2. `setCredentials` + `ensureSite("library", userInitiated = true)` ⇒ 走完整 CAS（RSA 加密密码、
 *    TGC、ticket 回跳），这条链路与 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest` 验的是同一段；
 * 3. **成功才落盘**：学号密码进 [JvmCredentialStore]（0600 的 Properties），设备指纹与 RSA 公钥
 *    顺手存下来（省掉下次登录的一次公钥请求）；
 * 4. 失败就把命名空间退回匿名，绝不把一个没登上去的账号写进 [AccountContext]（那会让所有按账号
 *    分目录的存储开始写一个不存在的账号）。
 *
 * ## ⚠️ 已知缺口（都在设计文档里，不是漏做）
 *
 * - **校外**：`LibrarySession.mustUseWebVpn = true`，而这里永远按 `AccessMode.NORMAL` 走 ⇒
 *   校外直连不上。WebVPN 网关需要「校内外判定」这个宿主机能，属 §5.4。
 * - **短信二验**：能弹（[MfaCodeDialog]），也只有在**窗口开着**的时候能弹 —— `attachMfaHost()`
 *   的宿主计数由 `ToolboxDesktopApp` 挂载/卸载，没有宿主时 `SessionManager` 会直接报错而不是空等
 *   （与 `:app` 同一条口径）。
 *
 * ## 状态为什么用 Compose 的 `mutableStateOf`
 *
 * 它与 `:app` 的 `AppLoginState` 同一个形态：宿主持有可观察状态，屏只是它的投影。
 * 读写都不在快照里做（`login()` 由界面的协程调、`restore()` 在启动时的组合里调），
 * 所以单测里直接构造它、`runBlocking { login() }` 也是合法的（见 `DesktopAuthLibraryJvmTest`）。
 */
class DesktopAuth(
    private val credentials: JvmCredentialStore = JvmCredentialStore(),
) {

    /** 会话管家：双 backend（直连 / WebVPN）、站点注册中心、MFA 状态机宿主。 */
    val sessionManager: SessionManager = SessionManager().apply {
        // 只注册桌面端**能取数**的站点。其余 18 个站点的类与它们的 `*Login` 还在 :app，
        // 搬一个注册一个（`:data:commonMain` 的 `Sites.kt` 那一批）。
        register(LibrarySession())
        // 切账号时清宿主侧共享缓存：`:app` 把它设成 `CampusProbe.ywtbToken = null`，
        // 而 CampusProbe 要 `ConnectivityManager`（宿主能力，没跟着内核搬进 :data）；
        // 桌面端没有那份缓存，所以留空。这正是「缝照真正用到的那几处切」。
    }

    /** 图书馆站点会话。冷启动时它还是「从快照恢复、待确认」，第一次取数会自己探活/重登。 */
    val librarySite: SiteSession get() = sessionManager.getSite(LIBRARY_SITE_KEY)

    /** 图书馆端口到 `:data`（`LibraryApi`）的适配，给 `:core` 的 `LibraryScreen` 用。 */
    val librarySource: LibrarySource by lazy { DesktopLibrarySource(librarySite) }

    // ── 登录页的可观察状态（屏只读它，见 :core 的 LoginScreen）────────────────

    /**
     * 登录页的账号输入。
     *
     * ⚠️ 【红线】它**不会**被 [restore] 填上已存的学号 —— 有凭据就直接进主界面，
     * 学号不上屏、不进日志、不进截图（见 `LoginScreen` 的 KDoc）。
     */
    var username by mutableStateOf("")

    /** 登录页的密码输入。登录成功后**立刻清空**，不留在界面状态里。 */
    var password by mutableStateOf("")

    var loginState by mutableStateOf<LoginUiState>(LoginUiState.Idle)
        private set

    /** 是否已经「有身份」＝ 已登录（或冷启动从落盘凭据恢复）。屏据此在登录页与主界面之间切。 */
    var loggedIn by mutableStateOf(false)
        private set


    /**
     * 用户改动了登录页的输入 ⇒ 上一次的失败文案不再适用。
     *
     * 不这么做的话，改完密码那个「账号或密码无效」还挂在下面，看上去像是这次也错了。
     * 只清 [LoginUiState.Failed]，**不动** [LoginUiState.Submitting]（正在登的时候用户的输入
     * 本来就被 `enabled = false` 锁着）。
     */
    fun onLoginInputChanged() {
        if (loginState is LoginUiState.Failed) loginState = LoginUiState.Idle
    }
    /**
     * 冷启动静默恢复：有落盘凭据就直接进主界面，**不经过登录页**。
     *
     * 幂等：已登录时直接返回。可以在组合里反复调（宿主用 `LaunchedEffect(Unit)` 调一次即可）。
     *
     * 恢复本身**不验证**会话是否还有效（与 Android 的启动恢复同一条口径）：站点会话是
     * 「从快照恢复、待确认」态，第一次真取数时会探活，失效就凭 TGC 免密或凭据重登 ——
     * 在这里多打一次网络只会让启动变慢。
     *
     * 同步读盘（几个 Properties 文件，几 KB）——与 Android 在 ViewModel init 里做的是同一件事。
     */
    fun restore() {
        if (loggedIn) return
        val (savedUsername, savedPassword) = credentials.load() ?: return
        val suffix = AccountContext.suffixFor(savedUsername)
        val accountType = credentials.accountType
        AccountContext.activeAccountId = savedUsername
        AccountContext.activeAccountType = accountType.toCoreAccountType()
        sessionManager.reconfigureForAccount(suffix)
        sessionManager.setCredentials(savedUsername, savedPassword)
        sessionManager.accountType = accountType
        sessionManager.fpVisitorId = credentials.fpVisitorId
        sessionManager.cachedRsaKey = credentials.rsaPublicKey
        // 注意：**不**写 username/password 这两个界面字段（红线，见它们的 KDoc）。
        loginState = LoginUiState.Idle
        loggedIn = true
    }

    /**
     * 用 [username] / [password] 真登一次（走完整 CAS）。成功返回 true 并切到已登录。
     *
     * 失败时：命名空间退回匿名、界面状态置 [LoginUiState.Failed]（文案由 `FriendlyError` 给，
     * 是中文短句、不含学号），返回 false。**不落盘**任何东西。
     */
    suspend fun login(): Boolean {
        if (loggedIn) return true
        val user = username.trim()
        val pass = password
        if (user.isEmpty() || pass.isEmpty()) {
            loginState = LoginUiState.Failed("请输入学号与密码")
            return false
        }
        loginState = LoginUiState.Submitting
        val suffix = AccountContext.suffixFor(user)
        val accountType = credentials.accountType
        AccountContext.activeAccountId = user
        AccountContext.activeAccountType = accountType.toCoreAccountType()
        sessionManager.reconfigureForAccount(suffix)
        sessionManager.setCredentials(user, pass)
        sessionManager.accountType = accountType
        // 缓存过的设备指纹与 RSA 公钥要带进来：前者避免学校把它当成新设备（多一发短信），
        // 后者省掉 `GET https://login.xjtu.edu.cn/cas/jwt/publicKey` 那一枪。
        // 机器上第一次登录时两样都还是 null，内核会自己去取（真机上是 https，能取到），
        // 取回后由下一个 `adoptFromLogin` 写进 `sessionManager`，再由下面的保存写进凭据文件。
        sessionManager.fpVisitorId = credentials.fpVisitorId
        sessionManager.cachedRsaKey = credentials.rsaPublicKey
        return try {
            // 真登录：CAS 表单 POST（密码 RSA 加密）→ TGC → ticket 回跳 → 座位系统会话。
            // userInitiated = true：用户正等结果，豁免站点级 60 秒冷却（防刷由 CasGate 兜）。
            sessionManager.ensureSite(LIBRARY_SITE_KEY, userInitiated = true)

            // 成功才记住。设备指纹与 RSA 公钥一起存：前者换来换去会被学校当成新设备（多一次短信），
            // 后者省掉每次登录那一枪 `GET /cas/jwt/publicKey`。
            credentials.save(user, pass)
            sessionManager.fpVisitorId?.let { credentials.fpVisitorId = it }
            sessionManager.cachedRsaKey?.let { credentials.rsaPublicKey = it }
            // 登录成功后**两个输入框都清空**：密码不该留在界面状态里，学号也不该 ——
            // 已登录时没人看这两个字段，少一份内存里的学号（红线：学号不上屏、不进日志、不进截图）。
            username = ""
            password = ""
            loginState = LoginUiState.Idle
            loggedIn = true
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没登上去就别占着命名空间：否则所有按账号分目录的存储会开始写一个不存在的账号
            AccountContext.activeAccountId = null
            sessionManager.reconfigureForAnonymous()
            loginState = LoginUiState.Failed(
                // 认证异常都实现了 UserFacingFailure，消息本身就是给用户看的中文（「账号或密码无效」）；
                // 其余异常落到 FriendlyError 的网络/兜底分类。两种都不含学号。
                FriendlyError.of(e, "登录")
            )
            false
        }
    }

    /**
     * 退出登录：**凭据、cookie、站点快照一起删**。
     *
     * 为什么不是「只删凭据」（Android 那边退出后 cookie 留着，切回该账号能免密）：
     * 桌面是**可分发**的客户端，可能装在共享机器上，留一个 TGC 在磁盘上等于下一个人能直接
     * 登进上一个人的账号。设计文档 §8 第 3 条把这条列为分发的代价之一：
     * 「每平台凭据存储 + 隐私说明 + 退出登录即清除」。
     */
    fun logout() {
        val suffix = AccountContext.suffixFor(AccountContext.activeAccountId)
        sessionManager.clearCredentials()
        sessionManager.forgetAllSites()
        sessionManager.reconfigureForAnonymous()
        AccountContext.activeAccountId = null
        runCatching { SessionBackend.wipe(suffix) }
        credentials.clear()
        username = ""
        password = ""
        loginState = LoginUiState.Idle
        loggedIn = false
    }

    companion object {
        /** 站点 key，与 `Sites.kt` / `LoginType.siteKey()` 里的那个字符串一致。 */
        const val LIBRARY_SITE_KEY = "library"
    }
}

/** `:data` 的 CAS 账号类型 → `:core` 的账号身份（两个枚举同义，各端各有一份，见两侧的 KDoc）。 */
internal fun XJTULogin.AccountType.toCoreAccountType(): AccountType = when (this) {
    XJTULogin.AccountType.UNDERGRADUATE -> AccountType.UNDERGRADUATE
    XJTULogin.AccountType.POSTGRADUATE -> AccountType.POSTGRADUATE
}
