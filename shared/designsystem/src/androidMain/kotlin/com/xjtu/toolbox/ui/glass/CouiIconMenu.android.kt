package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.coui.LocalCouiDarkTheme
import com.xjtu.toolbox.ui.glass.coui.menu.CouiMenuPlacement
import com.xjtu.toolbox.ui.glass.coui.menu.CouiMenuItem
import com.xjtu.toolbox.ui.glass.coui.menu.Menu
import com.xjtu.toolbox.ui.glass.coui.menu.couiMenuAnchor
import com.xjtu.toolbox.ui.glass.coui.menu.rememberCouiMenuAnchor
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * Android：触发按钮复用自己的顶栏圆钮（[CouiToolbarActionButton]），菜单是 COUI 原厂组件
 * （材质、开合动画、两级子菜单都在那边）。菜单的窗口采样源由 App 根部的
 * `ProvideCouiMenuBackdrop` 提供，没有采样源时菜单退回不透明的实色面板。
 *
 * 顶栏按钮的菜单固定在按钮正下方、右边缘与按钮右边缘对齐（[CouiMenuPlacement.BelowElement]）：
 * 从触点长出来的那套是给设置里需要展开的行用的。
 */
@Composable
actual fun CouiIconMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    content: CouiMenuScope.() -> Unit,
) {
    // 锚点把手指按下的位置交给 COUI：菜单从触点长出来，滚动后位置也跟着按钮走
    val anchor = rememberCouiMenuAnchor()
    val items = rememberCouiMenuEntries(content).toCouiMenuItems()

    CouiToolbarActionButton(
        onClick = { anchor.open { onExpandedChange(true) } },
        modifier = modifier.couiMenuAnchor(anchor),
    ) { icon() }

    // 菜单跟 App 的深浅色模式走（含用户强制的深/浅色），不跟系统：和顶栏按钮同一套口径。
    CompositionLocalProvider(LocalCouiDarkTheme provides LocalIsDarkTheme.current) {
        Menu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            items = items,
            onItemClick = { item ->
                // 先收起再执行：菜单项自己的 onclick 里不用再写关闭
                onExpandedChange(false)
                item.value.invoke()
            },
            anchor = anchor,
            placement = CouiMenuPlacement.BelowElement,
            // 圆钮的触控区是 48dp、可见圆 40dp：内缩 4dp 让面板贴着圆的右下边缘，而不是触控外框。
            anchorInset = DpOffset(4.dp, 4.dp),
        )
    }
}

/** DSL 条目 → COUI 菜单项。value 就是点击时要执行的动作。 */
private fun List<CouiMenuEntry>.toCouiMenuItems(): List<CouiMenuItem<() -> Unit>> {
    val items = mutableListOf<CouiMenuItem<() -> Unit>>()
    // 分隔线记给下一项；COUI 的 Divider 是「下一项的 dividerBefore」
    var dividerBefore = false
    forEach { entry ->
        when (entry) {
            is CouiMenuEntry.Option -> {
                items += CouiMenuItem(
                    value = entry.onClick,
                    text = entry.text,
                    icon = entry.icon,
                    selected = entry.selected,
                    enabled = entry.enabled,
                    dividerBefore = dividerBefore,
                )
                dividerBefore = false
            }

            is CouiMenuEntry.Submenu -> {
                items += CouiMenuItem(
                    value = {},
                    text = entry.label,
                    icon = entry.icon,
                    dividerBefore = dividerBefore,
                    children = entry.children.toCouiChildItems(),
                )
                dividerBefore = false
            }

            CouiMenuEntry.Divider -> dividerBefore = true
        }
    }
    return items
}

/**
 * 二级条目 → COUI 子菜单项。
 *
 * COUI 只允许两级：子菜单里再套子菜单会抛 require，所以深层 Submenu 退化成不可点的叶子项
 * （文案保留，避免整棵菜单崩掉）。现有调用点都没有三级。
 */
private fun List<CouiMenuEntry>.toCouiChildItems(): List<CouiMenuItem<() -> Unit>> {
    val items = mutableListOf<CouiMenuItem<() -> Unit>>()
    var dividerBefore = false
    forEach { entry ->
        when (entry) {
            is CouiMenuEntry.Option -> {
                items += CouiMenuItem(
                    value = entry.onClick,
                    text = entry.text,
                    icon = entry.icon,
                    selected = entry.selected,
                    enabled = entry.enabled,
                    dividerBefore = dividerBefore,
                )
                dividerBefore = false
            }

            is CouiMenuEntry.Submenu -> {
                items += CouiMenuItem(
                    value = {},
                    text = entry.label,
                    icon = entry.icon,
                    enabled = false,
                    dividerBefore = dividerBefore,
                )
                dividerBefore = false
            }

            CouiMenuEntry.Divider -> dividerBefore = true
        }
    }
    return items
}
