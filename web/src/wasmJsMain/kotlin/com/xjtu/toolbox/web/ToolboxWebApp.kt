package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.community.GithubSession
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.core.net.CampusInboxApi
import com.xjtu.toolbox.core.net.CampusEmptyRoomApi
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.core.net.CampusFitnessApi
import com.xjtu.toolbox.core.net.CampusSchoolCourseApi
import com.xjtu.toolbox.core.net.CampusFacultyApi
import com.xjtu.toolbox.core.net.CampusNoticeApi
import com.xjtu.toolbox.core.net.CampusGradesApi
import com.xjtu.toolbox.core.net.CampusSchoolCalendarApi
import com.xjtu.toolbox.core.net.CampusYellowPageApi
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.hop.HopScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.legal.EulaScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.inbox.InboxScreen
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import io.ktor.client.HttpClient
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Web 外壳：**导航与页面都改成 `:core` 的**，不再是自制的两格外壳。
 *
 * ## 与上一版（`var tab: Int` + 手写 Row/Text 底栏）的差别
 *
 * | 项 | 上一版 | 现在 |
 * |---|---|---|
 * | 当前页 | 局部 `Int` 下标 | [AppRoute]（与 App **同一张 42 条路由表**），`?route=<id>` 深链走 [appRouteOf] |
 * | 底栏 | 手写 `Row` + `Text` | `:core` 依赖里的 MIUIX `NavigationBar`/`NavigationBarItem`（App 的「经典底栏」用的是同一个组件） |
 * | 页面 | 2 个自写屏 | `:core` 的真屏：课表 / 黄页 / GPA2048 / 方块 / 社区（+ EULA 与自检两个 Web 专有页） |
 * | 主题 | 裸 `MiuixTheme` | `:core` 的 `XJTUToolBoxTheme`（与 App 同一个包裹，含深浅色覆盖与系统栏切口） |
 *
 * `?route=` 认两种东西：`:core` 路由表里的 id（`yellow_page`、`game_2048`…）→ 对应的共享屏；
 * 以及两个 **Web 专有**的伪路由（`probe` 后端自检、`eula` 协议页）——它们不是 App 的页面，
 * 所以不进 `AppRoute`，但排在一起方便排障。
 */
@Composable
fun ToolboxWebApp() {
    val client = remember { toolboxWebClient() }
    val session = remember { WebGithubSession(client) }
    val initial = remember { initialWebTarget() }
    var target: WebTarget by remember { mutableStateOf(initial) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (val t = target) {
                is WebTarget.App -> AppPage(t.route, client, session) { target = it }
                WebTarget.Probe -> ProbeScreen()
                WebTarget.Eula -> EulaScreen(onAccept = { target = WebTarget.Probe })
            }
        }
        WebBottomBar(selected = target) { target = it }
    }
}

/** 当前页：`:core` 的路由，或两个 Web 专有页。 */
internal sealed interface WebTarget {
    data class App(val route: AppRoute) : WebTarget
    data object Probe : WebTarget
    data object Eula : WebTarget
}

/** 底栏的五格 —— 每一格都是 `:core` 里真实存在的屏。 */
internal data class WebTab(val route: AppRoute, val label: String, val icon: ImageVector)

internal val WEB_TABS = listOf(
    WebTab(AppRoute.Schedule, "课表", Icons.Filled.CalendarMonth),
    WebTab(AppRoute.SchoolCalendar, "校历", Icons.Filled.EventNote),
    WebTab(AppRoute.Fitness, "体测", Icons.AutoMirrored.Filled.DirectionsRun),
    WebTab(AppRoute.ScoreReport, "成绩", Icons.Filled.Grade),
    WebTab(AppRoute.YellowPage, "黄页", Icons.Filled.Phone),
    WebTab(AppRoute.Game2048, "GPA2048", Icons.Filled.Psychology),
    WebTab(AppRoute.GameBlocks, "方块", Icons.Filled.Extension),
    WebTab(AppRoute.Games, "游戏", Icons.Filled.SportsEsports),
    WebTab(AppRoute.Community, "社区", Icons.Filled.Forum),
)

@Composable
private fun WebBottomBar(selected: WebTarget, onSelect: (WebTarget) -> Unit) {
    // 与 App 的「经典底栏」同一个组件、同一个 mode；只换 tab 集合（Web 只有这五件事能做）
    NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
        WEB_TABS.forEach { tab ->
            NavigationBarItem(
                selected = (selected as? WebTarget.App)?.route == tab.route,
                onClick = { onSelect(WebTarget.App(tab.route)) },
                icon = tab.icon,
                label = tab.label,
            )
        }
    }
}

/**
 * 一屏共享页。每个屏只注入**这一端能提供的东西**：
 * - 黄页：campus-api 版的 [CampusYellowPageApi]（Android 那边直连学校，Web 只能走同源反代）；
 * - 校历/体测：campus-api 版的 [CampusSchoolCalendarApi] / [CampusFitnessApi]（同一条理由：学校域名不给 CORS 头）；
 * - 社区：登录态与设备码登录在 Web 上如实报「未配置」（见 [WebGithubSession]）；
 * - 错误文案统一用 `:core` 的 [FriendlyError] —— 与 App 字句相同。
 */
@Composable
private fun AppPage(route: AppRoute, client: HttpClient, session: WebGithubSession, onNavigate: (WebTarget) -> Unit) {
    val back = { onNavigate(WebTarget.App(AppRoute.Schedule)) }
    when (route) {
        AppRoute.Schedule -> ScheduleScreen()
        // 内置浏览器：**Web 端的浏览器就是浏览器本身** —— 把 URL 交给它，同标签导航过去。
        //
        // :app 的 BrowserScreen 是个 WebView，它比普通浏览器多两件事：① 复用 App 已经登好的
        // 站点会话（WebVPN / 校园卡那类），② `then=` 参数在登录完成后自动跳下一站（付款页先过登录）。
        // 这两件在浏览器里都做不到（会话在 campus-api 的进程里，不在页面上），所以这里**只做
        // 浏览器做得到的那件**：导航到那个网址，要登录就由站点自己的登录页处理。
        // 这不算「许诺一个做不到的模式」—— 它本来就是浏览器打开一个网址。
        //
        // 同标签（navigateSameTab）而不是新标签：深链进页时没有用户手势，`window.open` 会被
        // 弹窗拦截器拦下、用户看到「什么都没发生」。回来靠浏览器后退键。
        //
        // ⚠️ 必须写 `is`：`AppRoute.Browser` 的两个参数都有默认值，裸写它会被当成
        // 「构造一个 Browser("", "") 再比相等」，既不匹配真实深链也不会智能转换类型。
        is AppRoute.Browser -> {
            LaunchedEffect(route.url) {
                if (route.url.isNotBlank()) navigateSameTab(route.url) else back()
            }
        }
        // 校历：与 Android 同一个屏、同一份模型与算法（:core/calendar），只换取数——
        // 浏览器不能直连 workflow.xjtu.edu.cn（无 CORS 头），走 campus-api 同源反代。
        AppRoute.SchoolCalendar -> SchoolCalendarScreen(
            source = remember { CampusSchoolCalendarApi(client) },
            onBack = back,
        )
        // 体测：与 Android 同一个屏、同一套模型与分项口径（:core/fitness），只换取数——
        // campus-api 按隐私口径不返回姓名/学号，所以英雄卡标题会落到兜底文案（已写在 FitnessSource 的 KDoc）。
        AppRoute.Fitness -> FitnessScreen(
            source = remember { CampusFitnessApi(client) },
            onBack = back,
        )
        // 成绩：与 Android 同一个屏与模型（:core/score），取数换成 campus-api 的精确成绩。
        // 两个端上游不是同一个接口（:app 解析帆软报表 HTML），字段对齐写在 CampusGradesApi 的 KDoc 里。
        AppRoute.ScoreReport -> ScoreReportScreen(
            source = remember { CampusGradesApi(client) },
            onBack = back,
            cache = remember { WebScoreReportCache() },
        )
        AppRoute.YellowPage -> YellowPageScreen(
            api = remember { CampusYellowPageApi(client) },
            onBack = back,
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
        // 游戏合集：与 Android 同一个屏（:core/game/GamesScreen），战绩读同一份 GameStore
        // （Web 走 localStorage，Android 走 SharedPreferences）。合集里没搬过来的那几条
        // （合成西交大 = WebView）点进去会落到 NotPortedScreen —— 不冒充。
        AppRoute.Games -> GamesScreen(
            onBack = back,
            onNavigate = { onNavigate(WebTarget.App(it)) },
            // 浏览器没有 BLE ⇒ 棋类那几行不显示「联机」标签，也不许诺一个点了会失败的模式。
            supportsOnline = false,
        )
        AppRoute.Game2048 -> Gpa2048Screen(onBack = back)
        AppRoute.GameBlocks -> BlocksScreen(onBack = back)
        // 五子棋：与 Android 同一个屏（:core/game/gomoku）。联机那一格不出现 ——
        // 浏览器没有 BLE，onlineLobby 传 null（不是做一个假大厅）。
        AppRoute.GameGomoku -> GomokuScreen(onBack = back)
        // 围棋：与 Android 同一个屏（:core/game/go）。同样没有联机那一格（无 BLE）。
        AppRoute.GameGo -> GoScreen(onBack = back)
        // 象棋：与 Android 同一个屏与引擎（:core/game/xiangqi）。规则引擎原来那 6 个
        // `.java` 翻成了 Kotlin（Java 编不了 commonMain），行为由原来那 3 个单测钉住。
        // 同样没有联机那一格（浏览器没有 BLE ⇒ onlineLobby 传 null）。
        AppRoute.GameXiangqi -> XiangqiScreen(onBack = back)
        // 跳一跳：与 Android 同一个屏（:core/game/hop）。9 张地标图按端注入：Android 用
        // R.drawable、Web 用 :web 的 composeResources（同一份 webp 字节，见 WebHopLandmarks.kt）。
        AppRoute.GameHop -> HopScreen(
            onBack = back,
            landmarkImages = rememberWebHopLandmarkImages(),
        )
        // 通知公告：与 Android 同一个屏与模型（:core/notification），取数换成 campus-api ——
        // 它覆盖同样 29 个源，两端拿到的是同一批通知（差别只在「谁去爬」）。
        AppRoute.Notification -> NotificationScreen(
            source = remember { CampusNoticeApi(client) },
            onBack = back,
            onNavigate = { onNavigate(WebTarget.App(it)) },
        )
        // 教师主页检索：与 Android 同一个屏与模型（:core/faculty），取数换成 campus-api 的
        // `/api/info/faculty`（免登录、同一个上游 advancesearch.jsp）。三处刻意降级写在
        // CampusFacultyApi 的 KDoc 里：筛选项表拿不到、主页不解析（改为新标签打开）、联系方式不取。
        AppRoute.Faculty -> FacultyScreen(
            source = remember { CampusFacultyApi(client) },
            onBack = back,
            onOpenUrl = { openInNewTab(it) },
        )
        // 全校课表：与 Android 同一个屏与模型（:core/schedule）。campus-api 的投影**少一批字段**
        // （人数/学时、YPSJDD、开课单位与公选筛选），逐条写在 CampusSchoolCourseApi 的 KDoc 里；
        // 屏据能力开关把筛不了的那两档控件整个隐藏，人数/学时那几块不画 —— 不拿 0 冒充。
        AppRoute.SchoolCourse -> SchoolCourseScreen(
            source = remember { CampusSchoolCourseApi(client) },
            onBack = back,
        )
        // 评教：与 Android 同一份屏与 ViewModel（:core/judge），取数换成 campus-api。
        // **只读** —— campus-api 永不实现提交/撤销评教，所以屏上不出现「一键全部好评」
        // 与撤回按钮（JudgeSource.canSubmit=false）；能看「哪些课还没评」。
        AppRoute.Judge -> WebJudgeScreen(onBack = back)
        // 消息收纳：与 Android 同一个屏与 store/rules（:core/inbox），取数换成 campus-api 的
        // `/api/inbox`（同样四路）。点击行为：外链（`browser?url=`）新标签打开 —— 与 :app 的
        // 内置浏览器同义；其余按 AppRoute 走站内导航。
        AppRoute.Inbox -> InboxScreen(
            source = remember { CampusInboxApi(client) },
            onBack = back,
            onOpen = { id ->
                when (val r = appRouteOf(id)) {
                    is AppRoute.Browser -> openInNewTab(r.url)
                    null -> Unit
                    else -> onNavigate(WebTarget.App(r))
                }
            },
        )
        // 空闲教室：与 Android 同一个屏与 ViewModel（:core/emptyroom），只换取数 —— campus-api 只有
        // CDN 那一档（`/api/emptyroom/cdn` 一次给全校区当天全部教室），所以 CampusEmptyRoomApi 的
        // availableSources 只报 CDN，屏上「实时状态」「直查教务」两项整个不出现（见那个类的 KDoc）。
        // Web 没有身份这回事 ⇒ accountType = null（研究生那条限制本来也走不到）。
        // 「CDN 查询说明读没读过」在 Web 存 localStorage（:app 存 CredentialStore），键名与 :app 同一个。
        AppRoute.EmptyRoom -> {
            val prefs = remember { keyValueStore("empty_room") }
            EmptyRoomScreen(
                source = remember { CampusEmptyRoomApi(client) },
                accountType = null,
                onBack = back,
                showCdnTip = !prefs.getBoolean("empty_room_cdn_tip", false),
                onCdnTipRead = { prefs.putBoolean("empty_room_cdn_tip", true) },
            )
        }
        AppRoute.Community -> CommunityScreen(
            session = session,
            // Web 端只有「粘贴 token」这一条登录路（设备码流程在浏览器里拿不到 device code）；
            // 登录页本身是共享屏，输入框由 supportsManualToken 长出来，:app 那边不会出现。
            deviceAuth = remember(session) { WebGithubDeviceAuth(session) },
            onBack = back,
            onOpenLegacyFeedback = {},
        )
        // 其它路线的屏还在 :app：**如实说「没搬过来」，不拿课表冒充**（以前这里落回 ScheduleScreen，
        // 深链 `?route=notification` 会静默显示课表 —— 看着像 bug，也让人分不清「没实现」与「坏了」）。
        else -> NotPortedScreen(route, onNavigate)
    }
}

/**
 * “这一屏还没搬到 Web”的占位页 —— 只给 `:core` 里尚未存在的 AppRoute 用。
 *
 * 为什么要有它：Web 只能渲染 `:core/commonMain` 里的屏；`:app` 那 40 条路由里还有一大批
 * （通知 / 全校课表 / 教师检索 / 图书馆 / 校园卡 / 成绩单 / 我的 …）因为取数挂着
 * okhttp、Context、Room、BLE 而暂时搬不过来。这页把**具体哪一条没搬**、**它需不需要登录**
 * 直接写在脸上，而不是落回课表假装能用 —— 交接文档里那条「只信工作区」的验收，看的就是这个。
 */
@Composable
private fun NotPortedScreen(route: AppRoute, onNavigate: (WebTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text("这一屏还没搬到 Web", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("AppRoute.${route::class.simpleName ?: "?"}", color = cs.primary, fontSize = 13.sp)
        Text("路由 id：${route.id}", color = cs.onBackgroundVariant, fontSize = 12.sp)
        Text(
            if (route.loginType != null) "它还需要 ${route.loginType} 的站点会话（浏览器没有 CAS 会话）。"
            else "它的取数还在 :app（okhttp / Context / Room / BLE 那一批）。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            "Web 只渲染 :core/commonMain 里的屏：搬一屏 = 屏 + 模型 + 取数端口进 :core，两端各自注入取数。" +
                "已搬过来的可以在下面底栏或 ?route= 里直接看。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        TextButton(text = "回课表", onClick = { onNavigate(WebTarget.App(AppRoute.Schedule)) }, minWidth = 96.dp)
    }
}

/**
 * `?route=<id>`：先认 Web 专有的两个（`probe` / `eula`），再交给 `:core` 的 [appRouteOf]；
 * 认不出（旧深链、手写错）一律落回课表 —— 与 App 的 `appRouteOf` 返回 null 时同一套兜底思路。
 */
internal fun initialWebTarget(): WebTarget = when (val raw = browserRouteParam()) {
    null -> WebTarget.App(AppRoute.Schedule)
    "probe" -> WebTarget.Probe
    "eula" -> WebTarget.Eula
    else -> WebTarget.App(appRouteOf(raw) ?: AppRoute.Schedule)
}
