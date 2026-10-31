package com.xjtu.toolbox.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SportsEsports
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabs
import com.xjtu.toolbox.shell.ShellTarget

/** 桌面这一端接共享壳的那一层 —— 只有三样东西：tab 集合、当前页的两种写法、两者之间的换算。 */
internal fun desktopTabs(): ShellTabs = DESKTOP_SHELL_TABS

/** 见 [desktopTabs]。 */
internal val DESKTOP_SHELL_TABS: ShellTabs = ShellTabs(
    listOf(
        // 首页（`AppRoute.Main`）：与 `:app` 的第一格同位。屏是 `:core` 的 `HomeScreen`，
        // 这一端只提供宿主面（课表缓存 / 节假日 / 真能打开的路由表 / 公告 / 消息那两格）。
        ShellTab(
            id = AppRoute.Main.id,
            label = "首页",
            icon = Icons.Filled.Home,
            target = ShellTarget.App(AppRoute.Main),
        ),
        ShellTab(
            id = AppRoute.SchoolCalendar.id,
            label = "校历",
            icon = Icons.Filled.EventNote,
            target = ShellTarget.App(AppRoute.SchoolCalendar),
        ),
        ShellTab(
            id = AppRoute.Library.id,
            label = "图书馆",
            icon = Icons.Filled.CalendarMonth,
            target = ShellTarget.App(AppRoute.Library),
        ),
        ShellTab(
            id = AppRoute.YellowPage.id,
            label = "黄页",
            icon = Icons.Filled.Phone,
            target = ShellTarget.App(AppRoute.YellowPage),
        ),
        ShellTab(
            id = AppRoute.Games.id,
            label = "游戏",
            icon = Icons.Filled.SportsEsports,
            target = ShellTarget.App(AppRoute.Games),
        ),
        // 第六格：这一端真能打开的路由都列在那一页上（`RoutesPage`）。`AllPages` 是共享壳里
        // 「更多 / 全部」那一档 —— 它**不是一条路由**，所以不会与上面五格混起来。
        ShellTab(
            id = "all",
            label = "全部",
            icon = Icons.Filled.Apps,
            target = ShellTarget.AllPages,
        ),
    ),
)

/** 这一端「当前在哪一页」的两种写法。见文件头：它是 [ShellTarget] 的子集，不是另一套模型。 */
internal sealed interface DesktopTarget {
    data class App(val route: AppRoute) : DesktopTarget
    data object Routes : DesktopTarget
}

/** 这一端 → 壳（全函数：这两种目标壳里都有对应的一档）。 */
internal fun DesktopTarget.toShell(): ShellTarget = when (this) {
    is DesktopTarget.App -> ShellTarget.App(route)
    DesktopTarget.Routes -> ShellTarget.AllPages
}

/** 壳 → 这一端；null = 壳里有、这一端还没有的那一档（[ShellTarget.Custom]：端自己的页）。 */
internal fun ShellTarget.toDesktop(): DesktopTarget? = when (this) {
    is ShellTarget.App -> DesktopTarget.App(route)
    ShellTarget.AllPages -> DesktopTarget.Routes
    is ShellTarget.Custom -> null
}

/**
 * 规范化默认启动落点 → 这一端开场那一页（三张映射表之一：另两张是 `:app` 的 `bottomTabOf()`
 * 与 `:web` 的 `webStartTarget()`）。
 *
 * 桌面六格（首页 / 校历 / 图书馆 / 黄页 / 游戏 / 全部）与 `:app` 五格不同构，所以这张表不是一一对应，
 * 而是「差最近的那一页」—— 并如实写明差在哪里：
 *
 * - `home` → [AppRoute.Main]（就是第一格，一一对应）；
 * - `schedule` → [AppRoute.Schedule]（课表是一页，但**底栏里没有这一格** ⇒ 一格都不高亮，
 *   这是共享壳里就允许的状态，见 `ShellNavState.selected` 的 KDoc）；
 * - `agent` → [AppRoute.Agent]（同上：屁岱有页无格）；
 * - `services` → [DesktopTarget.Routes]（「全部」那一页列的是本端真能打开的路由，与「学辅」目录同位）；
 * - `profile` → [AppRoute.Settings]（桌面没有「我的」那格；与账号/外观相关的只有设置页）。
 *
 * 认不出（老文件、手工改过的值）退日程：与 `SettingsStore.TAB_KEY_DEFAULT` 同一档。
 */
internal fun desktopStartTarget(defaultTab: String): DesktopTarget =
    when (SettingsStore.normalizeDefaultTab(defaultTab)) {
        SettingsStore.TAB_KEY_HOME -> DesktopTarget.App(AppRoute.Main)
        SettingsStore.TAB_KEY_AGENT -> DesktopTarget.App(AppRoute.Agent)
        SettingsStore.TAB_KEY_SERVICES -> DesktopTarget.Routes
        SettingsStore.TAB_KEY_PROFILE -> DesktopTarget.App(AppRoute.Settings)
        else -> DesktopTarget.App(AppRoute.Schedule)
    }
