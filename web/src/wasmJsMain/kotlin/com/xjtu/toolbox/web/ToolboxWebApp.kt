package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.community.GithubSession
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.core.net.CampusFitnessApi
import com.xjtu.toolbox.core.net.CampusGradesApi
import com.xjtu.toolbox.core.net.CampusSchoolCalendarApi
import com.xjtu.toolbox.core.net.CampusYellowPageApi
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.legal.EulaScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import io.ktor.client.HttpClient
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem

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
private fun AppPage(route: AppRoute, client: HttpClient, session: GithubSession, onNavigate: (WebTarget) -> Unit) {
    val back = { onNavigate(WebTarget.App(AppRoute.Schedule)) }
    when (route) {
        AppRoute.Schedule -> ScheduleScreen()
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
        AppRoute.Game2048 -> Gpa2048Screen(onBack = back)
        AppRoute.GameBlocks -> BlocksScreen(onBack = back)
        AppRoute.Community -> CommunityScreen(
            session = session,
            deviceAuth = WebGithubDeviceAuth,
            onBack = back,
            onOpenLegacyFeedback = {},
        )
        else -> ScheduleScreen()
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
