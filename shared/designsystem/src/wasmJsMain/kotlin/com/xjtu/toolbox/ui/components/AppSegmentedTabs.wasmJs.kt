package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    small: Boolean,
    reselectable: Boolean,
) = SegmentedTabsFallback(labels, selectedIndex, onSelected, modifier, small, reselectable)
