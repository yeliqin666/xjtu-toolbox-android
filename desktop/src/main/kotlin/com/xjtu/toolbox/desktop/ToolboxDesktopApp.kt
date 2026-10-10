package com.xjtu.toolbox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginScreen
import com.xjtu.toolbox.auth.MfaCodeDialog
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.emptyroom.AppEmptyRoomSource
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.fitness.FitnessApi
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.judge.GraduateJudgeSource
import com.xjtu.toolbox.judge.GraduateQuestionnaire
import com.xjtu.toolbox.judge.JudgeListScreen
import com.xjtu.toolbox.judge.JudgeViewModel
import com.xjtu.toolbox.judge.Questionnaire
import com.xjtu.toolbox.judge.UndergraduateJudgeSource
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.hop.HopScreen
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.score.scoreReportSource
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 桌面外壳（窗口模式 · Stage A 第二步）。
 *
 * ## 它现在是什么
 *
 * 一层很薄的壳，只做四件事：
 *  1. 没登录 → [LoginScreen]（`:core` 的共享登录屏），登录状态由 [DesktopAuth] 持有；
 *  2. 已登录 → 底栏 + 当前页；
 *  3. 全程挂着 MFA 弹窗宿主（登录本身就可能要短信二验，登录页上也一样要）；
 *  4. 「全部页面」那张索引页如实列出**哪些路由真能用、哪些还没搬**。
 *
 * 数据全来自 `:data`（会话内核 + `LibraryApi`），**没有任何我方的服务器**（设计文档 C2）。
 *
 * ## 与 `:web` 的外壳是什么关系
 *
 * 同一批屏、同一套路由表（`AppRoute`），差别只有两处，两处都是**平台决定的**：
 *
 * | | `:web` | `:desktop` |
 * |---|---|---|
 * | 数据从哪来 | 同源反代到 campus-api（浏览器只能这样） | 进程内直取 `:data`（自己登录、自己取数） |
 * | 外链怎么开 | `window.open` / `location.assign` | `java.awt.Desktop.browse` |
 *
 * ## 底栏只有 3 格（诚实的一条）
 *
 * 底栏**只放这一端真能用的东西**：图书馆（唯一搬进 `:data` 的站点）+ 游戏（纯 UI，与数据无关）+
 * 「全部」。Stage 0 那 19 条路由里其余的 12 条，屏在 `:core` 但取数还在 `:app` 的 `Campus*Api` /
 * 站点类里 —— 点了会看到 [NotPortedScreen] 把这些话写在脸上，而不是落回某一屏假装能用。
 *
 * 屏上的「返回」箭头一律回图书馆首页：这一端没有返回栈（底栏就是导航），与 Stage 0 那条
 * 「返回 = 回默认页」同一条口径。
 *
 * @param auth 会话 + 凭据 + 登录状态（`Main.kt` 与离屏证据各建一个）
 * @param initialTarget 首屏。默认图书馆 —— 它是这一端唯一有真数据的屏。
 */
@Composable
internal fun ToolboxDesktopApp(
    auth: DesktopAuth,
    initialTarget: DesktopTarget = DesktopTarget.App(AppRoute.Library),
) {
    var target by remember { mutableStateOf(initialTarget) }
    val scope = rememberCoroutineScope()

    // 冷启动：有落盘凭据就直接进主界面（幂等，重复组合无害）
    LaunchedEffect(auth) { auth.restore() }

    if (!auth.loggedIn) {
        LoginScreen(
            title = "西交工具箱",
            subtitle = "用你的校园账号登录。凭据只留在本机，取数直接从学校站点走。",
            username = auth.username,
            onUsernameChange = { auth.username = it; auth.onLoginInputChanged() },
            password = auth.password,
            onPasswordChange = { auth.password = it; auth.onLoginInputChanged() },
            state = auth.loginState,
            onSubmit = { scope.launch { auth.login() } },
            footer = "凭据存成权限 0600 的文件（只有你这台机器的这个用户可以读）。" +
                "登录需要两步验证时会在窗口里弹出来。",
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (val t = target) {
                    is DesktopTarget.App -> DesktopPage(auth, t.route) { target = it }
                    DesktopTarget.Routes -> RoutesPage(auth) { target = it }
                }
            }
            DesktopBottomBar(selected = target) { target = it }
        }
    }

    // 登录页与主界面都要挂：登录本身就可能要短信二验
    DesktopMfaDialog(auth)
}

/** 当前页：`:core` 路由表里的一屏，或「全部页面」那张索引页。 */
internal sealed interface DesktopTarget {
    data class App(val route: AppRoute) : DesktopTarget
    data object Routes : DesktopTarget
}

internal data class DesktopTab(val label: String, val icon: ImageVector, val target: DesktopTarget)

/** 底栏那五格 —— 每一格都是这一端**真能画**的屏（取数在 `:data`，或压根不需要取数）。 */
internal val DESKTOP_TABS = listOf(
    DesktopTab("校历", Icons.Filled.EventNote, DesktopTarget.App(AppRoute.SchoolCalendar)),
    DesktopTab("图书馆", Icons.Filled.CalendarMonth, DesktopTarget.App(AppRoute.Library)),
    DesktopTab("黄页", Icons.Filled.Phone, DesktopTarget.App(AppRoute.YellowPage)),
    DesktopTab("游戏", Icons.Filled.SportsEsports, DesktopTarget.App(AppRoute.Games)),
    DesktopTab("全部", Icons.Filled.Apps, DesktopTarget.Routes),
)

/**
 * 这一端**真能画**的路由（`:core` 里有屏 + 取数在 `:data`，两者缺一不可）。
 *
 * 十条真取数：图书馆 / 体测 / 全校课表 / 成绩 / 评教 / 校园卡（**要登录**，走会话内核 + 进那一屏再建会话，
 * 见 `DesktopSiteGate`）、空闲教室（三档数据源要登的站点不同 ⇒ **由源自己 ensure**，见那一段的注释）、
 * 校历 / 黄页 / 教师检索（**免登录**的公开门户接口）。
 * 其余是纯 UI 的游戏（与数据源无关，三端同一份）。
 */
internal val DESKTOP_SUPPORTED_ROUTES = listOf(
    AppRoute.SchoolCalendar to "校历",
    AppRoute.Library to "图书馆座位",
    AppRoute.Fitness to "体测",
    AppRoute.SchoolCourse to "全校课表",
    AppRoute.ScoreReport to "成绩",
    AppRoute.Judge to "学生评教",
    AppRoute.CampusCard to "校园卡",
    AppRoute.EmptyRoom to "空闲教室",
    AppRoute.YellowPage to "黄页",
    AppRoute.Faculty to "教师检索",
    AppRoute.Games to "游戏合集",
    AppRoute.Game2048 to "GPA 2048",
    AppRoute.GameBlocks to "方块",
    AppRoute.GameGomoku to "五子棋",
    AppRoute.GameHop to "跳一跳",
    AppRoute.GameGo to "围棋",
    AppRoute.GameXiangqi to "象棋",
)

/**
 * **屏已经搬进 `:core`、但这一端还没有数据源**的路由 —— 也就是 Stage A 剩下的进度表。
 *
 * 它们卡的都是同一件事：取数还在 `:app`（`Campus*Api` 的只读端，或某个站点的 `*Login` + 站点类）。
 * 搬到 `:data` 一条，这里就划掉一条（`docs/desktop-port-plan.md` §5.1／§3.2）。
 */
internal val DESKTOP_PENDING_ROUTES = listOf(
    AppRoute.Notification to "通知公告",
    AppRoute.Inbox to "消息收纳",
    AppRoute.Venue to "体育场馆",
)

@Composable
private fun DesktopBottomBar(selected: DesktopTarget, onSelect: (DesktopTarget) -> Unit) {
    // 与 App 的「经典底栏」同一个组件、同一个 mode；只是格数不同（见文件头）。
    // 当前页不在底栏那五格里（例如某个游戏子屏、或教师检索）⇒ 一格都不高亮，这是对的：
    // 底栏是「去哪儿」，不是「你从哪儿来」。
    val selectedIndex = DESKTOP_TABS.indexOfFirst { it.target == selected }
    NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
        DESKTOP_TABS.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(tab.target) },
                icon = tab.icon,
                label = tab.label,
            )
        }
    }
}

/**
 * 一屏共享页。每个屏只注入**这一端能提供的东西**：
 * - 图书馆：取数换成 `:data`（真会话 + 真 `LibraryApi`），并且**重新认证那一枪也能打**
 *   （宿主手里就有凭据与会话 —— 这正是只读端没有的能力，见 `LibraryScreen` 的 `reAuthenticate`）；
 * - 外链交给系统浏览器（`java.awt.Desktop.browse`）；
 * - 「功能说明弹过了没」这类本地偏好走 `:core` 的 `keyValueStore`（JVM 侧是落盘的 Properties 文件）。
 *
 * 需要站点会话的屏（现在只有体测）走 [DesktopSiteGate] —— 会话按需建立，见那一段的 KDoc。
 */

/**
 * 「进这一屏之前先把它的站点会话建起来」—— 与 `:app` 同一条口径：那边是导航层
 * `AppRouter.open(route)` 先按 `route.loginType` 的 `ensureSite` 再跳，所以屏自己从不处理
 * 「会话未初始化」。
 *
 * 为什么不放在登录页那一步：体测服务历史上真返回过 502（见 `:data` 的 `FitnessSession` 的 KDoc），
 * 把每个站点都塞进 `login()` 会让「某个子系统自己挂了」变成「整个桌面端登不进去」。
 * 放在路由上，最坏只是这一屏自己报错 —— 而且这里给了「重试」，服务恢复后点一下就回来。
 */
@Composable
private fun DesktopSiteGate(
    auth: DesktopAuth,
    siteKey: String,
    siteName: String,
    content: @Composable (SiteSession) -> Unit,
) {
    val site = auth.sessionManager.getSiteOrNull(siteKey)
    var attempt by remember(siteKey) { mutableIntStateOf(0) }
    var failure by remember(siteKey) { mutableStateOf<String?>(null) }
    var ready by remember(siteKey) { mutableStateOf(site?.hasLogin == true) }

    LaunchedEffect(siteKey, attempt) {
        if (ready) return@LaunchedEffect
        failure = null
        ready = runCatching { auth.ensureSession(siteKey) }.fold(
            onSuccess = { true },
            onFailure = { failure = FriendlyError.of(it, "建立$siteName 会话"); false },
        )
    }

    when {
        ready && site != null -> content(site)
        failure != null -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "$siteName 会话没建起来",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(failure.orEmpty(), color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
            Text(
                "这一屏需要先登录「$siteName」。它与图书馆是同一套凭据；这里失败通常是那个子系统自己出问题" +
                    "（体测服务历史上返回过 502），不是账号问题。",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 12.sp,
            )
            TextButton(text = "重试", onClick = { attempt++ }, minWidth = 120.dp)
        }
        else -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                "正在准备$siteName 会话…",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * 一屏共享页 —— 见文件上方那段 KDoc（「每个屏只注入这一端能提供的东西」）。
 */
@Composable
private fun DesktopPage(auth: DesktopAuth, route: AppRoute, onNavigate: (DesktopTarget) -> Unit) {
    // 这一端没有返回栈，底栏就是导航 ⇒ 屏上的返回一律回图书馆首页（与 Stage 0 同一条口径）
    val back = { onNavigate(DesktopTarget.App(AppRoute.Library)) }
    when (route) {
        // 校历：**免登录**的公开门户接口 ⇒ 不需要任何会话适配器，`：data` 的取数直接用
        //（这正是「屏在 `:core` + 取数在 `:data`」的报偿：搬一个 35 行的 IO 适配器就多一屏）。
        // `calendarImage` 不传：那是 `:app` 的教务处图片接口（`SchoolCalendarImageApi`），
        // 用到 `Intent` 与 `DataCache`，还没搬——不传就是不画那一档，不假装有。
        AppRoute.SchoolCalendar -> SchoolCalendarScreen(
            source = remember { SchoolCalendarApi() },
            onBack = back,
        )
        // 教师检索：**免登录**（`faculty.xjtu.edu.cn` 检索 + `gr.xjtu.edu.cn` 主页），取数已搬进 `:data`。
        // 适配器 `FacultyApiSource` 也是共享的（`:app` 与桌面用同一份，不再各写一份）。
        // 头像用默认的 `InitialsFacultyAvatar`（首字圆）：`:app` 那份是 BitmapFactory + LruCache
        //（Android 专属），而 `gr.xjtu.edu.cn` 不给 CORS 头 —— 不假装能取到图。
        AppRoute.Faculty -> FacultyScreen(
            source = remember { FacultyApiSource() },
            onBack = back,
            onOpenUrl = { openInBrowser(it) },
        )
        // 黄页：另一条**免登录**的公开门户接口，而且 `:core` 里那份 `YellowPageApi` 早就搬完了
        //（第 1 步 okhttp→Ktor 时搬的，端口收一个 `HttpClient`）⇒ 桌面只需给一条客户端。
        // `cache = null`：`:core` 的黄页缓存缝要一个按账号隔离的落盘实现（`:app` 是 `DataCache`），
        // 桌面端还没有那份宿主存储（属 §5.4）——不缓存，直接请求，屏上的语义不变。
        AppRoute.YellowPage -> YellowPageScreen(
            api = remember { YellowPageApi(yellowPageClient) },
            onBack = back,
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
        AppRoute.Library -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LibraryScreen(
                source = auth.librarySource,
                onBack = back,
                reAuthenticate = {
                    // 用户点「重新认证」时强制重登一次。`:app` 那一份读的是 App 的凭据 +
                    // `site.ensureLogin(force = true)`；桌面端的凭据就在会话管家里，所以直接走
                    // `ensureSite(userInitiated = true)`（凭据已在，失败会抛出来给屏显示）。
                    auth.sessionManager.ensureSite(DesktopAuth.LIBRARY_SITE_KEY, userInitiated = true)
                },
                showFirstUseHint = !hintPrefs.getBoolean("library_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("library_hint_shown", true) },
            )
        }
        // 体测：站点是 **https**，取数走 `:data` 的 `FitnessApi`（v3 加密协议优先、失败退回 legacy PHP）。
        // 站点会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它 ——
        // 体测服务历史上真的返回过 502，不能因为它挂了就把人挡在登录页外。
        AppRoute.Fitness -> DesktopSiteGate(auth, DesktopAuth.FITNESS_SITE_KEY, "体测") { site ->
            FitnessScreen(
                source = remember(site) { FitnessApi(site) },
                onBack = back,
            )
        }
        // 教务（全校课表）：站点会话**进门时才建**（见 `DesktopSiteGate`）。取数就是 `:data` 里那份
        // `AppSchoolCourseSource`（包住原来的 `SchoolCourseApi`）—— `:core` 的屏只认端口。
        AppRoute.SchoolCourse -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
            SchoolCourseScreen(
                source = remember(site) { AppSchoolCourseSource(site) },
                onBack = back,
            )
        }
        // 成绩：同一个教务站点（两条路由共用一份会话）。取数要一个**学号**，它闭在适配器里；
        // 桌面端没有登录取学号那一套（`LoginScreen` 只有账号密码，学号不落屏），所以用登录时
        // 写进 `AccountContext.activeAccountId` 的那个 —— 它**取不到就画错误页**，绝不拿空学号
        // 去请求（那会遇到一张“查无此人”的报表，看起来像屏坏了）。
        AppRoute.ScoreReport -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
            val studentId = AccountContext.activeAccountId?.takeIf { it.isNotBlank() }
            if (studentId == null) {
                MissingIdentityPage(
                    onNavigate = onNavigate,
                    title = "成绩报表要一个学号，现在取不到",
                    lines = listOf(
                        "成绩报表按学号取数，学号来自这次登录。取不到通常是登录还没走完，或者这一份装配是" +
                            "从落盘凭据恢复的 —— 重新登录一次即可。",
                        "教务那边的会话本身是好的（能进这一屏就说明它建起来了）。",
                    ),
                )
            } else {
                ScoreReportScreen(
                    source = remember(site, studentId) { scoreReportSource(site, studentId) },
                    onBack = back,
                )
            }
        }

        // 评教（第八条真数据路由）—— 本科 / 研究生是**两条不同的链路**，与 `:app` 的导航层同一个判据
        // （`AccountContext.activeAccountType`，即 `AppRoute.Judge.loginType` 里那个 `isPostgraduateSession()`）：
        //
        // - **本科**：与全校课表 / 成绩**共用同一个教务站点**（所以走同一个 `DesktopSiteGate`）。参评人
        //   （`CPR`）就是登录时写进 `AccountContext.activeAccountId` 的学号 —— 取不到就画说明页，
        //   不拿空学号去请求（那会领回一张空卷子，看着像屏坏了）；
        // - **研究生**：问卷在 gste、课程信息在 gmis。这条**不套 Gate**：`GraduateJudgeSource` 自己
        //   `ensureSite`（gste 进屏时登、gmis 到一键评教时才登）—— 这正是 `:app` 那边的语义，
        //   只是把宿主那一发缓到真正取数的时候。
        AppRoute.Judge -> if (AccountContext.activeAccountType == AccountType.POSTGRADUATE) {
            val source = remember { GraduateJudgeSource(auth.sessionManager) }
            val vm: JudgeViewModel<GraduateQuestionnaire> =
                viewModel(key = "judge-postgraduate") { JudgeViewModel(source) }
            JudgeListScreen("学生评教", vm, back)
        } else {
            DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
                val username = AccountContext.activeAccountId?.takeIf { it.isNotBlank() }
                if (username == null) {
                    MissingIdentityPage(
                        onNavigate = onNavigate,
                        title = "评教要一个学号，现在取不到",
                        lines = listOf(
                            "评教是按学号取「我的问卷」的，学号来自这次登录。取不到通常是登录还没走完，或者" +
                                "这一份装配是从落盘凭据恢复的 —— 重新登录一次即可。",
                            "教务那边的会话本身是好的（能进这一屏就说明它建起来了）。",
                        ),
                    )
                } else {
                    val source = remember(site, username) { UndergraduateJudgeSource(site, username) }
                    val vm: JudgeViewModel<Questionnaire> =
                        viewModel(key = "judge-undergraduate") { JudgeViewModel(source) }
                    JudgeListScreen("学生评教", vm, back)
                }
            }
        }

        // 校园卡（第九条真数据路由）：站点是 **https**，取数走 `:data` 的 `AppCampusCardSource`
        // （包住原来的 `CampusCardApi`）。会话**进门时才建**（见 `DesktopSiteGate`）—— 登录页那一步
        // 只是尽力预热它，ncard 自己挂了不该把人挡在门外。
        // 缓存传 `null`：桌面没有按账号分文件的宿主存储（属 §5.4），不缓存 —— 与 Web 端同一条口径：
        // 屏仍旧自己取数，只是没有「首屏秒开」那一档。加餐券那条二级入口也不传（桌面没有那份首页摘要）。
        AppRoute.CampusCard -> DesktopSiteGate(auth, DesktopAuth.CAMPUS_CARD_SITE_KEY, "校园卡") { site ->
            CampusCardScreen(
                source = remember(site) { AppCampusCardSource(site) },
                onBack = back,
            )
        }

        // 空闲教室（第十条真数据路由）：三档数据源（实时状态 / CDN 课表 / 直查教务）都在 `:data`
        // 的 `AppEmptyRoomSource` 里 —— 会话由**那个源自己 ensure**（实时状态那一档登智慧教室
        // `js`、直查那一档登教务），所以这一条**不套** `DesktopSiteGate`：`AppRoute.EmptyRoom.loginType`
        // 本来就是 null（「按数据源不同要登的站点不同，由页面自己登」），与研究生评教那条同型。
        // 落盘传 `null`：桌面没有按账号分命名空间的宿主存储（属 §5.4），屏仍旧自己取数，
        // 只是少了下拉刷新之外的那一档磁盘兜底 —— 与 Web / 校园卡同一条口径。
        // 「CDN 说明读没读过」与 Web 同一个键、同一个 pref 文件（`empty_room`，屏自己的偏好也在那儿）。
        AppRoute.EmptyRoom -> {
            val prefs = remember { keyValueStore("empty_room") }
            EmptyRoomScreen(
                source = remember { AppEmptyRoomSource(auth.sessionManager) },
                // 身份证：与其余端一样传当前账号类型（研究生在屏上不提供直查教务那一档）
                accountType = AccountContext.activeAccountType,
                onBack = back,
                showCdnTip = !prefs.getBoolean("empty_room_cdn_tip", false),
                onCdnTipRead = { prefs.putBoolean("empty_room_cdn_tip", true) },
            )
        }

        AppRoute.Games -> GamesScreen(
            onBack = back,
            onNavigate = { onNavigate(DesktopTarget.App(it)) },
            // 桌面没有 BLE ⇒ 棋类那几行不显示「联机」，也不许诺一个点了会失败的模式
            supportsOnline = false,
        )
        AppRoute.Game2048 -> Gpa2048Screen(onBack = back)
        AppRoute.GameBlocks -> BlocksScreen(onBack = back)
        AppRoute.GameGomoku -> GomokuScreen(onBack = back)
        // 跳一跳：纯 UI（不需要任何取数）。远景地标图**不传** —— 那是平台资源
        //（`:app` = R.drawable.hop_landmark_*，Web = 它自己的取图），桌面端没有那份资源；
        // 屏自己写着「空列表 = 本端没图，就不画远景（游戏照常可玩）」，所以这是如实降级、不是坏掉。
        AppRoute.GameHop -> HopScreen(onBack = back)
        AppRoute.GameGo -> GoScreen(onBack = back)
        AppRoute.GameXiangqi -> XiangqiScreen(onBack = back)
        // 其余路由**如实说「没搬过来」**，不拿有数据的屏冒充（深链/误点看着像 bug，也分不清
        // 「没实现」与「坏了」）。
        else -> NotPortedScreen(route, onNavigate)
    }
}

/**
 * 全部页面：先列**真能用**的，再列**屏在了但没数据源**的。这一页同时是 Stage A 的进度表，
 * 也是「退出登录」的唯一入口（桌面端还没有设置页 —— 那属于 Stage C）。
 */
@Composable
internal fun RoutesPage(auth: DesktopAuth, onNavigate: (DesktopTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("桌面端（Stage A）", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "屏全部来自 :core —— 与 Android、Web 是同一份源码。取数来自 :data：" +
                "这一端自己登录、自己直连学校站点，不经过任何我方的服务器。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            // 只说「已登录」，不显示学号/姓名（红线：身份该出现在账号页，不该出现在索引页）
            "已登录 · 会话已就绪（图书馆站点）",
            color = cs.primary,
            fontSize = 12.sp,
        )

        Text("真能用（${DESKTOP_SUPPORTED_ROUTES.size}）", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        DESKTOP_SUPPORTED_ROUTES.forEach { (route, label) ->
            TextButton(
                text = "$label（${route.id}）",
                onClick = { onNavigate(DesktopTarget.App(route)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            "屏在了、还没有数据源（${DESKTOP_PENDING_ROUTES.size}）",
            color = cs.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "下面这些屏已经是 :core 的共享屏，卡的是**取数**：它们的 `*Api` / 站点类还在 :app。" +
                "搬到 :data 一条就能用一条。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        DESKTOP_PENDING_ROUTES.forEach { (route, label) ->
            TextButton(
                text = "$label（${route.id}）",
                onClick = { onNavigate(DesktopTarget.App(route)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TextButton(
            text = "退出登录（清除本机凭据与这条账号的会话）",
            onClick = { auth.logout() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "退出会删掉：凭据文件、这条账号的 cookie 与站点快照。" +
                "共享的机器上请不要留着登录态。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
    }
}

/**
 * 「这一屏还没搬到桌面」的占位页 —— 与 `:web` 的 `NotPortedScreen` 同一套口径：
 * 把**具体哪一条没搬**、**它卡在哪**写在脸上，而不是落回某一屏假装能用。
 */
@Composable
private fun NotPortedScreen(route: AppRoute, onNavigate: (DesktopTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("这一屏还没搬到桌面", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("AppRoute.${route::class.simpleName ?: "?"}", color = cs.primary, fontSize = 13.sp)
        Text("路由 id：${route.id}", color = cs.onBackgroundVariant, fontSize = 12.sp)
        Text(
            if (route.loginType != null)
                "它需要 ${route.loginType} 的站点会话 —— 那个站点的类与它的 *Login 还在 :app，" +
                    "没搬进 :data。搬一个站点就有数据源。"
            else
                "它的取数还在 :app / campus-api 的只读端，没进 :core / :data。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            "桌面的取数不走 campus-api（设计文档 C2：任何人独立安装、独立登录）——" +
                "所以 Stage 0 那批脚手架屏幕这一轮换成了如实说明，而不是继续借 campus-api 画画面。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}

/**
 * 「没有身份就取不了数」的如实说明 —— 现在成绩报表与本科评教两屏会走到这里。
 *
 * 它们的取数都要一个**学号**，而桌面端的学号来自登录时写进 `AccountContext.activeAccountId` 的那个值
 * （`JwappScoreViewModel` 在 App 上用 `loginState.activeUsername`，同一件事）。取不到时不拿空学号
 * 去请求 —— 教务会回一张「查无此人」的报表、或一张空卷子，看上去像屏坏了；也不直接崩，那连回退都没有了。
 *
 * 两屏整合到同一个页而不是各写一份：差别只有标题与那两句话，画法与回退入口是同一套。
 */
@Composable
private fun MissingIdentityPage(
    onNavigate: (DesktopTarget) -> Unit,
    title: String,
    lines: List<String>,
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        lines.forEachIndexed { index, line ->
            Text(
                line,
                color = cs.onBackgroundVariant,
                fontSize = if (index == 0) 13.sp else 12.sp,
            )
        }
        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}

/**
 * MFA（短信二验）弹窗宿主：把 `:data` 的 `SessionManager.activeMfaRequest` 接到 `:core` 的
 * 哑视图 [MfaCodeDialog] 上。
 *
 * 与 `:app` 的 `MfaDialogHost` 逐条对齐的四件事：
 *  1. **挂载登记**：挂上时 `attachMfaHost()`，卸载时反登记 —— 没有任何宿主时
 *     `SessionManager.verifyMfaWithUser` 会直接报错，而不是占着登录锁空等（`MfaWaitTimeout` 是 150 秒，
 *     那段时间同一边其余站点全登不上）；
 *  2. **取手机号**：在 IO 上取，取不到就报错（不静默地显示一个假号码）；
 *  3. **被拒不关窗**：`rejections` 一涨就清空输入并提示重输（最多 3 次，`SessionManager` 那边数）；
 *  4. **取消必须回传**：`req.cancel()` —— 否则登录锁一直挂着。
 */
@Composable
private fun DesktopMfaDialog(auth: DesktopAuth) {
    val manager = auth.sessionManager
    DisposableEffect(manager) {
        val detach = manager.attachMfaHost()
        onDispose { detach() }
    }

    val active by manager.activeMfaRequest.collectAsState()
    val request = active ?: return

    var phone by remember(request) { mutableStateOf<String?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val rejections by request.rejections.collectAsState()

    LaunchedEffect(request) {
        runCatching { withContext(Dispatchers.IO) { request.mfaContext.getPhoneNumber() } }
            .onSuccess { phone = it }
            .onFailure { error = FriendlyError.of(it, "获取验证手机号") }
    }
    LaunchedEffect(rejections) { if (rejections > 0) error = "验证码不对，请重新输入" }

    MfaCodeDialog(
        siteName = request.siteName,
        phone = phone,
        error = error,
        // 被拒一次就换一个 attempt ⇒ 输入框自己清空（见 MfaCodeDialog 的 attempt 参数）
        attempt = rejections,
        onSubmit = { code -> if (!request.submit(code)) error = "提交失败" },
        onCancel = { request.cancel() },
    )
}
