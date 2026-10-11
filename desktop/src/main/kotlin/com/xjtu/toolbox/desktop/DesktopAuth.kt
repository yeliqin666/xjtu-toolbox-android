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
 * 2. `setCredentials` + 把 [SESSION_SITE_KEYS] 里每个站点 `ensureSite(userInitiated = true)`
 *    ⇒ 走完整 CAS（RSA 加密密码、TGC、ticket 回跳），这条链路与 `:data:jvmTest` 的
 *    `LibraryLoginSessionJvmTest` 验的是同一段；
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
        // 校园卡（`:data` 的 `CampusCardSession` + `CampusCardLogin`）：桌面自己登 ncard，
        // 用同包的 `AppCampusCardSource` 取卡面与流水（缓存传 null —— 桌面没有宿主存储）。
        // 它也是「登录页那一步只是尽力预热」的一员：ncard 自己挂了最坏只影响那一屏。
        register(CampusCardSession())
        // 智慧教室平台（`:data` 的 `JsSession` + `JsLogin`）：空闲教室「实时状态」那一档要它。
        // 注册了才拿得到会话 —— `AppEmptyRoomSource` 构造时就 `getSiteOrNull("js")`，拿不到
        // 那一档永远报「实时状态暂不可用」。但它**不在** `SESSION_SITE_KEYS` 里：只有这一屏的
        // 三档里的那一档需要它，登录页那一步没必要为它多走一趟 CAS（见那个常量的 KDoc）。
        register(JsSession())
        // 体育场馆（`:data` 的 `VenueSession` + `VenueLogin`）：桌面自己登场馆站，用同包的
        // `AppVenueSource` 取场馆 / 时段 / 订单（收藏走 `:core` 的 `VenueFavorites`，JVM 侧的内存 store）。
        // 与校园卡/教务一样，「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那一屏。
        // ✅ Stage B 之后这个站点在桌面上能下单：滑块宿主（`DesktopSlideCaptchaHost`）就绪，
        // 那条路由传 `canBook = true`（下单要先解滑块，识别/提交逻辑在 `:data`）。
        register(VenueSession())
        // 一网通办（`:data` 的 `YwtbSession` + `YwtbLogin`）：消息收纳那四路（消息 / 事务中心 / 预约 /
        // 校车）共用的站点，桌面自己登它，用同包的 `AppInboxSource` 取数。与校园卡/场馆一样，
        // 「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那一屏。
        register(YwtbSession())
        // 加餐券（`:data` 的 `CouponSession` + `CouponLogin`）：`egc.xjtu.edu.cn` 这条路由
        // 进门时要用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。它**有自己的独立登录页**
        // （CAS OAuth2 `client_id=1596` → `org.xjtu.edu.cn` 开放平台 → 回落到 egc 的
        // receiveCas.html → 换 `auth_token`），不借别的站点会话 —— 见 `CouponSession` 的 KDoc。
        // 与校园卡/场馆一样，「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那一屏。
        register(CouponSession())
        // 电子凭证（`:data` 的 `DzpzSession` + `DzpzLogin`）：成绩单（`dzpz` 的工作流引擎）这条路由
        // 进门时要用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。它**有自己的独立登录页**
        // （`dzpz.xjtu.edu.cn/login/Login.jsp` → CAS OAuth2 `client_id=new9940` → 回跳换
        // `loginidweaver`），不借别的站点会话 —— 见 `DzpzSession` 的 KDoc。
        // 与校园卡/场馆一样，「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那一屏。
        register(DzpzSession())

        // 宿舍电费（`:data` 的 `SsnSession` + `SsnLogin`）：`ssn.xjtu.edu.cn/cems` 这条路由
        // 进门时要用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。它**有自己的独立登录链**
        // （`org.xjtu.edu.cn` 开放平台 OAuth2 `appId=1759` → CAS → 带 `code` 回缴费页，站点点下
        // 会话、页面里内嵌 `cid`），不借校园卡 / 教务的会话 —— 见 `SsnSession` 的 KDoc。
        // 与校园卡/场馆一样，「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那一屏。
        register(SsnSession())

        // 考勤（`:data` 的 `AttendanceSession` + `AttendanceLogin`）：`kq` 这条路由进门时要用它
        //（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。它**有自己的独立登录入口** ——
        // `AttendanceLogin` 直连本科生业务站（bk-kq 的 `student-pc`，账号类型已知；失败自动退回
        // 门户），CAS 回调带 loginRequestId+ticket 换业务令牌 —— 不借校园卡 / 教务的会话（见
        // `AttendanceSession` 的 KDoc）。与校园卡/场馆一样，「登录页那一步」只是尽力预热它。
        register(AttendanceSession())

        // 移动教务（`:data` 的 `JwappSession` + `JwappLogin`）：`jwapp_score` 这条路由进门时要用它
        // （见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。它**有自己的独立登录链**（开放平台
        // `org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370` → 统一认证 → 带 `token` 回
        // `jwapp.xjtu.edu.cn/app/index`，token 从回调 URL 里抠出来当 `Authorization`），不借别的
        // 站点会话。⚠️ 成绩屏的第二条上游是**教务**（`jwxt`），两个站点各自独立（见 `JwxtScoreSupplement`）。
        register(JwappSession())

        // 人脸识别签到（`:data` 的 `IclassfaceSession` + `IclassfaceLogin`）：考勤屏上那个二级入口
        // （`AppRoute.Iclassface`）进门时要用它。它也有自己的独立登录链（CAS OAuth2 `client_id=1543`
        // → `org.xjtu.edu.cn` 开放平台中转 → 回落 `/oauth/callback/xjtu_new`，凭据就是 `PHPSESSID`）。
        register(IclassfaceSession())

        // 教材中心（`:data` 的 `JiaocaiSession` + `JiaocaiLogin`）：`jiaocai1`（教材全文库）那两条
        // 路由进门时要用它 —— 全文库**没有自己的登录器，借的就是这个站点**（`AppRoute.Jiaocai1`
        // 的 `loginType` 是 `LoginType.JIAOCAI`；入口 `/entry/login` → CAS → 超星 SSO）。
        // `mustUseWebVpn = true`：校外要过 WebVPN，而桌面目前永远按直连走（见本类的「已知缺口」）。
        // 与校园卡/场馆一样，「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那两屏。
        register(JiaocaiSession())

        // 思源学堂（`:data` 的 `LmsSession` + `LmsLogin`）：`lms` 这条路由进门时要用它
        //（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。它**不借别的已搬站点的会话** ——
        // 直接 CAS 回跳（`LmsLogin.loginUrl` = lms 根路径 → 302 到统一认证 → 回 lms 种会话 cookie，
        // 与教务同型；不是 dzpz/coupon 那种自带独立登录页）。与校园卡/场馆一样，
        // 「登录页那一步」只是尽力预热它，它自己挂了最坏只影响那一屏。
        register(LmsSession())
        // 切账号时清宿主侧共享缓存：`:app` 把它设成 `CampusProbe.ywtbToken = null`，
        // 而 CampusProbe 要 `ConnectivityManager`（宿主能力，没跟着内核搬进 :data）；
        // 桌面端没有那份缓存，所以留空。这正是「缝照真正用到的那几处切」。
    }

    /** 图书馆站点会话。冷启动时它还是「从快照恢复、待确认」，第一次取数会自己探活/重登。 */
    val librarySite: SiteSession get() = sessionManager.getSite(LIBRARY_SITE_KEY)

    /** 体测站点会话。与图书馆同一条会话语义，但它那一半是 https（见 `FitnessApi`）。 */
    val fitnessSite: SiteSession get() = sessionManager.getSite(FITNESS_SITE_KEY)

    /**
     * 教务站点会话。全校课表与成绩报表**共用一个**（同一个子系统），所以只注册一次、
     * 两条路由各自进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。
     */
    val jwxtSite: SiteSession get() = sessionManager.getSite(JWXT_SITE_KEY)

    /** 校园卡站点会话（卡面 + 流水两条取数共用一个上游）。 */
    val campusCardSite: SiteSession get() = sessionManager.getSite(CAMPUS_CARD_SITE_KEY)

    /** 体育场馆站点会话（场馆 / 时段 / 订单三条取数共用一个上游）。 */
    val venueSite: SiteSession get() = sessionManager.getSite(VENUE_SITE_KEY)

    /**
     * 进某一屏之前把它的站点会话建起来（失败就抛给调用方）。
     *
     * 与 `:app` 的导航层同一条口径：那边 `AppRouter.open(route)` 先按 `route.loginType`
     * `ensureSite` 再跳，所以屏自己从不处理「会话未初始化」。桌面端由 `ToolboxDesktopApp` 的
     * `DesktopSiteGate` 调它 —— 这样「某个站点自己挂了」最坏只影响那一屏。
     */
    suspend fun ensureSession(siteKey: String) {
        sessionManager.ensureSite(siteKey, userInitiated = true)
    }

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
     * 冷启动静默恢复：有落盘账号就直接进主界面，**不经过登录页**。
     *
     * 两步（多账号之后）：
     *  1. **账号表里有激活账号** ⇒ 直接切进去（与 Android 的启动恢复同一条口径：`AccountStore
     *     .activeAccount()` → `AccountManager.switchTo` 那一路）；
     *  2. 没有激活账号、但那份**单账号凭据文件**（`xjtu_credentials`）还在 ⇒ 一次性导入成账号表
     *     里的一条，然后走第 1 步。这是桌面端自己的 `AccountMigration`：Android 那份要搬
     *     cookies / DataCache / Room，桌面端 Stage A 从来没写过那些（凭据文件是唯一的遗留），
     *     所以这一支就只有「建一条账号 + 抹掉旧文件」两行 —— 与 `AccountMigration` 同一分工。
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
        // 1) 多账号：账号表里有激活账号就直接切进去
        accountStore.activeAccount()?.let { account ->
            adoptAccount(account)
            return
        }
        // 2) 一次性导入 Stage A 那份单账号凭据（`xjtu_credentials`）
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
        writeActiveAccountId(user)
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
            // 真登录：CAS 表单 POST（密码 RSA 加密）→ TGC → ticket 回跳 → 各站点自己的会话。
            //
            // 登录页这一步**只硬要求主站（图书馆）**：它证明这组凭据真能过统一认证，也是 Stage A
            // 验收那条竖切。其余站点**尽力而为**（失败只记不抛）—— 不能因为某个子系统自己挂了
            // 就把人挡在门外：体测服务历史上真返回过 502（见 `FitnessSession` 的 KDoc），
            // 把它硬塞进 login() 会让「体测挂了」变成「整个桌面端登不进去」。
            // 那些站点在自己那一屏进门时再补登（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`，
            // 与 `:app` 导航层「先 ensureSite 再跳」是同一条口径）。
            // userInitiated = true：用户正等结果，豁免站点级 60 秒冷却（防刷由 CasGate 兜）。
            sessionManager.ensureSite(LIBRARY_SITE_KEY, userInitiated = true)
            SESSION_SITE_KEYS.filter { it != LIBRARY_SITE_KEY }.forEach { key ->
                runCatching { sessionManager.ensureSite(key, userInitiated = true) }
                    .onFailure { sessionManager.recordDiagnostic("WARN", key, "登录时预热失败（不阻塞登录）：${it.message}") }
            }

            // 成功才记住。设备指纹与 RSA 公钥一起存：前者换来换去会被学校当成新设备（多一次短信），
            // 后者省掉每次登录那一枪 `GET /cas/jwt/publicKey`。
            // 成功才记住 —— 写进**账号表**（`AccountStore`），与 Android 的
            // `AccountManager.persistCurrentLogin` 同一分工。设备指纹与 RSA 公钥一起存：
            // 前者换来换去会被学校当成新设备（多一次短信），后者省掉每次登录那一枪
            // `GET /cas/jwt/publicKey`。
            //
            // 本轮之前这里写的是 `:data` 那份**单账号**凭据文件（`JvmCredentialStore`）。多账号
            // 之后账号表是唯一的真相，那份文件降级成「只在 `restore()` 里读一次做导入」
            //（与 Android 的 `AccountMigration` 同一分工：迁完就 `clearLegacyCredentials`）。
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

    /**
     * 退出登录：**凭据、cookie、站点快照一起删**。
     *
     * 为什么不是「只删凭据」（Android 那边退出后 cookie 留着，切回该账号能免密）：
     * 桌面是**可分发**的客户端，可能装在共享机器上，留一个 TGC 在磁盘上等于下一个人能直接
     * 登进上一个人的账号。设计文档 §8 第 3 条把这条列为分发的代价之一：
     * 「每平台凭据存储 + 隐私说明 + 退出登录即清除」。
     *
     * ⚠️ 多账号之后（Stage C）**只退这一条账号**：它的会话、cookies、快照与账号表里的记录
     * 一起删掉（界面上的文案就是「清除本机凭据与这条账号的会话」）。
     * 为什么要删记录而不只是登出：留一条没有会话的记录，下次冷启动 `restore()` 会把它恢复回来，
     * 用户看到的是「我退了，但一开又进去了」。其余账号一条不动 —— 那才是多账号的意义。
     */
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
        // Stage A 那份单账号凭据文件：多账号之后已经不该再有它（`restore()` 导入时就抹了），
        // 这里再抹一次是兼容「从旧版本升级上来、还没重启过」的那一瞬。
        credentials.clear()
        username = ""
        password = ""
        loginState = LoginUiState.Idle
        loggedIn = false
    }

    // ── 多账号（Stage C）：`:core` 的 `AccountsController` 要的那几个内部钩子 ────────────────
    //
    // 为什么钩子在这儿而不是在 `DesktopAccounts` 里：切账号要同时改**四样**东西
    //（会话内核的命名空间、`AccountContext` 的两个字段、这份对象的可观察状态、以及界面输入框），
    // 它们全部是 `DesktopAuth` 的私有状态。`DesktopAccounts` 只管「谁、什么顺序、失败了怎么办」
    //（即 `:app` 的 `AccountManager` 那一层），命名空间与界面状态归这里。

    /**
     * 账号表。多账号的**唯一真相**（Stage A 那份单账号凭据文件只用于一次性导入，见 [restore]）。
     */
    internal val accountStore = AccountStore()

    /**
     * 「当前账号」的**可观察镜像**。
     *
     * `AccountContext.activeAccountId` 是 `:core` 里的普通字段（它不认识 Compose 状态，也不该认识）
     * —— 而多账号页要按它重排列表、高亮当前那一行。这个字段与它同刻写入（只能经
     * [writeActiveAccountId] 写），数据流与 `:app` 那边读 `AppLoginState.accountId` 一致。
     */
    var activeAccountId by mutableStateOf<String?>(null)
        private set

    /** [AccountContext.activeAccountId] 与 [activeAccountId] 必须同刻写入 —— 只能走这里。 */
    private fun writeActiveAccountId(id: String?) {
        AccountContext.activeAccountId = id
        activeAccountId = id
    }

    /**
     * 把一条已保存账号认成「当前身份」：命名空间、凭据、指纹、RSA 公钥、界面状态一起换。
     *
     * 与 `:app` 的 `AccountManager.switchToLocked` 逐条对齐，只少了它那几件 Android 侧的清场
     * （WebView 的 localStorage、Room 自定义课程、按账号的 SharedPreferences）—— 桌面端没有那几样。
     *
     * 注意：**不**写 [username] / [password] 两个界面字段（红线：学号不上屏）。
     */
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

        /**
         * 智慧教室平台（`js`）：空闲教室的「实时状态」那一档用。
         *
         * ⚠️ 它**刻意不在** [SESSION_SITE_KEYS] 里（与其余站点都不同）：那个屏幕有三档数据源，
         * 只有实时状态这一档要登这个站点（CDN 免登录、直查登教务）—— 为了一屏的一档，让
         * **每一次登录**都多走一趟 CAS + 换令牌不划算。所以这一屏的会话由它自己 ensure：
         * `AppRoute.EmptyRoom.loginType` 是 null（导航层不替它建会话），而 `:data` 的
         * `AppEmptyRoomSource.liveSnapshot` 进门就 `ensureSite(JsSession.SITE_KEY)` ——
         * 与 `GraduateJudgeSource` 那条先例同型，所以**不需要** `DesktopSiteGate`。
         */
        const val JS_SITE_KEY = JsSession.SITE_KEY

        /** 体育场馆（`venue`）。那条路由进门时用它（见 `ToolboxDesktopApp` 的 `DesktopSiteGate`）。 */
        const val VENUE_SITE_KEY = VenueSession.SITE_KEY

        /**
         * 一网通办（`ywtb`）：消息收纳那四路取数要它（`:data` 的 `AppInboxSource` 进门自己 ensure）。
         *
         * 与 [JS_SITE_KEY] 不同，它在 [SESSION_SITE_KEYS] 里 —— 这一屏取数**只有**这一个站点，
         * 登录页那一步顺手预热它的代价就是一趟（多半有 TGC 免密的）CAS；`js` 那边是一屏三档里
         * 只有一档要它，才刻意不预。两处都不套 `DesktopSiteGate`：`AppRoute.Inbox.loginType`
         * 本来就是 null（要登哪个站点是数据源的事）。
         */
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
        /**
         * 教材全文库（`jiaocai1`）那两条路由进门时用的站点。
         *
         * ⚠️ 站点 key 是 **`jiaocai`（教材中心）**，不是 `jiaocai1` —— 全文库借的就是教材中心的
         * 会话（`AppRoute.Jiaocai1` 的 `loginType` = `LoginType.JIAOCAI`，`:app` 导航层那句是
         * `WithSite("jiaocai")`），所以常量直接取 [JiaocaiSession.SITE_KEY]，不另造一个字符串。
         */
        const val JIAOCAI_SITE_KEY = JiaocaiSession.SITE_KEY

        /**
         * 登录页那一步一次建起会话的站点：`:core` 里有屏 + `:data` 里有站点类与取数的那几个。
         * （校历 / 黄页 / 教师检索是免登录的公开接口，游戏是纯 UI —— 它们不需要会话。）
         *
         * ⚠️ `js` 是唯一一个**满足上面这条却不在**表里的：只有空闲教室那一屏的三档里的
         * 「实时状态」要它，进屏时由那个源自己 ensure，见 [JS_SITE_KEY]。
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
