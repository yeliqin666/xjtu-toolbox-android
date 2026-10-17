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

/** Web 这一端接共享壳的那一层。 */
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

/** 这一端「当前在哪一页」的写法。 */
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

/** 壳 → 这一端；null = 壳里有、这一端还没有的那一档。 */
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

/** Web 那一份 tab 集合（五格与 `:app` 的 `BottomTab` 一致）。 */
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
