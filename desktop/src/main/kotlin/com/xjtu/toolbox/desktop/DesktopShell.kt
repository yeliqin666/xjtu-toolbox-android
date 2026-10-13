package com.xjtu.toolbox.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SportsEsports
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabs
import com.xjtu.toolbox.shell.ShellTarget

/**
 * 桌面这一端接共享壳的那一层 —— 只有三样东西：**tab 集合、当前页的两种写法、两者之间的换算**。
 *
 * ## 这一端没有第二套导航模型
 *
 * 壳里的当前页是 [ShellTarget]（三档：某一屏 / 「全部」那一页 / 端自己的页），这一端用的是
 * [DesktopTarget]（两档：某一屏 / 索引页）—— 后者不是另一套模型，而是**前者的子集**：
 * [toShell] 与 [toDesktop] 是这两个方向唯一的两条路（往返一致由 `DesktopShellJvmTest` 钉住）。
 * 底栏 / 侧栏 / 当前页那一层全在共享壳里（`AppShell`），这一端只交出「有哪六格」。
 *
 * 为什么 [DesktopTarget] 还留着：它是 `ToolboxDesktopApp(initialTarget = …)` 那一个参数与各屏
 * `onNavigate` 的写法（`Main.kt` / `DesktopHome` / `DesktopBrowser` / `RoutesPage` 与证据运行都在用），
 * 删掉它要改十几处调用点却换不来任何东西 —— 换算就摆在这儿，两行看得懂。
 *
 * ## 六格从哪来
 *
 * 与搬迁前那份 `DESKTOP_TABS` **逐字相同**（顺序 / 文字 / 图标一个没动），只是换成了共享壳的
 * [ShellTab]。id 用这条路由**自己的 id**（桌面没有把 tab 存盘的地方，写它只为一眼能对上路由；
 * 「存进设置与启动意图的就是 id」那条纪律留给真存盘的端，见 [ShellTab.id] 的 KDoc）。
 */
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

/**
 * 壳 → 这一端；**null = 壳里有、这一端还没有的那一档**（[ShellTarget.Custom]：端自己的页）。
 *
 * 这一端的目标只从 [toShell] 来，而 [desktopTabs] 里一格 Custom 都没有 ⇒ 实际取不到 null；
 * 换算仍如实返回它，由调用点自己决定怎么办（`ToolboxDesktopApp` 的 `onSelect` 是「映射不出来就不动」，
 * 不假装能去）。
 */
internal fun ShellTarget.toDesktop(): DesktopTarget? = when (this) {
    is ShellTarget.App -> DesktopTarget.App(route)
    ShellTarget.AllPages -> DesktopTarget.Routes
    is ShellTarget.Custom -> null
}
