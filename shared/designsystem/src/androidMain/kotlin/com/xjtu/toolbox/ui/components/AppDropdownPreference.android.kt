package com.xjtu.toolbox.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xjtu.toolbox.ui.glass.coui.LocalCouiDarkTheme
import com.xjtu.toolbox.ui.glass.coui.menu.CouiMenuItem
import com.xjtu.toolbox.ui.glass.coui.menu.Menu
import com.xjtu.toolbox.ui.glass.coui.menu.couiMenuAnchor
import com.xjtu.toolbox.ui.glass.coui.menu.rememberCouiMenuAnchor
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/**
 * Android：菜单是 COUI 原厂组件（毛玻璃材质、开合动画、两级子菜单都在那边），
 * 用默认的触点模式——从这一行按下的位置长出来，就是「设置行展开成菜单」那套效果。
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
actual fun AppDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier,
    summary: String?,
    enabled: Boolean,
    startAction: (@Composable () -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    // 当前值：菜单展开时淡掉，收起时等菜单缩回去才回来
    val valueAlpha by animateFloatAsState(
        if (open) 0f else 1f,
        if (open) tween(160) else tween(260, delayMillis = 160),
        label = "dropdownValue",
    )
    val anchor = rememberCouiMenuAnchor()
    val entries = remember(items, selectedIndex) {
        items.mapIndexed { index, text -> CouiMenuItem(value = index, text = text, selected = index == selectedIndex) }
    }
    Box(modifier.couiMenuAnchor(anchor)) {
        DropdownPreferenceRow(
            title = title,
            summary = summary,
            enabled = enabled,
            startAction = startAction,
            value = items.getOrNull(selectedIndex).orEmpty(),
            valueAlpha = valueAlpha,
            holdDown = open,
            onClick = { anchor.open { open = true } },
        )
        // 菜单跟 App 的深浅色模式走，和顶栏按钮同一套口径。
        CompositionLocalProvider(LocalCouiDarkTheme provides LocalIsDarkTheme.current) {
            Menu(
                expanded = open,
                onDismissRequest = { open = false },
                items = entries,
                onItemClick = { item ->
                    open = false
                    onSelectedIndexChange(item.value)
                },
                anchor = anchor,
            )
        }
    }
}
