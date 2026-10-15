package com.xjtu.toolbox.web

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabs
import com.xjtu.toolbox.shell.ShellTarget

/**
 * Web 这一端接共享壳的那一层 —— 与桌面那份（`desktop/DesktopShell.kt`）同一分工：
 * **tab 集合、当前页的两种写法、两者之间的换算**，没有别的东西。
 *
 * ## 五格从哪来：逐字照 `:app` 的 `BottomTab`
 *
 * [webTabs] 的五格（顺序 / 文字 / 图标 / [ShellTab.iconSize]）与
 * `app/src/main/java/com/xjtu/toolbox/main/BottomTab.kt` **逐字相同** —— 顺序与文字是
 * 「首页 / 日程 / 屁岱 / 学辅 / 我的」，图标依次 `Home` / `CalendarMonth` / `SmartToy` /
 * `AutoMirrored.MenuBook` / `Person`，光学尺寸 24 / 23 / 27 / 22 / 27dp，id 也用那个枚举的名字
 * （`HOME` / `COURSES` / …：`:app` 把 id 存进设置与启动意图，web 这边照抄一份才不会出现
 * 「同一个格子两端两个名字」）。
 *
 * 这一条是本阶段（阶段 C）的**验收点**：web 不再自定一套 tab（搬迁前是「课表 / 校历 / 体测 /
 * 成绩 / 黄页 / GPA2048 / 方块 / 游戏 / 社区」九格，全是能做的功能凑出来的），而是与 App 同五格；
 * **做不到的那几格落一张如实降级页，不把格子藏起来** —— 藏起来就又变成"另一套层级"了。
 *
 * 哪一格真能做：见 `ToolboxWebApp` 的 `page` 分支（首页 = 共享 `HomeScreen` + web `WebHomeHost`，
 * 日程 = 共享 `ScheduleScreen` + web `ScheduleHost`，
 * 我的 = 共享 `SettingsScreen` + web `SettingsHost`，
 * 屁岱 = 共享 `AgentScreen` + web `WebAgentSession` / `WebAgentHost` / `WebAgentScreen`），
 * 壳里认不出的自定义目标见 `WebFallbackScreens.kt`。
 *
 * ## 「会话」那一屏不进 tab 列表（壳级 [ShellTarget.Custom] 入口）
 *
 * serve 模式的会话屏（登录 / 短信二验 / 登出，`WebServeSessionScreen`）是 **Web 专有**的一屏：
 * campus-api 那套没有「页面上登录」这回事（凭据托管在反代进程里）。它因此不进五格 —— 进任何一格
 * 都会让「五格与 `:app` 逐字一致」当场失效。它走壳级 `Custom` 入口：
 *
 * - `?route=serve` 深链（搬迁前就在，语义一行未改）；
 * - **过不了闸门自动落过去**：serve 模式下 `GET /api/session` 失败（没令牌 / 令牌不对）时，
 *   外壳把当前页设成 [WebTarget.Serve] —— 就是搬迁前那一段行为，位置与判据都没动；
 * - 「学辅」那一页（[WebServicesScreen]）上有一个入口行：serve 模式下点它进会话屏，
 *   于是「已登录、想登出」也有路可走。
 */
internal object WebRoutes {

    /** 壳级伪路由：`?route=probe` 后端自检页。 */
    const val PROBE = "probe"

    /** 壳级伪路由：`?route=eula` 用户协议页。 */
    const val EULA = "eula"

    /** 壳级伪路由：`?route=serve` 会话屏（见文件头）。 */
    const val SERVE = "serve"

    /** 「学辅」那一格：web 这一端自己的「本端有什么」页（见 [WebServicesScreen]）。 */
    const val SERVICES = "zyxf"

    /** 「我的」那一格：共享的设置屏（web 没有账号这回事，见 `ToolboxWebApp` 的 `page`）。 */
    const val PROFILE = "profile"
}

/**
 * 这一端「当前在哪一页」的写法 —— 与桌面那份 `DesktopTarget` 同型：它是 [ShellTarget] 的子集
 * （[toShell] 全函数），不是第二套导航模型。
 *
 * 留着它是因为屏与外壳之间的 `onNavigate` 一直用它（`AppPage` 的 `WebTarget.App`、
 * `?route=` 的初值、serve 落会话屏那一段），删掉要改十几处调用点却换不来任何东西 ——
 * 换算就摆在这儿，两行看得懂。
 */
internal sealed interface WebTarget {

    /** `:core` 路由表里的一页。 */
    data class App(val route: AppRoute) : WebTarget

    /** 后端自检（Web 专有）。 */
    data object Probe : WebTarget

    /** 用户协议（Web 专有）。 */
    data object Eula : WebTarget

    /** serve 模式的会话屏（Web 专有，见文件头）。 */
    data object Serve : WebTarget

    /** 「学辅」那一格：本端自己的**可点目录**（同一份服务目录里本端真能点开的那些）。 */
    data object Services : WebTarget

    /** 「我的」那一格：共享设置屏（Web 专有目标，屏是 `:core` 的）。 */
    data object Profile : WebTarget
}

/** 这一端 → 壳（全函数：每一档壳里都有对应的一档）。 */
internal fun WebTarget.toShell(): ShellTarget = when (this) {
    is WebTarget.App -> ShellTarget.App(route)
    WebTarget.Probe -> ShellTarget.Custom(WebRoutes.PROBE)
    WebTarget.Eula -> ShellTarget.Custom(WebRoutes.EULA)
    WebTarget.Serve -> ShellTarget.Custom(WebRoutes.SERVE)
    WebTarget.Services -> ShellTarget.Custom(WebRoutes.SERVICES)
    WebTarget.Profile -> ShellTarget.Custom(WebRoutes.PROFILE)
}

/**
 * 壳 → 这一端；**null = 壳里有、这一端还没有的那一档**。
 *
 * [ShellTarget.AllPages] 如实返回 null：web 的 tab 集合里没有「全部」那一格
 *（`ShellTab.target` 那条 KDoc：Android 与 Web 都没有这一档），所以实际取不到它 ——
 * 与桌面那份同一条写法（映射不出来就不动，不假装能去）。
 */
internal fun ShellTarget.toWeb(): WebTarget? = when (this) {
    is ShellTarget.App -> WebTarget.App(route)
    ShellTarget.AllPages -> null
    is ShellTarget.Custom -> when (id) {
        WebRoutes.PROBE -> WebTarget.Probe
        WebRoutes.EULA -> WebTarget.Eula
        WebRoutes.SERVE -> WebTarget.Serve
        WebRoutes.SERVICES -> WebTarget.Services
        WebRoutes.PROFILE -> WebTarget.Profile
        else -> null
    }
}

/** Web 那一份 tab 集合（见文件头：五格与 `:app` 的 `BottomTab` 逐字一致）。 */
internal fun webTabs(): ShellTabs = WEB_SHELL_TABS

/** 见 [webTabs]。 */
internal val WEB_SHELL_TABS: ShellTabs = ShellTabs(
    listOf(
        ShellTab(
            id = "HOME",
            label = "首页",
            icon = Icons.Filled.Home,
            iconSize = 24.dp,
            target = ShellTarget.App(AppRoute.Main),
        ),
        // 唯一一格里 web 真接上了共享屏的（`:core` 的 ScheduleScreen + 本端 ScheduleHost）。
        ShellTab(
            id = "COURSES",
            label = "日程",
            icon = Icons.Filled.CalendarMonth,
            iconSize = 23.dp,
            target = ShellTarget.App(AppRoute.Schedule),
        ),
        ShellTab(
            id = "PIDAI",
            label = "屁岱",
            icon = Icons.Filled.SmartToy,
            iconSize = 27.dp,
            target = ShellTarget.App(AppRoute.Agent),
        ),
        ShellTab(
            id = "TOOLS",
            label = "学辅",
            icon = Icons.AutoMirrored.Filled.MenuBook,
            iconSize = 22.dp,
            target = ShellTarget.Custom(WebRoutes.SERVICES),
        ),
        // `:app` 这一格带账号角标（多账号显示数字、未登录红点）。web 没有账号这回事
        //（凭据在 campus-api / serve 进程里，页面上不持有），所以 `showsAccountBadge`
        // 照抄 `:app` 的 true 而 `ShellNavState.accountBadge` 传 null ⇒ 壳一个角标都不画
        //（`ShellNavState.badge` 的判据：内容为 null 就不画）—— 不是"藏起来"，是这一端真没有数字可写。
        ShellTab(
            id = "PROFILE",
            label = "我的",
            icon = Icons.Filled.Person,
            iconSize = 27.dp,
            target = ShellTarget.Custom(WebRoutes.PROFILE),
            showsAccountBadge = true,
        ),
    ),
)
