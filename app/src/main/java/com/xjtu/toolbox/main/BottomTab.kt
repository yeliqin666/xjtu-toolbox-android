package com.xjtu.toolbox.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabs
import com.xjtu.toolbox.shell.ShellTarget

/**
 * 底部导航项。[name] 已存进设置和启动意图，不能改。
 *
 * [iconSize] 是按墨迹面积拉平的光学尺寸（同为 24dp 的 Material 图标视觉大小差很多），
 * 只在玻璃底栏生效，miuix 自带的几种导航栏把图标尺寸写死了。
 *
 * ## 与共享壳的关系
 *
 * 这个枚举是 **Android 这一端的 tab 集合**（哪五格、叫什么、什么图标），底栏怎么画、当前页怎么摆
 * 都在 `:core` 的 [com.xjtu.toolbox.shell.AppShell] 里 —— 见 [shellTab] / [shellTabs]。
 * `name` 就是共享壳里的 [ShellTab.id]（存盘与启动意图都按它认，改一个字老用户的默认 tab 就失效）。
 */
enum class BottomTab(
    val label: String,
    val icon: ImageVector,
    val iconSize: Dp,
) {
    HOME("首页", Icons.Filled.Home, 24.dp),
    COURSES("日程", Icons.Filled.CalendarMonth, 23.dp),

    /** 屁岱，固定在正中，画成机器人（PidaiNavButton），[icon] 用不上。 */
    PIDAI("屁岱", Icons.Filled.SmartToy, 27.dp),
    TOOLS("学辅", Icons.AutoMirrored.Filled.MenuBook, 22.dp),
    PROFILE("我的", Icons.Filled.Person, 27.dp),
}

/**
 * 把这一格交给共享壳。
 *
 * 三个能对上路由的 tab 指向它对应的 [AppRoute]（`AppRouter.go` 把这三条路由落成切 tab，见那一段）；
 * 「学辅」「我的」这两格是这一端自己的页（不是共享路由表里的一条），走 [ShellTarget.Custom]。
 * 「我的」那一格带账号角标（多账号显示数字，未登录一个红点）。
 */
fun BottomTab.shellTab(): ShellTab = ShellTab(
    id = name,
    label = label,
    icon = icon,
    iconSize = iconSize,
    // 判据里要给枚举常量带类名：这个 `when` 在枚举类体**外**（枚举项不会自动进作用域）
    target = when (this) {
        BottomTab.HOME -> ShellTarget.App(AppRoute.Main)
        BottomTab.COURSES -> ShellTarget.App(AppRoute.Schedule)
        BottomTab.PIDAI -> ShellTarget.App(AppRoute.Agent)
        BottomTab.TOOLS -> ShellTarget.Custom("zyxf")
        BottomTab.PROFILE -> ShellTarget.Custom("profile")
    },
    showsAccountBadge = this == BottomTab.PROFILE,
)

/** Android 那一份 tab 集合（顺序 = 底栏从左到右 = [BottomTab.entries]）。 */
fun androidShellTabs(): ShellTabs = ANDROID_SHELL_TABS

/** 见 [androidShellTabs]。 */
val ANDROID_SHELL_TABS: ShellTabs = ShellTabs(BottomTab.entries.map { it.shellTab() })

/** 屁岱那一格（共享壳只按 id 认；自绘由 `AndroidShellTabArt` 接）。 */
val PIDAI_TAB: ShellTab = ANDROID_SHELL_TABS.byId(BottomTab.PIDAI.name)!!
