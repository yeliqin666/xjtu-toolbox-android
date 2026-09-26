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
 * 底部导航项。[name] 写进了设置（默认启动 tab）和启动意图（[com.xjtu.toolbox.MainActivity.EXTRA_LAUNCH_TAB]），不能改。
 *
 * [icon] 是实心图标，选中与未选中**共用同一枚**，两者的差别只在颜色（见 MainScreen 的
 * navItemColors：选中主题色、未选中容器字色）。
 *
 * [iconSize] 是**光学尺寸**。Material 的字形都画在 24dp 网格里，但墨迹覆盖差得很多——
 * 底栏上按 24dp 实测：日程 18.0×20.1dp、学辅 22.2×16.9dp、首页 19.0×16.9dp、
 * 我的 15.9×15.9dp，于是"日历显大、小人显小"。这里按墨迹面积拉平给各自尺寸（再按眼睛
 * 微调）；只在玻璃底栏生效——miuix 的经典/悬浮胶囊/宽屏侧栏把图标尺寸写死在库里
 * （26dp / 28dp / rail 自己的），不吃外面的 modifier。
 */
enum class BottomTab(
    val label: String,
    val icon: ImageVector,
    val iconSize: Dp,
) {
    HOME("首页", Icons.Filled.Home, 24.dp),
    COURSES("日程", Icons.Filled.CalendarMonth, 23.dp),

    /**
     * 屁岱。它是**正经的 0 级页**，不是 push 出来的子页——底栏常驻、有自己的返回语义，
     * 和其他四个 tab 完全对等。
     *
     * 但它在底栏里不走 NavigationBarItem：渲染成一颗会动的机器人（见 PidaiNavButton，
     * 直径由那边给），所以 [icon] 和 [iconSize] 对它其实用不上，仅为满足枚举形状。
     * 位置固定在正中，前后各两个标签——这是它区别于其他 tab 的全部理由。
     */
    PIDAI("屁岱", Icons.Filled.SmartToy, 27.dp),
    TOOLS("学辅", Icons.AutoMirrored.Filled.MenuBook, 22.dp),
    PROFILE("我的", Icons.Filled.Person, 27.dp),
}

/** 悬浮底栏胶囊本体的最小高度，对齐 miuix FloatingNavigationBar 的 defaultMinSize。 */
internal val FLOATING_BAR_HEIGHT = 52.dp

/** 手机竖屏玻璃底栏本体的高度。GlassBottomTabs 的胶囊与 MainScreen 的浮空占位都取它。 */
internal val GLASS_BAR_HEIGHT = 58.dp
