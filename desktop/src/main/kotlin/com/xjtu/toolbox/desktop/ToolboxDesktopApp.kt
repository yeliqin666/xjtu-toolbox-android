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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.core.net.CampusCardNetApi
import com.xjtu.toolbox.core.net.CampusEmptyRoomApi
import com.xjtu.toolbox.core.net.CampusFacultyApi
import com.xjtu.toolbox.core.net.CampusFitnessApi
import com.xjtu.toolbox.core.net.CampusGradesApi
import com.xjtu.toolbox.core.net.CampusInboxApi
import com.xjtu.toolbox.core.net.CampusLibraryApi
import com.xjtu.toolbox.core.net.CampusNoticeApi
import com.xjtu.toolbox.core.net.CampusSchoolCalendarApi
import com.xjtu.toolbox.core.net.CampusSchoolCourseApi
import com.xjtu.toolbox.core.net.CampusVenueApi
import com.xjtu.toolbox.core.net.CampusYellowPageApi
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.inbox.InboxScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import io.ktor.client.HttpClient
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 桌面外壳（阶段 0 · 窗口模式）。
 *
 * ## 它与 `:web` 的外壳是什么关系
 *
 * 同一套思路、同一批屏，差别只有两处，两处都是**平台决定的**、不是取舍：
 *
 * | | `:web` | `:desktop` |
 * |---|---|---|
 * | 数据从哪来 | 同源反代到 campus-api（浏览器只能这样） | 直连 `127.0.0.1:3099`（进程内，无同源概念） |
 * | 外链怎么开 | `window.open` / `location.assign` | `java.awt.Desktop.browse` |
 *
 * 屏、ViewModel、模型、端口、`AppRoute` 路由表**一份都不用改** —— 这正是「屏在 `:core`」的兑现。
 *
 * ## 阶段 0 的诚实降级（都不是「坏了」）
 *
 * - 课表：那一屏还在 `:app`（Android 专属依赖没摘），`Web 有自己的 ScheduleScreen.kt`，
 *   桌面这边没有 ⇒ 走 [NotPortedScreen] 如实说没搬；
 * - 社区：登录态与设备码流程在两端各不相同（Web 是「粘贴 token」，桌面还没有），本轮不画；
 * - 写操作：脚手架数据源是 campus-api（只读）⇒ 各屏自己按 `canBook`/`canSubmit` 把按钮藏起来，
 *   这正是端口上那些能力开关存在的意义；
 * - 收藏/首用提示等本地偏好在 jvm 侧是**内存**实现（`KeyValueStore.jvm.kt`），退出进程就没了
 *   —— 落盘版属于 Stage A 的「宿主存储实现」，见设计文档 §5.1。
 *
 * ## 底栏只有 6 格
 *
 * 桌面窗口比手机宽，但底栏不是桌面该有的形态（Stage A 会随账号/设置一起重做导航）。
 * 阶段 0 只要一件事可验证：**这些屏在桌面上真渲染**。所以 5 个直达格 + 一格「全部页面」
 * （把能画的 15 条路由列出来，点了就进）——比塞 15 个格子诚实，也不假装有导航设计。
 */
@Composable
fun ToolboxDesktopApp() {
    val client = remember { desktopCampusClient() }
    var target: DesktopTarget by remember { mutableStateOf(DesktopTarget.App(AppRoute.SchoolCalendar)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (val t = target) {
                is DesktopTarget.App -> DesktopPage(t.route, client) { target = it }
                DesktopTarget.Routes -> RoutesPage(client) { target = it }
            }
        }
        DesktopBottomBar(selected = target) { target = it }
    }
}

/** 当前页：`:core` 路由表里的一屏，或「全部页面」那张索引页。 */
internal sealed interface DesktopTarget {
    data class App(val route: AppRoute) : DesktopTarget
    data object Routes : DesktopTarget
}

internal data class DesktopTab(val label: String, val icon: ImageVector, val target: DesktopTarget)

/** 底栏那 5 个直达格 —— 每一格都是 `:core` 里真实存在、且这一端有取数的屏。 */
internal val DESKTOP_TABS = listOf(
    DesktopTab("校历", Icons.Filled.EventNote, DesktopTarget.App(AppRoute.SchoolCalendar)),
    DesktopTab("成绩", Icons.Filled.Grade, DesktopTarget.App(AppRoute.ScoreReport)),
    DesktopTab("图书馆", Icons.Filled.CalendarMonth, DesktopTarget.App(AppRoute.Library)),
    DesktopTab("黄页", Icons.Filled.Phone, DesktopTarget.App(AppRoute.YellowPage)),
    DesktopTab("游戏", Icons.Filled.SportsEsports, DesktopTarget.App(AppRoute.Games)),
)

/**
 * 这一端**真能画**的路由（`:core` 里有屏 + `:core` 里有 campus-api 取数，两者缺一不可）。
 *
 * 与 `:web` 的 `AppPage` 是同一批，只少一个「社区」（那边是 Web 专有的 token 登录）。
 * 这份清单同时是「全部页面」索引页的数据源，也是 Stage 0 的进度表：
 * 不在里面的路由点进去会看到 [NotPortedScreen] 如实说明。
 */
internal val DESKTOP_SUPPORTED_ROUTES = listOf(
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
    AppRoute.Library to "图书馆座位",
    AppRoute.Judge to "评教（只读）",
    AppRoute.Games to "游戏合集",
    AppRoute.Game2048 to "GPA 2048",
    AppRoute.GameBlocks to "方块",
    AppRoute.GameGomoku to "五子棋",
    AppRoute.GameGo to "围棋",
    AppRoute.GameXiangqi to "象棋",
)

@Composable
private fun DesktopBottomBar(selected: DesktopTarget, onSelect: (DesktopTarget) -> Unit) {
    // 与 App 的「经典底栏」同一个组件、同一个 mode；只是格数不同（见 ToolboxDesktopApp 的 KDoc）
    NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
        DESKTOP_TABS.forEach { tab ->
            NavigationBarItem(
                selected = (selected as? DesktopTarget.App)?.route == (tab.target as? DesktopTarget.App)?.route,
                onClick = { onSelect(tab.target) },
                icon = tab.icon,
                label = tab.label,
            )
        }
        NavigationBarItem(
            selected = selected is DesktopTarget.Routes,
            onClick = { onSelect(DesktopTarget.Routes) },
            icon = Icons.Filled.Apps,
            label = "全部",
        )
    }
}

/**
 * 一屏共享页。每个屏只注入**这一端能提供的东西**：
 * - 取数换成 campus-api 版（直连本机 3099），只读能力开关由那些类自己声明；
 * - 外链交给系统浏览器（`java.awt.Desktop.browse`）；
 * - 「功能说明弹过了没」这类偏好走 `:core` 的 `keyValueStore`（jvm 侧目前是内存实现）。
 */
@Composable
private fun DesktopPage(route: AppRoute, client: HttpClient, onNavigate: (DesktopTarget) -> Unit) {
    val back = { onNavigate(DesktopTarget.App(AppRoute.SchoolCalendar)) }
    when (route) {
        AppRoute.SchoolCalendar -> SchoolCalendarScreen(
            source = remember { CampusSchoolCalendarApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
        )
        AppRoute.Fitness -> FitnessScreen(
            source = remember { CampusFitnessApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
        )
        AppRoute.ScoreReport -> ScoreReportScreen(
            source = remember { CampusGradesApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
            // 缓存：桌面端还没有「按账号隔离的落盘缓存」（那属于 Stage A 的宿主存储实现）
            // ⇒ 不传 cache。屏自己 errored/refreshing 的语义不变，只是没有首屏秒显的旧值。
            cache = null,
        )
        AppRoute.YellowPage -> YellowPageScreen(
            api = remember { CampusYellowPageApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
        AppRoute.Notification -> NotificationScreen(
            source = remember { CampusNoticeApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
            onNavigate = { onNavigate(DesktopTarget.App(it)) },
        )
        AppRoute.Faculty -> FacultyScreen(
            source = remember { CampusFacultyApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
            onOpenUrl = { openInBrowser(it) },
        )
        AppRoute.SchoolCourse -> SchoolCourseScreen(
            source = remember { CampusSchoolCourseApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
        )
        AppRoute.Inbox -> InboxScreen(
            source = remember { CampusInboxApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
            onOpen = { id ->
                when (val r = appRouteOf(id)) {
                    is AppRoute.Browser -> openInBrowser(r.url)
                    null -> Unit
                    else -> onNavigate(DesktopTarget.App(r))
                }
            },
        )
        AppRoute.EmptyRoom -> {
            // 「CDN 查询说明读没读过」与 :app 同一个键名（jvm 侧是内存实现，重启后回到初值）
            val prefs = remember { keyValueStore("empty_room") }
            EmptyRoomScreen(
                source = remember { CampusEmptyRoomApi(client, SCAFFOLD_API_BASE) },
                accountType = null,
                onBack = back,
                showCdnTip = !prefs.getBoolean("empty_room_cdn_tip", false),
                onCdnTipRead = { prefs.putBoolean("empty_room_cdn_tip", true) },
            )
        }
        AppRoute.CampusCard -> CampusCardScreen(
            source = remember { CampusCardNetApi(client, SCAFFOLD_API_BASE) },
            onBack = back,
        )
        AppRoute.Venue -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            VenueScreen(
                source = remember { CampusVenueApi(client, SCAFFOLD_API_BASE) },
                onBack = back,
                onOpenBrowser = { url, _ -> if (url.isNotBlank()) openInBrowser(url) },
                showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("venue_hint_shown", true) },
            )
        }
        AppRoute.Library -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LibraryScreen(
                source = remember { CampusLibraryApi(client, SCAFFOLD_API_BASE) },
                onBack = back,
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
        // 评教：屏与 ViewModel 在 :core，取数换成 campus-api 的只读端（`canSubmit = false`，
        // 所以屏上不出现「一键全部好评」与撤回按钮）。这里与 :web 的 WebJudgeScreen 同一个做法。
        AppRoute.Judge -> DesktopJudgeScreen(onBack = back, client = client)
        // 其余路由**如实说「没搬过来」**，不拿校历冒充（深链/误点看着像 bug，也分不清「没实现」与「坏了」）
        else -> NotPortedScreen(route, onNavigate)
    }
}

/** 全部页面：把 [DESKTOP_SUPPORTED_ROUTES] 列出来。阶段 0 的进度表兼导航。 */
@Composable
internal fun RoutesPage(client: HttpClient, onNavigate: (DesktopTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    var status by remember { mutableStateOf("正在探本机 campus-api…") }
    LaunchedEffect(client) { status = scaffoldStatusText(client) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("桌面端（阶段 0）", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "下面这些屏全部来自 :core —— 与 Android、Web 是同一份源码。取数走本机 campus-api（脚手架）。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(status, color = cs.primary, fontSize = 12.sp)
        DESKTOP_SUPPORTED_ROUTES.forEach { (route, label) ->
            TextButton(
                text = "$label（${route.id}）",
                onClick = { onNavigate(DesktopTarget.App(route)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
            if (route.loginType != null) "它需要 ${route.loginType} 的站点会话 —— 桌面端要自己登录，那部分在 Stage A。"
            else "它的屏与取数还在 :app（Android 专属依赖那一批），没进 :core / :data。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            "搬一屏 = 屏 + 模型 + 取数端口进 :core（或 :data），三端各自注入取数。" +
                "已经搬过来的可以在底栏或「全部」里直接看 —— 这一页只负责不假装。",
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
