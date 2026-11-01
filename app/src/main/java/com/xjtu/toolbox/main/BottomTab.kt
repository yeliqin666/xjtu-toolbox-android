package com.xjtu.toolbox.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.home.BottomBarTabs
import com.xjtu.toolbox.home.HomeIcons
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabs
import com.xjtu.toolbox.shell.ShellTarget

/**
 * 主界面原生的五格（页面专为挂在底栏上写，顶栏由主界面画）。[name] 已存进设置和启动意图，不能改。
 *
 * [iconSize] 是按墨迹面积拉平的光学尺寸（同为 24dp 的 Material 图标视觉大小差很多），
 * 只在玻璃底栏生效，miuix 自带的几种导航栏把图标尺寸写死了。
 * [route] 是这一格不在底栏上时压栈打开的那一页；首页和我的恒在底栏上，没有。
 */
enum class BottomTab(
    val label: String,
    val icon: ImageVector,
    val iconSize: Dp,
    val route: AppRoute?,
) {
    HOME("首页", Icons.Filled.Home, 24.dp, null),
    COURSES("日程", Icons.Filled.CalendarMonth, 23.dp, AppRoute.Schedule),

    /** 小岱，画成机器人（PidaiNavButton），[icon] 用不上。 */
    PIDAI("小岱", Icons.Filled.SmartToy, 27.dp, AppRoute.Agent),
    TOOLS("学辅", Icons.AutoMirrored.Filled.MenuBook, 22.dp, AppRoute.Zyxf),
    PROFILE("我的", Icons.Filled.Person, 27.dp, null);

    companion object {
        fun of(name: String?): BottomTab? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 底栏上的一格：原生五格之一（[core]），或用户放上来的一个功能页（[route]，原本是压栈的子页，
 * 自带顶栏，由 MainScreen 的 RouteTabPage 挂上去）。[id] 见 [BottomBarTabs.tabId]。
 */
@Immutable
data class BarTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val iconSize: Dp,
    val core: BottomTab?,
    val route: AppRoute?,
) {
    val isRouteTab: Boolean get() = core == null

    fun shellTab(): ShellTab = ShellTab(
        id = id,
        label = label,
        icon = icon,
        iconSize = iconSize,
        target = when (core) {
            BottomTab.HOME -> ShellTarget.App(AppRoute.Main)
            BottomTab.TOOLS -> ShellTarget.Custom("zyxf")
            BottomTab.PROFILE -> ShellTarget.Custom("profile")
            else -> ShellTarget.App(route!!)
        },
        showsAccountBadge = core == BottomTab.PROFILE,
        drawsOwnTopBar = isRouteTab,
    )

    companion object {
        fun of(tab: BottomTab) = BarTab(tab.name, tab.label, tab.icon, tab.iconSize, tab, tab.route)

        /** 格子 id → 这一格；认不得返回 null。 */
        fun of(id: String): BarTab? {
            BottomTab.of(id)?.let { return of(it) }
            val route = appRouteOf(id) ?: return null
            return BarTab(id, BottomBarTabs.label(route), HomeIcons.of(route), 24.dp, null, route)
        }

        /** 设置里的格子 id → 底栏，认不得的跳过。 */
        fun barOf(ids: List<String>): List<BarTab> = ids.mapNotNull(::of).distinctBy { it.id }
    }
}

/** Android 那一份 tab 集合（顺序 = 底栏从左到右）。 */
fun androidShellTabs(bar: List<BarTab>): ShellTabs = ShellTabs(bar.map { it.shellTab() })

/** 小岱那一格（共享壳只按 id 认；自绘由 `AndroidShellTabArt` 接）。 */
val PIDAI_TAB: ShellTab = BarTab.of(BottomTab.PIDAI).shellTab()
