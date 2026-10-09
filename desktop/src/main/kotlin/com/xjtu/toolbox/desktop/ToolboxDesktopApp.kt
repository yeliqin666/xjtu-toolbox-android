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
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.LoginScreen
import com.xjtu.toolbox.auth.MfaCodeDialog
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.keyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
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

/** 底栏那三格 —— 每一格都是这一端**真能画**的屏（取数在 `:data`，或压根不需要取数）。 */
internal val DESKTOP_TABS = listOf(
    DesktopTab("图书馆", Icons.Filled.CalendarMonth, DesktopTarget.App(AppRoute.Library)),
    DesktopTab("游戏", Icons.Filled.SportsEsports, DesktopTarget.App(AppRoute.Games)),
    DesktopTab("全部", Icons.Filled.Apps, DesktopTarget.Routes),
)

/**
 * 这一端**真能画**的路由（`:core` 里有屏 + 取数在 `:data`，两者缺一不可）。
 *
 * 图书馆是唯一一条走 `:data` 取数的；其余是纯 UI 的游戏（与数据源无关，三端同一份）。
 */
internal val DESKTOP_SUPPORTED_ROUTES = listOf(
    AppRoute.Library to "图书馆座位",
    AppRoute.Games to "游戏合集",
    AppRoute.Game2048 to "GPA 2048",
    AppRoute.GameBlocks to "方块",
    AppRoute.GameGomoku to "五子棋",
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
    AppRoute.SchoolCalendar to "校历",
    AppRoute.Fitness to "体测",
    AppRoute.ScoreReport to "成绩",
    AppRoute.YellowPage to "黄页",
    AppRoute.Notification to "通知公告",
    AppRoute.Faculty to "教师检索",
    AppRoute.SchoolCourse to "全校课表",
    AppRoute.Inbox to "消息收纳",
    AppRoute.EmptyRoom to "空闲教室",
    AppRoute.CampusCard to "校园卡",
    AppRoute.Venue to "体育场馆",
    AppRoute.Judge to "评教",
)

@Composable
private fun DesktopBottomBar(selected: DesktopTarget, onSelect: (DesktopTarget) -> Unit) {
    // 与 App 的「经典底栏」同一个组件、同一个 mode；只是格数不同（见文件头）。
    // 当前页不在底栏那三格里（例如某个游戏子屏）⇒ 一格都不高亮，这是对的：
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
 */
@Composable
private fun DesktopPage(auth: DesktopAuth, route: AppRoute, onNavigate: (DesktopTarget) -> Unit) {
    // 这一端没有返回栈，底栏就是导航 ⇒ 屏上的返回一律回图书馆首页（与 Stage 0 同一条口径）
    val back = { onNavigate(DesktopTarget.App(AppRoute.Library)) }
    when (route) {
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
        AppRoute.Games -> GamesScreen(
            onBack = back,
            onNavigate = { onNavigate(DesktopTarget.App(it)) },
            // 桌面没有 BLE ⇒ 棋类那几行不显示「联机」，也不许诺一个点了会失败的模式
            supportsOnline = false,
        )
        AppRoute.Game2048 -> Gpa2048Screen(onBack = back)
        AppRoute.GameBlocks -> BlocksScreen(onBack = back)
        AppRoute.GameGomoku -> GomokuScreen(onBack = back)
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
