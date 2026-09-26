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

/**
 * 底部导航项。[name] 已存进设置和启动意图，不能改。
 *
 * [iconSize] 是按墨迹面积拉平的光学尺寸（同为 24dp 的 Material 图标视觉大小差很多），
 * 只在玻璃底栏生效，miuix 自带的几种导航栏把图标尺寸写死了。
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

/** 悬浮底栏胶囊本体的最小高度，对齐 miuix FloatingNavigationBar 的 defaultMinSize。 */
internal val FLOATING_BAR_HEIGHT = 52.dp

/** 手机竖屏玻璃底栏本体的高度。GlassBottomTabs 的胶囊与 MainScreen 的浮空占位都取它。 */
internal val GLASS_BAR_HEIGHT = 58.dp
