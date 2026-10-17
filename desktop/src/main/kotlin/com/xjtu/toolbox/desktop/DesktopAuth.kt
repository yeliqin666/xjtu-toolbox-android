package com.xjtu.toolbox.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.CampusCardSession
import com.xjtu.toolbox.auth.CouponSession
import com.xjtu.toolbox.auth.DzpzSession
import com.xjtu.toolbox.auth.FitnessSession
import com.xjtu.toolbox.auth.GmisSession
import com.xjtu.toolbox.auth.GsteSession
import com.xjtu.toolbox.auth.JsSession
import com.xjtu.toolbox.auth.JwxtSession
import com.xjtu.toolbox.auth.LoginUiState
import com.xjtu.toolbox.auth.LibrarySession
import com.xjtu.toolbox.auth.SessionBackend
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.VenueSession
import com.xjtu.toolbox.auth.YwtbSession
import com.xjtu.toolbox.auth.SsnSession
import com.xjtu.toolbox.auth.AttendanceSession
import com.xjtu.toolbox.auth.LmsSession
import com.xjtu.toolbox.auth.JwappSession
import com.xjtu.toolbox.auth.IclassfaceSession
import com.xjtu.toolbox.auth.JiaocaiSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.library.LibrarySource
import com.xjtu.toolbox.platform.JvmCredentialStore
import kotlinx.coroutines.CancellationException

/** 桌面端的「自己登录」：会话管家 + 凭据存储 + MFA 宿主状态，装在一个对象里。 */
class DesktopAuth(
    private val credentials: JvmCredentialStore = JvmCredentialStore(),
) {

    /** 会话管家：双 backend（直连 / WebVPN）、站点注册中心、MFA 状态机宿主。 */
    val sessionManager: SessionManager = SessionManager().apply {
        // 只注册桌面端**能取数**的站点。其余站点的类与它们的 `*Login` 还在 :app，
        // 搬一个注册一个（`:data:commonMain` 的 `Sites.kt` 那一批）。
        register(LibrarySession())
        register(FitnessSession())
        // 教务：全校课表 / 成绩报表 / 本科评教三条路由共用的站点（`:data` 的 `JwxtSession` + `JwxtLogin`）。
        register(JwxtSession())
        // 研究生评教要的两个站点：gste（问卷）与 gmis（课程详情 + 学位课）。它们**只**服务研究生，
        // 本科账号登它们会失败 —— 所以登录页那一步只是「尽力预热」，失败只记不抛（见 `login()`）。
        register(GsteSession())
        register(GmisSession())
        register(CampusCardSession())
        register(JsSession())
        register(VenueSession())
        register(YwtbSession())
        register(CouponSession())
        register(DzpzSession())

        register(SsnSession())

        // 考勤会话有自己的独立登录入口（`AttendanceLogin`），登录页只尽力预热。
        register(AttendanceSession())

        // 移动教务有自己的独立登录链（开放平台 appId=1370 → 统一认证 → 回调 URL 里的 token）；⚠️ 成绩屏的第二条上游是 jwxt。
        register(JwappSession())

        register(IclassfaceSession())

        register(JiaocaiSession())

        register(LmsSession())
    }

    /** 图书馆站点会话。冷启动时它还是「从快照恢复、待确认」，第一次取数会自己探活/重登。 */
    val librarySite: SiteSession get() = sessionManager.getSite(LIBRARY_SITE_KEY)

    /** 体测站点会话。与图书馆同一条会话语义，但它那一半是 https（见 `FitnessApi`）。 */
    val fitnessSite: SiteSession get() = sessionManager.getSite(FITNESS_SITE_KEY)

    /** 教务站点会话。 */
    val jwxtSite: SiteSession get() = sessionManager.getSite(JWXT_SITE_KEY)

    /** 校园卡站点会话（卡面 + 流水两条取数共用一个上游）。 */
    val campusCardSite: SiteSession get() = sessionManager.getSite(CAMPUS_CARD_SITE_KEY)

    /** 体育场馆站点会话（场馆 / 时段 / 订单三条取数共用一个上游）。 */
    val venueSite: SiteSession get() = sessionManager.getSite(VENUE_SITE_KEY)

    /** 进某一屏之前把它的站点会话建起来（失败就抛给调用方）。 */
    suspend fun ensureSession(siteKey: String) {
        sessionManager.ensureSite(siteKey, userInitiated = true)
    }

    /** 图书馆端口到 `:data`（`LibraryApi`）的适配，给 `:core` 的 `LibraryScreen` 用。 */
    val librarySource: LibrarySource by lazy { DesktopLibrarySource(librarySite) }

    // ── 登录页的可观察状态（屏只读它，见 :core 的 LoginScreen）────────────────

    /** 登录页的账号输入。 */
    var username by mutableStateOf("")

    /** 登录页的密码输入。登录成功后**立刻清空**，不留在界面状态里。 */
    var password by mutableStateOf("")

    var loginState by mutableStateOf<LoginUiState>(LoginUiState.Idle)
        private set

    /** 是否已经「有身份」＝ 已登录（或冷启动从落盘凭据恢复）。屏据此在登录页与主界面之间切。 */
    var loggedIn by mutableStateOf(false)
        private set


    /** 用户改动了登录页的输入 ⇒ 上一次的失败文案不再适用。 */
    fun onLoginInputChanged() {
        if (loginState is LoginUiState.Failed) loginState = LoginUiState.Idle
    }
    /** 冷启动静默恢复：有落盘账号就直接进主界面，不经过登录页。 */
    fun restore() {
        if (loggedIn) return
        // 1) 多账号：账号表里有激活账号就直接切进去
        accountStore.activeAccount()?.let { account ->
            adoptAccount(account)
            return
        }
        val (savedUsername, savedPassword) = credentials.load() ?: return
        val imported = Account(
            accountId = savedUsername,
            password = savedPassword,
            accountType = credentials.accountType.toCoreAccountType(),
            fpVisitorId = credentials.fpVisitorId,
            rsaPublicKey = credentials.rsaPublicKey,
            rsaKeyTime = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
        )
        accountStore.upsert(imported, setActive = true)
        // 导完就抹掉那份单账号凭据：从此账号表是唯一的真相（与 `AccountMigration` 同一分工）。
        // 注意顺序：先读完（上面那几个字段）再抹。
        credentials.clear()
        adoptAccount(imported)
    }

    /** 用 [username] / [password] 真登一次（走完整 CAS）。 */
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
        writeActiveAccountId(user)
        AccountContext.activeAccountType = accountType.toCoreAccountType()
        sessionManager.reconfigureForAccount(suffix)
        sessionManager.setCredentials(user, pass)
        sessionManager.accountType = accountType
        sessionManager.fpVisitorId = credentials.fpVisitorId
        sessionManager.cachedRsaKey = credentials.rsaPublicKey
        return try {
            // 只硬要求主站（图书馆）登录成功，其余站点尽力而为（体测会 502，不能因此挡住登录）；userInitiated 豁免 60 秒冷却。
            sessionManager.ensureSite(LIBRARY_SITE_KEY, userInitiated = true)
            SESSION_SITE_KEYS.filter { it != LIBRARY_SITE_KEY }.forEach { key ->
                runCatching { sessionManager.ensureSite(key, userInitiated = true) }
                    .onFailure { sessionManager.recordDiagnostic("WARN", key, "登录时预热失败（不阻塞登录）：${it.message}") }
            }

            // 成功才写进账号表。设备指纹换来换去会被当成新设备（多一次短信），RSA 公钥省一次 `GET /cas/jwt/publicKey`。
            val previous = accountStore.get(user)
            accountStore.upsert(
                Account(
                    accountId = user,
                    password = pass,
                    accountType = accountType.toCoreAccountType(),
                    nickname = previous?.nickname,
                    fpVisitorId = sessionManager.fpVisitorId ?: previous?.fpVisitorId,
                    rsaPublicKey = sessionManager.cachedRsaKey ?: previous?.rsaPublicKey,
                    rsaKeyTime = if (sessionManager.cachedRsaKey != null) {
                        System.currentTimeMillis()
                    } else {
                        previous?.rsaKeyTime ?: 0L
                    },
                    lastUsedAt = System.currentTimeMillis(),
                ),
                setActive = true,
            )
            username = ""
            password = ""
            loginState = LoginUiState.Idle
            loggedIn = true
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没登上去就别占着命名空间：否则所有按账号分目录的存储会开始写一个不存在的账号
            writeActiveAccountId(null)
            sessionManager.reconfigureForAnonymous()
            loginState = LoginUiState.Failed(
                // 认证异常都实现了 UserFacingFailure，消息本身就是给用户看的中文（「账号或密码无效」）；
                // 其余异常落到 FriendlyError 的网络/兜底分类。两种都不含学号。
                FriendlyError.of(e, "登录")
            )
            false
        }
    }

    /** 退出登录：凭据、cookie、站点快照一起删。 */
    fun logout() {
        val id = AccountContext.activeAccountId
        val suffix = AccountContext.suffixFor(id)
        sessionManager.clearCredentials()
        sessionManager.forgetAllSites()
        sessionManager.reconfigureForAnonymous()
        writeActiveAccountId(null)
        runCatching { SessionBackend.wipe(suffix) }
        if (id != null) {
            accountStore.remove(id)
            accountStore.clearActive()
        }
        // 再抹一次旧的单账号凭据文件：兼容从旧版本升级上来、还没重启过的那一瞬。
        credentials.clear()
        username = ""
        password = ""
        loginState = LoginUiState.Idle
        loggedIn = false
    }

    // ── 多账号：`AccountsController` 要的内部钩子（切账号要改的四样状态都是这里的私有状态）──

    /** 账号表。 */
    internal val accountStore = AccountStore()

    /** 「当前账号」的可观察镜像。 */
    var activeAccountId by mutableStateOf<String?>(null)
        private set

    /** [AccountContext.activeAccountId] 与 [activeAccountId] 必须同刻写入 —— 只能走这里。 */
    private fun writeActiveAccountId(id: String?) {
        AccountContext.activeAccountId = id
        activeAccountId = id
    }

    /** 把一条已保存账号认成「当前身份」：命名空间、凭据、指纹、RSA 公钥、界面状态一起换。 */
    internal fun adoptAccount(account: Account) {
        writeActiveAccountId(account.accountId)
        AccountContext.activeAccountType = account.accountType
        sessionManager.reconfigureForAccount(AccountContext.suffixFor(account.accountId))
        sessionManager.setCredentials(account.accountId, account.password)
        sessionManager.accountType = account.accountType.toXjtuLoginAccountType()
        // 带进来的指纹与公钥省下两次往返（且指纹换了会被学校当成新设备、多一发短信）。
        sessionManager.fpVisitorId = account.fpVisitorId
        sessionManager.cachedRsaKey = account.rsaPublicKey
        username = ""
        password = ""
        loginState = LoginUiState.Idle
        loggedIn = true
    }

    /** 回到未登录（匿名命名空间）。删掉最后一条账号、或者新增失败要回滚时用。 */
    internal fun dropToAnonymous() {
        sessionManager.clearCredentials()
        sessionManager.forgetAllSites()
        sessionManager.reconfigureForAnonymous()
        writeActiveAccountId(null)
        username = ""
        password = ""
        loginState = LoginUiState.Idle
        loggedIn = false
    }

    companion object {
        /** 站点 key，与 `Sites.kt` / `LoginType.siteKey()` 里的那个字符串一致。 */
        const val LIBRARY_SITE_KEY = "library"
        const val FITNESS_SITE_KEY = "fitness"

        /** 教务：全校课表 + 成绩报表 + 本科评教共用的那一个站点。 */
        const val JWXT_SITE_KEY = "jwxt"

        /** 研究生评教：问卷在 gste、课程信息在 gmis（两个站点，都只服务研究生）。 */
        const val GSTE_SITE_KEY = "gste"
        const val GMIS_SITE_KEY = "gmis"

        /** 校园卡（ncard）。屏上的「校园卡」那一屏进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val CAMPUS_CARD_SITE_KEY = "campus_card"

        /** 智慧教室平台（`js`）：空闲教室的「实时状态」那一档用。 */
        const val JS_SITE_KEY = JsSession.SITE_KEY

        /** 体育场馆（`venue`）。那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val VENUE_SITE_KEY = VenueSession.SITE_KEY

        /** 一网通办（`ywtb`）：消息收纳那四路取数要它（`:data` 的 `AppInboxSource` 进门自己 ensure）。 */
        const val YWTB_SITE_KEY = YwtbSession.SITE_KEY

        /** 加餐券（`coupon` = `egc.xjtu.edu.cn`）。那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val COUPON_SITE_KEY = CouponSession.SITE_KEY

        /** 电子凭证（`dzpz`）。成绩单那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val DZPZ_SITE_KEY = DzpzSession.SITE_KEY


        /** 宿舍电费（`ssn`）。缴费页那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val SSN_SITE_KEY = SsnSession.SITE_KEY


        /** 考勤（`new_attendance`）。考勤那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val ATTENDANCE_SITE_KEY = AttendanceSession.SITE_KEY

        /** 思源学堂（`lms`）。那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val LMS_SITE_KEY = LmsSession.SITE_KEY

        /** 移动教务（`jwapp`）。成绩那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val JWAPP_SITE_KEY = JwappSession.SITE_KEY

        /** 人脸识别签到（`iclassface`）。那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val ICLASSFACE_SITE_KEY = IclassfaceSession.SITE_KEY
        /** 教材全文库（`jiaocai1`）那两条路由进门时用的站点。 */
        const val JIAOCAI_SITE_KEY = JiaocaiSession.SITE_KEY

        /** 登录页那一步一次建起会话的站点：`:core` 里有屏 + `:data` 里有站点类与取数的那几个。 */
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
            // 教材全文库那两条路由借的就是教材中心的会话（站点 key = `jiaocai`）
            JIAOCAI_SITE_KEY,
        )
    }
}

/** `:core` 的账号身份 → `:data` 的 CAS 账号类型（[toCoreAccountType] 的反向，各端各一份）。 */
internal fun AccountType.toXjtuLoginAccountType(): XJTULogin.AccountType = when (this) {
    AccountType.UNDERGRADUATE -> XJTULogin.AccountType.UNDERGRADUATE
    AccountType.POSTGRADUATE -> XJTULogin.AccountType.POSTGRADUATE
}

/** `:data` 的 CAS 账号类型 → `:core` 的账号身份（两个枚举同义，各端各有一份，见两侧的 KDoc）。 */
internal fun XJTULogin.AccountType.toCoreAccountType(): AccountType = when (this) {
    XJTULogin.AccountType.UNDERGRADUATE -> AccountType.UNDERGRADUATE
    XJTULogin.AccountType.POSTGRADUATE -> AccountType.POSTGRADUATE
}
