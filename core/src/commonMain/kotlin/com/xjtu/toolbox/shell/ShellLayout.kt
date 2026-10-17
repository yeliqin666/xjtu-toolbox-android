package com.xjtu.toolbox.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.WindowSize
import com.xjtu.toolbox.ui.glass.GLASS_BAR_HEIGHT

/** 底栏画成哪一种 —— 屏形状 × 界面风格 的结论（纯枚举，没有 Compose 依赖，可单测）。 */
enum class ShellForm {

    /** miuix 经典底栏（`NavigationBar`）：界面风格选「经典」时，手机 / 平板竖屏用。 */
    CLASSIC_BAR,

    /** 玻璃胶囊底栏（[GlassBottomTabs][com.xjtu.toolbox.ui.glass.GlassBottomTabs]）：默认风格 + 手机竖屏。 */
    GLASS_BAR,

    /** miuix 悬浮胶囊（`FloatingNavigationBar`）：默认风格 + 平板竖屏（玻璃底栏只在手机上用）。 */
    FLOATING_PILL,

    /** 宽屏侧栏（`NavigationRail`）：屏幕够宽时，底栏换成侧栏。 */
    RAIL,
}

/** 悬浮底栏胶囊本体的最小高度，对齐 miuix FloatingNavigationBar 的 defaultMinSize。 */
val FLOATING_BAR_HEIGHT = 52.dp

/** floatingToolbar 槽位离屏幕底边有 miuix 私有的 4dp（FloatingToolbarSpacing），要补偿掉。 */
private val MIUIX_FLOATING_TOOLBAR_SPACING = 4.dp

/** 判据：屏形状决定底栏/侧栏，界面风格决定玻璃/经典，窗口宽度区分手机与平板竖屏。 */
fun shellForm(isWide: Boolean, windowSize: WindowSize, glass: Boolean): ShellForm = when {
    isWide -> ShellForm.RAIL
    glass && windowSize == WindowSize.Compact -> ShellForm.GLASS_BAR
    glass -> ShellForm.FLOATING_PILL
    else -> ShellForm.CLASSIC_BAR
}

/** 壳的形态学：形态 + 几个由端上量出来的数 —— 于是「底栏占多少、内容要让开多少」全部可单测。 */
class ShellLayout(
    val form: ShellForm,
    val glass: Boolean,
    val bottomInset: Dp,
) {

    /** 玻璃胶囊底栏在场（气泡要按它决定采样哪一层）。 */
    val glassBarPresent: Boolean get() = form == ShellForm.GLASS_BAR

    /** 手机 / 平板竖屏的底栏离导航条的空隙：有导航条 8dp，没有（手势条）20dp。 */
    private val glassBarBottomGap: Dp get() = if (bottomInset > 0.dp) 8.dp else 20.dp

    /** 玻璃底栏塞进 floatingToolbar 槽位时的底边距：本体以外的空隙 + 导航条，再扣掉 miuix 私有的 4dp。 */
    val glassBarSlotBottomPadding: Dp
        get() = (bottomInset + glassBarBottomGap - MIUIX_FLOATING_TOOLBAR_SPACING).coerceAtLeast(0.dp)

    /** 悬浮胶囊塞进槽位时的纵向下移量（照 miuix 的私有内边距补偿）。 */
    val bottomBarSlotOffsetY: Dp
        get() = if (form == ShellForm.FLOATING_PILL) MIUIX_FLOATING_TOOLBAR_SPACING else 0.dp

    /** 浮在上面的底栏一共占掉多少高度 —— 各 tab 的滚动内容末尾要留出这么多，否则最后一行被底栏压住。 */
    val bottomBarReserve: Dp
        get() = when (form) {
            ShellForm.GLASS_BAR -> GLASS_BAR_HEIGHT + glassBarBottomGap + bottomInset
            ShellForm.FLOATING_PILL -> FLOATING_BAR_HEIGHT + (if (bottomInset > 0.dp) 26.dp + bottomInset else 36.dp)
            ShellForm.CLASSIC_BAR, ShellForm.RAIL -> 0.dp
        }
}
