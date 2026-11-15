package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
) = DropdownPreferenceLegacy(
    title, items, selectedIndex, onSelectedIndexChange, modifier, summary, enabled, startAction,
)
