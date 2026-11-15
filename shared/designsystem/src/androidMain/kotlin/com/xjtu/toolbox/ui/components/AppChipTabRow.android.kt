package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.ui.glass.coui.ChipTabRow
import com.xjtu.toolbox.ui.glass.coui.CouiChipTabItem
import com.xjtu.toolbox.ui.glass.coui.LocalCouiToolbarBackdrop
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

@Composable
internal actual fun <K : Any> PlatformChipTabRow(
    options: List<K>,
    selected: K,
    onSelect: (K) -> Unit,
    label: (K) -> String,
    containerColor: Color,
    modifier: Modifier,
    reselectable: Boolean,
    multi: Set<K>?,
) {
    val labels = options.map(label)
    val items = remember(options, labels) { options.zip(labels) { key, text -> CouiChipTabItem(key, text) } }
    // 顶栏里采顶栏那层模糊；页面里自己垫一条底色——不采整页，页面采样源里也录着这排胶囊自己
    val toolbar = LocalCouiToolbarBackdrop.current
    val own = if (toolbar == null) rememberCouiBackdrop() else null
    Box(modifier) {
        if (own != null) Box(Modifier.matchParentSize().couiBackdrop(own).background(containerColor))
        ChipTabRow(
            modifier = Modifier.fillMaxWidth(),
            items = items,
            selectedKey = selected,
            onSelectedKeyChange = { key -> options.firstOrNull { it == key }?.let(onSelect) },
            backdrop = toolbar ?: own!!,
            dark = LocalIsDarkTheme.current,
            reselectable = reselectable,
            selectedKeys = multi,
        )
    }
}
